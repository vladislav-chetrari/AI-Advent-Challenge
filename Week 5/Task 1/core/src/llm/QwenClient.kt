package core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ChatMessage(val role: String, val content: String)

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

sealed interface LlmResult {
    data class Ok(val text: String, val usage: TokenUsage = TokenUsage()) : LlmResult
    data class HttpError(val code: Int, val detail: String = "") : LlmResult
    data class NetworkError(val detail: String) : LlmResult
    data object Empty : LlmResult
}

@Serializable
private data class WireMessage(val role: String, val content: String)

@Serializable
private data class OllamaOptions(
    val temperature: Double = 0.7,
    val num_ctx: Int = 32_768,
)

// think=false глушит reasoning у qwen3 (иначе раздувает расход и ломает короткие ответы);
// qwen2.5 поле игнорирует. encodeDefaults=true ниже оставляет его на проводе.
@Serializable
private data class OllamaChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean = false,
    val think: Boolean = false,
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

@Serializable
private data class TagsModel(val name: String = "")

@Serializable
private data class TagsResponse(val models: List<TagsModel> = emptyList())

// Тонкий HTTP-клиент Ollama: нативный /api/chat (stream=false, приходит один JSON,
// парсим текстом — ContentNegotiation ndjson не берёт). Других зависимостей нет.
open class QwenClient(
    private val model: String = QwenModel.QWEN25_7B.apiId,
    private val temperature: Double = 0.7,
    baseUrl: String = "http://localhost:11434",
    private val numCtx: Int = 32_768,
) {
    val modelName: String get() = model

    private val base: String = baseUrl.trimEnd('/')

    // encodeDefaults=true — иначе think=false/num_ctx выпадают и Ollama думает/режет молча.
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }
    }

    suspend open fun complete(history: List<ChatMessage>): LlmResult {
        return try {
            val http = client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(
                    OllamaChatRequest(
                        model = model,
                        messages = history.map { WireMessage(it.role, it.content) },
                        options = OllamaOptions(temperature = temperature, num_ctx = numCtx),
                    )
                )
            }
            if (!http.status.isSuccess()) {
                val detail = runCatching { http.bodyAsText().take(500) }.getOrDefault("")
                return LlmResult.HttpError(http.status.value, detail)
            }
            val raw = runCatching { http.bodyAsText() }.getOrNull() ?: return LlmResult.Empty
            // Один JSON при stream=false, но терпим и NDJSON-поток (несколько строк).
            val acc = StringBuilder()
            var promptCount = 0
            var evalCount = 0
            var decodedAny = false
            for (line in raw.lines()) {
                val t = line.trim()
                if (t.isEmpty()) continue
                runCatching { json.decodeFromString<OllamaChatResponse>(t) }.getOrNull()?.let {
                    decodedAny = true
                    it.message?.content?.let(acc::append)
                    if (it.prompt_eval_count != 0) promptCount = it.prompt_eval_count
                    if (it.eval_count != 0) evalCount = it.eval_count
                }
            }
            if (!decodedAny) {
                runCatching { json.decodeFromString<OllamaChatResponse>(raw) }.getOrNull()?.let {
                    decodedAny = true
                    acc.clear().append(it.message?.content.orEmpty())
                    promptCount = it.prompt_eval_count
                    evalCount = it.eval_count
                }
            }
            if (!decodedAny) return LlmResult.Empty
            val text = acc.toString().trim()
            if (text.isEmpty()) LlmResult.Empty
            else LlmResult.Ok(text, TokenUsage(promptCount, evalCount, promptCount + evalCount))
        } catch (e: Exception) {
            LlmResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    // Имена моделей из `ollama list` (GET /api/tags). Пустой список = сервер недоступен.
    suspend open fun listModels(): List<String> {
        return try {
            val http = client.get("$base/api/tags")
            if (!http.status.isSuccess()) return emptyList()
            val raw = runCatching { http.bodyAsText() }.getOrNull() ?: return emptyList()
            runCatching { json.decodeFromString<TagsResponse>(raw) }.getOrNull()
                ?.models?.map { it.name }?.filter { it.isNotBlank() }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun close() = client.close()
}
