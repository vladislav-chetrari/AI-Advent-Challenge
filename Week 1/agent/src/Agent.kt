package agent

import agent.data.db.DatabaseFactory
import agent.data.db.SqliteChatRepository
import agent.domain.ChatMessage
import agent.domain.ChatRepository
import agent.network.ApiKeyProvider
import agent.network.DeepSeekClient
import agent.network.LlmResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

// --- Агент: фасад Clean Architecture. HTTP — в DeepSeekClient, история — в ChatRepository (SQLite+Flyway).
// Публичный API (ask/reset/historySnapshot/close/resolveApiKey) сохранён для UI и тестов. ---
class Agent(
    model: String = "deepseek-chat",
    private val systemPrompt: String? = null,
    temperature: Double = 0.7,
    private val repository: ChatRepository,
    private val conversationId: String = ChatRepository.DEFAULT_CONVERSATION,
    private val llm: DeepSeekClient = DeepSeekClient(model = model, temperature = temperature),
) {
    constructor(
        model: String = "deepseek-chat",
        systemPrompt: String? = null,
        temperature: Double = 0.7,
        dbFile: File = DatabaseFactory.defaultDbFile(),
        conversationId: String = ChatRepository.DEFAULT_CONVERSATION,
    ) : this(
        model = model,
        systemPrompt = systemPrompt,
        temperature = temperature,
        repository = SqliteChatRepository(dbFile),
        conversationId = conversationId,
        llm = DeepSeekClient(model = model, temperature = temperature),
    )

    private val history: MutableList<ChatMessage> = mutableListOf()
    private val lock = Any()

    init {
        val stored = try {
            repository.load(conversationId)
        } catch (_: Exception) {
            emptyList()
        }
        synchronized(lock) {
            history += stored
            if (history.isEmpty() && systemPrompt != null) {
                history += ChatMessage("system", systemPrompt)
                persistLocked()
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            val system = history.firstOrNull()?.takeIf { it.role == "system" }
            history.clear()
            if (system != null) history += system
            persistLocked()
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            history.clear()
            if (systemPrompt != null) history += ChatMessage("system", systemPrompt)
            persistLocked()
        }
    }

    fun historySnapshot(): List<Pair<String, String>> =
        synchronized(lock) {
            history.filter { it.role != "system" }.map { it.role to it.content }.toList()
        }

    suspend fun ask(prompt: String): AgentResult {
        val clean = prompt.trim()
        if (clean.isEmpty()) return AgentResult.Failure(AgentError.Api("Пустой запрос."))

        val apiKey = resolveApiKey()
        if (apiKey.isNullOrBlank()) return AgentResult.Failure(AgentError.MissingKey)

        val toSend: List<ChatMessage> = synchronized(lock) {
            history += ChatMessage("user", clean)
            persistLocked()
            history.toList()
        }

        return when (val r = llm.complete(toSend, apiKey)) {
            is LlmResult.Ok -> {
                synchronized(lock) {
                    history += ChatMessage("assistant", r.text)
                    persistLocked()
                }
                AgentResult.Success(r.text)
            }
            is LlmResult.HttpError -> {
                rollbackLastUser()
                val err = if (r.code == 401) AgentError.Unauthorized else AgentError.Api("HTTP ${r.code}")
                AgentResult.Failure(err)
            }
            is LlmResult.Empty -> {
                rollbackLastUser()
                AgentResult.Failure(AgentError.EmptyResponse)
            }
            is LlmResult.NetworkError -> {
                rollbackLastUser()
                AgentResult.Failure(AgentError.Network(r.detail))
            }
        }
    }

    suspend fun close() {
        withContext(Dispatchers.IO) { repository.close() }
        llm.close()
    }

    private fun rollbackLastUser() {
        synchronized(lock) {
            if (history.lastOrNull()?.role == "user") history.removeLastOrNull()
            persistLocked()
        }
    }

    private fun persistLocked() {
        try {
            repository.replaceAll(conversationId, history.toList())
        } catch (_: Exception) {
            // Историю в памяти не теряем; SQLite/Flyway ошибки не должны ронять чат.
            // UI всё равно покажет ошибку сети/БД при следующем рестарте через пустой load.
        }
    }

    companion object {
        var apiKeyOverride: String?
            get() = ApiKeyProvider.override
            set(value) {
                ApiKeyProvider.override = value
            }

        fun resolveApiKey(): String? = ApiKeyProvider.resolve()
    }
}
