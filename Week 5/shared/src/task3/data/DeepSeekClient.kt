package task3.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class WireMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    val temperature: Double = 0.3,
    val stream: Boolean = false,
)

@Serializable
private data class ChatChoiceMessage(val content: String? = null)

@Serializable
private data class ChatChoice(val message: ChatChoiceMessage? = null)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

sealed interface DeepSeekResult {
    data class Ok(val text: String) : DeepSeekResult
    data class HttpError(val code: Int, val detail: String) : DeepSeekResult
    data class NetworkError(val detail: String) : DeepSeekResult
}

/** DeepSeek через OpenAI-совместимый endpoint (транспорт — HttpURLConnection, без Ktor). */
object DeepSeekClient {
    const val MODEL = "deepseek-chat"
    private const val BASE_URL = "https://api.deepseek.com"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(
        system: String,
        user: String,
        history: List<Pair<String, String>> = emptyList(),
        apiKey: String?,
    ): DeepSeekResult {
        if (apiKey.isNullOrBlank()) return DeepSeekResult.NetworkError("Нет API-ключа DeepSeek")
        val messages = mutableListOf(WireMessage("system", system))
        history.takeLast(12).forEach { (role, content) -> messages += WireMessage(role, content) }
        messages += WireMessage("user", user)
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model = MODEL, messages = messages),
        )
        val res = try {
            httpPostJson(
                "$BASE_URL/chat/completions",
                body,
                mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"),
            )
        } catch (e: Exception) {
            return DeepSeekResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
        if (res.code !in 200..299) return DeepSeekResult.HttpError(res.code, res.body.take(300))
        return try {
            val parsed = json.decodeFromString(ChatResponse.serializer(), res.body)
            val text = parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
            if (text.isEmpty()) DeepSeekResult.NetworkError("Пустой ответ модели") else DeepSeekResult.Ok(text)
        } catch (e: Exception) {
            DeepSeekResult.NetworkError("Не разобрал ответ: ${e.message}")
        }
    }
}
