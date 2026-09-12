package agent.network

import agent.domain.ChatMessage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class WireMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
)

@Serializable
private data class ChatChoiceMessage(val content: String? = null)

@Serializable
private data class ChatChoice(val message: ChatChoiceMessage? = null)

@Serializable
private data class CompletionTokensDetailsDto(val reasoning_tokens: Int = 0)

@Serializable
private data class UsageDto(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0,
    val prompt_cache_hit_tokens: Int = 0,
    val prompt_cache_miss_tokens: Int = 0,
    val completion_tokens_details: CompletionTokensDetailsDto? = null,
)

@Serializable
private data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
    val usage: UsageDto? = null,
)

// Нативный Ollama /api/chat: truncate/shift=false превращают молчаливое
// обрезание в честный HTTP 400 exceed_context_size_error (иначе /v1 всегда 200).
@Serializable
private data class OllamaOptions(val temperature: Double = 0.7)

@Serializable
private data class OllamaChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean = false,
    val truncate: Boolean = false,
    val shift: Boolean = false,
    val options: OllamaOptions,
)

@Serializable
private data class OllamaMessage(val role: String? = null, val content: String? = null)

@Serializable
private data class OllamaChatResponse(
    val message: OllamaMessage? = null,
    val prompt_eval_count: Int = 0,
    val eval_count: Int = 0,
)

// Публичные токены ответа — только по факту ответа API. Оценка до отправки не делается (Task 3).
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val reasoningTokens: Int = 0,
)

sealed interface LlmResult {
    data class Ok(val text: String, val usage: TokenUsage = TokenUsage()) : LlmResult
    data class HttpError(val code: Int, val detail: String = "") : LlmResult
    data class NetworkError(val detail: String) : LlmResult
    data object Empty : LlmResult
}

// SRP: только HTTP (DeepSeek через OpenAI-совместимый endpoint, локальная Ollama
// через нативный /api/chat с truncate=false/shift=false чтобы переполнение давало
// HTTP 400 вместо молчаливого sliding window). Про историю и SQLite ничего не знает.
// open для тестов Task 4 (FakeLlmClient переопределяет complete).
open class LlmClient(
    private val model: String = "deepseek-chat",
    private val temperature: Double = 0.7,
    private val baseUrl: String = "https://api.deepseek.com",
) {
    val modelName: String get() = model

    // Локалка определяется по порту Ollama; облако — всё остальное.
    private val useNativeOllama: Boolean =
        baseUrl.contains("localhost:11434") || baseUrl.contains("127.0.0.1:11434")

    private val nativeBase: String = baseUrl.substringBefore("/v1").trimEnd('/')

    // Важно: encodeDefaults=true — иначе kotlinx.serialization выкидывает
    // truncate=false/shift=false с провода и Ollama молча режет историю.
    private val lenientJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(lenientJson)
            }
        }
    }

    suspend open fun complete(history: List<ChatMessage>, apiKey: String?): LlmResult {
        return try {
            if (useNativeOllama) completeNative(history) else completeOpenAi(history, apiKey)
        } catch (e: Exception) {
            LlmResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun completeOpenAi(history: List<ChatMessage>, apiKey: String?): LlmResult {
        return try {
            val http = client.post("${baseUrl.trimEnd('/')}/chat/completions") {
                if (!apiKey.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        model = model,
                        messages = history.map { WireMessage(it.role, it.content) },
                        temperature = temperature,
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
            val text = parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
            if (text.isEmpty()) LlmResult.Empty else {
                val u = parsed.usage
                LlmResult.Ok(
                    text,
                    TokenUsage(
                        promptTokens = u?.prompt_tokens ?: 0,
                        completionTokens = u?.completion_tokens ?: 0,
                        totalTokens = u?.total_tokens ?: 0,
                        cacheHitTokens = u?.prompt_cache_hit_tokens ?: 0,
                        cacheMissTokens = u?.prompt_cache_miss_tokens ?: 0,
                        reasoningTokens = u?.completion_tokens_details?.reasoning_tokens ?: 0,
                    ),
                )
            }
        } catch (e: Exception) {
            LlmResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun completeNative(history: List<ChatMessage>): LlmResult {
        return try {
            val payload = OllamaChatRequest(
                model = model,
                messages = history.map { WireMessage(it.role, it.content) },
                stream = false,
                truncate = false,
                shift = false,
                options = OllamaOptions(temperature = temperature),
            )
            val http = client.post("$nativeBase/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (!http.status.isSuccess()) {
                val detail = try {
                    http.bodyAsText().take(500)
                } catch (_: Exception) {
                    ""
                }
                return LlmResult.HttpError(http.status.value, detail)
            }
            val raw = try {
                // Ollama отдает application/x-ndjson даже при stream=false —
                // ContentNegotiation его не берет, парсим текст вручную.
                // На всякий случай терпим и NDJSON-поток (несколько JSON-строк).
                http.bodyAsText()
            } catch (_: Exception) {
                return LlmResult.Empty
            }
            var textAcc = StringBuilder()
            var promptCount = 0
            var evalCount = 0
            var decodedAny = false
            for (line in raw.lines()) {
                val t = line.trim()
                if (t.isEmpty()) continue
                try {
                    val part = lenientJson.decodeFromString<OllamaChatResponse>(t)
                    decodedAny = true
                    part.message?.content?.let { textAcc.append(it) }
                    if (part.prompt_eval_count != 0) promptCount = part.prompt_eval_count
                    if (part.eval_count != 0) evalCount = part.eval_count
                } catch (_: Exception) {
                    // Игнорируем битую строку, пробуем остальные.
                }
            }
            // Фолбэк: вдруг пришел один JSON с переносами строк внутри content.
            if (!decodedAny) {
                try {
                    val part = lenientJson.decodeFromString<OllamaChatResponse>(raw)
                    decodedAny = true
                    textAcc = StringBuilder(part.message?.content.orEmpty())
                    promptCount = part.prompt_eval_count
                    evalCount = part.eval_count
                } catch (_: Exception) {
                    return LlmResult.Empty
                }
            }
            val text = textAcc.toString().trim()
            if (text.isEmpty()) LlmResult.Empty else {
                LlmResult.Ok(
                    text,
                    TokenUsage(
                        promptTokens = promptCount,
                        completionTokens = evalCount,
                        totalTokens = promptCount + evalCount,
                    ),
                )
            }
        } catch (e: Exception) {
            LlmResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun close() = client.close()
}
