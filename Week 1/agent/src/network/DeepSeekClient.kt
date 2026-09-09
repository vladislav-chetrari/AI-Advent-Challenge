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

// SRP: только HTTP к DeepSeek. Про историю и SQLite ничего не знает.
class DeepSeekClient(
    private val model: String = "deepseek-chat",
    private val temperature: Double = 0.7,
) {
    val modelName: String get() = model

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    suspend fun complete(history: List<ChatMessage>, apiKey: String): LlmResult {
        return try {
            val http = client.post("https://api.deepseek.com/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
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

    fun close() = client.close()
}
