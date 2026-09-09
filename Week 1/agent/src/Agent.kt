package agent

import agent.data.db.DatabaseFactory
import agent.data.db.SqliteChatRepository
import agent.domain.ChatMessage
import agent.domain.ChatRepository
import agent.domain.TokenPricing
import agent.network.ApiKeyProvider
import agent.network.DeepSeekClient
import agent.network.LlmResult
import agent.network.TokenUsage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- Публичные ошибки агента (UI показывает только userMessage) ---
sealed class AgentError(val userMessage: String) {
    data object MissingKey : AgentError(
        "DEEPSEEK_API_KEY не найден. Задайте env-переменную или положите .env с DEEPSEEK_API_KEY рядом с проектом."
    )
    data object Unauthorized : AgentError("DeepSeek отклонил ключ (401). Проверьте DEEPSEEK_API_KEY.")
    data object ContextOverflow : AgentError(
        "Контекст переполнен: история + запрос превысили лимит модели. Очистите историю и попробуйте снова."
    )
    data class Network(val detail: String) : AgentError("Сеть/запрос не удался: $detail")
    data class Api(val detail: String) : AgentError("DeepSeek API вернул ошибку: $detail")
    data object EmptyResponse : AgentError("DeepSeek вернул пустой ответ. Попробуйте еще раз.")
    data object EmptyPrompt : AgentError("Пустой запрос.")
}

sealed interface AgentResult {
    data class Success(val text: String, val usage: TokenUsage = TokenUsage()) : AgentResult
    data class Failure(val error: AgentError) : AgentResult
}

// Статистика токенов по факту ответов API (Task 3). Никаких оценок до отправки.
// sessionTokens/sessionCost — сумма по полю tokens/costUsd всех сообщений.
// contextTokens — размер истории (prompt_tokens) на момент последнего ответа.
data class AgentStats(
    val sessionTokens: Long = 0,
    val sessionCostUsd: Double = 0.0,
    val contextTokens: Int = 0,
)

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

    // Токен-статистика сессии — только по факту ответов API.
    private var lastUsage: TokenUsage = TokenUsage()
    private var sessionTokens: Long = 0
    private var sessionCostUsd: Double = 0.0

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
            // Гидратация статистики из SQLite: переживает рестарт (Task 2+3).
            val assistants = history.filter { it.role == "assistant" }
            sessionTokens = history.sumOf { it.tokens.toLong() }
            sessionCostUsd = history.sumOf { it.costUsd }
            lastUsage = assistants.lastOrNull()?.let {
                TokenUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens,
                )
            } ?: TokenUsage()
        }
    }

    fun reset() {
        synchronized(lock) {
            val system = history.firstOrNull()?.takeIf { it.role == "system" }
            history.clear()
            if (system != null) history += system
            persistLocked()
            lastUsage = TokenUsage()
            sessionTokens = 0
            sessionCostUsd = 0.0
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            history.clear()
            if (systemPrompt != null) history += ChatMessage("system", systemPrompt)
            persistLocked()
            lastUsage = TokenUsage()
            sessionTokens = 0
            sessionCostUsd = 0.0
        }
    }

    fun historySnapshot(): List<Pair<String, String>> =
        synchronized(lock) {
            history.filter { it.role != "system" }.map { it.role to it.content }.toList()
        }

    // Полный снапшот с токенами для UI (Task 3). Старые сообщения без замера имеют 0.
    fun historyWithTokens(): List<ChatMessage> =
        synchronized(lock) {
            history.filter { it.role != "system" }.toList()
        }

    fun statsSnapshot(): AgentStats =
        synchronized(lock) {
            AgentStats(
                sessionTokens = sessionTokens,
                sessionCostUsd = sessionCostUsd,
                contextTokens = lastUsage.promptTokens,
            )
        }

    suspend fun ask(prompt: String): AgentResult {
        val clean = prompt.trim()
        if (clean.isEmpty()) return AgentResult.Failure(AgentError.EmptyPrompt)

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
                    // Токены user-сообщения знаем только ПОСЛЕ ответа: дельта prompt_tokens
                    // минус предыдущий контекст и предыдущий ответ. Включает system/oверхед
                    // на первом витке — это ограничение API, а не оценка до отправки.
                    val userTokens =
                        (r.usage.promptTokens - lastUsage.promptTokens - lastUsage.completionTokens)
                            .coerceAtLeast(0)
                    val userCost = TokenPricing.inputCostUsd(userTokens)
                    val assistantCost = TokenPricing.outputCostUsd(r.usage.completionTokens)
                    val lastUserIdx = history.indexOfLast { it.role == "user" }
                    if (lastUserIdx >= 0) {
                        val u = history[lastUserIdx]
                        history[lastUserIdx] = u.copy(tokens = userTokens, costUsd = userCost)
                    }
                    history += ChatMessage(
                        role = "assistant",
                        content = r.text,
                        promptTokens = r.usage.promptTokens,
                        completionTokens = r.usage.completionTokens,
                        totalTokens = r.usage.totalTokens,
                        tokens = r.usage.completionTokens,
                        costUsd = assistantCost,
                    )
                    persistLocked()
                    lastUsage = r.usage
                    sessionTokens += (userTokens + r.usage.completionTokens).toLong()
                    sessionCostUsd += (userCost + assistantCost)
                }
                AgentResult.Success(r.text, r.usage)
            }
            is LlmResult.HttpError -> {
                rollbackLastUser()
                val err = when {
                    r.code == 401 -> AgentError.Unauthorized
                    isContextOverflow(r.code, r.detail) -> AgentError.ContextOverflow
                    else -> AgentError.Api("HTTP ${r.code}${if (r.detail.isNotBlank()) ": ${r.detail}" else ""}")
                }
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

    private fun isContextOverflow(code: Int, detail: String): Boolean {
        if (code != 400 && code != 413 && code != 422) return false
        val d = detail.lowercase()
        return d.contains("context") ||
            d.contains("maximum context length") ||
            d.contains("too many tokens") ||
            d.contains("token limit") ||
            d.contains("contextwindow") ||
            d.contains("insufficient_system_resource")
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
