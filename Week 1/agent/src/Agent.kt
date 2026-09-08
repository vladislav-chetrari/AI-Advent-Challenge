package agent

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
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
import java.nio.file.Paths

// --- Публичные ошибки агента (UI показывает только userMessage) ---
sealed class AgentError(val userMessage: String) {
    data object MissingKey : AgentError(
        "DEEPSEEK_API_KEY не найден. Задайте env-переменную или положите .env с DEEPSEEK_API_KEY рядом с проектом."
    )
    data object Unauthorized : AgentError("DeepSeek отклонил ключ (401). Проверьте DEEPSEEK_API_KEY.")
    data class Network(val detail: String) : AgentError("Сеть/запрос не удался: $detail")
    data class Api(val detail: String) : AgentError("DeepSeek API вернул ошибку: $detail")
    data object EmptyResponse : AgentError("DeepSeek вернул пустой ответ. Попробуйте еще раз.")
}

sealed interface AgentResult {
    data class Success(val text: String) : AgentResult
    data class Failure(val error: AgentError) : AgentResult
}

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
)

@Serializable
private data class ChatChoiceMessage(val content: String? = null)

@Serializable
private data class ChatChoice(val message: ChatChoiceMessage? = null)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

// --- Агент: отдельная сущность, вся работа с DeepSeek инкапсулирована здесь ---
class Agent(
    private val model: String = "deepseek-chat",
    systemPrompt: String? = null,
    private val temperature: Double = 0.7,
) {
    private val history: MutableList<ChatMessage> = mutableListOf()

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    init {
        if (systemPrompt != null) {
            history += ChatMessage("system", systemPrompt)
        }
    }

    fun reset() {
        val system = history.firstOrNull()?.takeIf { it.role == "system" }
        history.clear()
        if (system != null) history += system
    }

    fun historySnapshot(): List<Pair<String, String>> =
        history.filter { it.role != "system" }.map { it.role to it.content }

    suspend fun ask(prompt: String): AgentResult {
        val clean = prompt.trim()
        if (clean.isEmpty()) return AgentResult.Failure(AgentError.Api("Пустой запрос."))

        val apiKey = resolveApiKey()
        if (apiKey.isNullOrBlank()) return AgentResult.Failure(AgentError.MissingKey)

        history += ChatMessage("user", clean)
        return try {
            val http = client.post("https://api.deepseek.com/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(model = model, messages = history.toList(), temperature = temperature))
                // Таймауты по умолчанию CIO; при желании добавить HttpTimeout
            }
            if (!http.status.isSuccess()) {
                history.removeLastOrNull()
                val err = when (http.status.value) {
                    401 -> AgentError.Unauthorized
                    else -> AgentError.Api("HTTP ${http.status.value}")
                }
                return AgentResult.Failure(err)
            }
            val parsed = http.body<ChatResponse>()
            val text = parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
            if (text.isEmpty()) {
                history.removeLastOrNull()
                AgentResult.Failure(AgentError.EmptyResponse)
            } else {
                history += ChatMessage("assistant", text)
                AgentResult.Success(text)
            }
        } catch (e: Exception) {
            history.removeLastOrNull()
            AgentResult.Failure(AgentError.Network(e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun close() {
        client.close()
    }

    companion object {
        @Volatile
        var apiKeyOverride: String? = null

        // Ключ — ответственность модуля агента: override -> env -> .env файлы вверх по дереву.
        // Не-null override авторитативен (даже пустой = форсировать MissingKey, удобно для тестов).
        fun resolveApiKey(): String? {
            if (apiKeyOverride != null) return apiKeyOverride!!.trim().ifBlank { null }
            System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
            return findDotEnvKey()
        }

        private fun findDotEnvKey(): String? {
            return try {
                var dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
                repeat(5) {
                    val dotEnv = dir.resolve(".env").toFile()
                    if (dotEnv.isFile) {
                        dotEnv.readLines().forEach { line ->
                            val t = line.trim()
                            if (t.startsWith("DEEPSEEK_API_KEY")) {
                                val value = t.substringAfter("=", "").trim().trim('"', '\'')
                                if (value.isNotBlank()) return value
                            }
                        }
                    }
                    dir = dir.parent ?: return null
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}
