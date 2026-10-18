package core.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class WireFunctionCall(val name: String, val arguments: String = "")

@Serializable
data class WireToolCall(
    val id: String,
    val type: String = "function",
    val function: WireFunctionCall,
)

@Serializable
data class WireToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class WireTool(
    val type: String = "function",
    val function: WireToolFunction,
)

@Serializable
private data class WireMessage(
    val role: String,
    // null обязателен для role="tool" и для assistant с одним tool_calls:
    // пустая строка вместо null некоторые провайдеры считают текстовым ответом.
    val content: String? = null,
    @kotlinx.serialization.SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @kotlinx.serialization.SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    val temperature: Double = 0.3,
    val stream: Boolean = false,
    // null = без ограничения. Поддержке нужен потолок: короткий ответ оператора
    // не даёт модели уйти в перечисление выдуманных шагов и сорваться на другой язык.
    val maxTokens: Int? = null,
    // Task 4: function-calling. null = tools не шлём, и ответ всегда LlmResult.Ok —
    // так ведут себя все потребители кроме агента (ask/askPlain/ревью/саппорт).
    val tools: List<WireTool>? = null,
    // "none" — запретить вызовы (opencode так глушит тулы на лимите шагов).
    val toolChoice: String? = null,
)

@Serializable
private data class ChatChoiceMessage(
    val content: String? = null,
    @kotlinx.serialization.SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
)

@Serializable
private data class ChatChoice(val message: ChatChoiceMessage? = null)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

sealed interface LlmResult {
    data class Ok(val text: String) : LlmResult
    // Task 4: модель запросила тулы. text может быть непустым — модели часто
    // пишут «сейчас посмотрю» рядом с вызовом, и это стоит показать в UI.
    data class Tools(val text: String, val calls: List<ToolCall>) : LlmResult
    data class HttpError(val code: Int, val detail: String = "") : LlmResult
    data class NetworkError(val detail: String) : LlmResult
    data object Empty : LlmResult
}

// Сериализуется намеренно: транскрипт агента сохраняется на диск, и без
// @Serializable продолжить прогон после остановки было бы нечем.
@kotlinx.serialization.Serializable
data class ChatMsg(
    val role: String,
    val content: String,
    // Task 4: ответная часть assistant-сообщения с вызовами тулов. Её обязательно
    // надо вернуть провайдеру ДО сообщений role="tool" — без эха OpenAI-совместимые
    // API (и DeepSeek, и Ollama) отклоняют следующий запрос.
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
)

// LLM через OpenAI-совместимый endpoint: DeepSeek-облако ИЛИ локальная Ollama.
// Историй не знает — только complete(). Для Ollama ключ не нужен,
// endpoint — {base}/v1/chat/completions; для DeepSeek — {base}/chat/completions.
open class LlmClient(
    private val model: String = "deepseek-chat",
    private val temperature: Double = 0.3,
    private val baseUrl: String = "https://api.deepseek.com",
    private val provider: String = LlmSettings.PROVIDER_DEEPSEEK,
) {
    val label: String get() = "$provider:$model"
    // encodeDefaults + explicitNulls=false: в теле запроса обязаны быть
    // "type":"function" у каждого тула (без него провайдер не считает тул
    // функцией), а вот null-поля — наоборот лишние (max_tokens/content у
    // сообщения с вызовом тула). Раньше encodeDefaults был выключен, и
    // "type" молча терялся.
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            // Локальная 32–70B на MPS/CPU отвечает минутами — дефолтных таймаутов не хватает.
            install(HttpTimeout) { requestTimeoutMillis = 600_000 }
        }
    }

    // temperature — опциональный override на вызов: фактические RAG-ответы
    // идут почти детерминированно (ANSWER_TEMPERATURE), служебные
    // (rewrite/память) и живой чат — на температуре из настроек.
    suspend open fun complete(
        history: List<ChatMsg>,
        apiKey: String?,
        temperatureOverride: Double? = null,
        maxTokens: Int? = null,
        // Task 4: список тулов. Пока пусто/null — ведём себя как раньше
        // (Ok/Empty), чтобы не трогать остальных потребителей.
        tools: List<ToolSpec>? = null,
        toolChoice: String? = null,
    ): LlmResult {
        return try {
            val endpoint = LlmSettings(provider = provider, model = model, baseUrl = baseUrl, temperature = temperature).endpoint()
            val http = client.post(endpoint) {
                if (!apiKey.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        model = model,
                        messages = history.map { it.toWire() },
                        temperature = temperatureOverride ?: temperature,
                        maxTokens = maxTokens,
                        tools = tools?.takeIf { it.isNotEmpty() }?.map { it.toWire() },
                        toolChoice = toolChoice,
                    )
                )
            }
            if (!http.status.isSuccess()) {
                val detail = try {
                    http.bodyAsText().take(500)
                } catch (_: Exception) {
                    ""
                }
                return LlmResult.HttpError(http.status.value, detail)
            }
            val parsed = http.body<ChatResponse>()
            val msg = parsed.choices.firstOrNull()?.message
            val text = msg?.content?.trim().orEmpty()
            val rawCalls = msg?.toolCalls.orEmpty()
            if (rawCalls.isEmpty()) {
                if (text.isEmpty()) LlmResult.Empty else LlmResult.Ok(text)
            } else {
                LlmResult.Tools(text, rawCalls.mapIndexed { i, c -> c.toToolCall(i) })
            }
        } catch (e: Exception) {
            LlmResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun close() = client.close()
}

private fun ChatMsg.toWire(): WireMessage = WireMessage(
    role = role,
    // null вместо "" — см. комментарий у WireMessage.
    content = content.takeIf { it.isNotEmpty() || (toolCalls.isNullOrEmpty() && role != "tool") },
    toolCalls = toolCalls?.takeIf { it.isNotEmpty() }?.map {
        WireToolCall(id = it.id, function = WireFunctionCall(name = it.name, arguments = it.rawArgs))
    },
    toolCallId = toolCallId,
    name = name,
)

// Аргументы приходят строкой JSON и у мелких локальных моделей регулярно
// бывают битыми (обрезанный JSON, опечатки в кавычках). Такие не роняем:
// отдаём пустой объект и НЕвалидные — имён, которые агент перечислит модели
// в тексте ошибки, иначе цикл молча зависнет на одном и том же вызове.
private fun WireToolCall.toToolCall(ord: Int): ToolCall {
    val safeId = id.ifBlank { "call_${ord}_${function.name.take(16)}" }
    val parsed = parseToolArgs(function.arguments)
    return ToolCall(
        id = safeId,
        name = function.name,
        arguments = parsed ?: JsonObject(emptyMap()),
        rawArgs = function.arguments.ifBlank { parsed?.toString() ?: "{}" },
        rejected = parsed == null && function.arguments.isNotBlank(),
    )
}
