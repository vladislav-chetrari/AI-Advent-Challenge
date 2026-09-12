package agent

import agent.data.CompressionSettings
import agent.data.CompressionStore
import agent.data.ModelStore
import agent.data.db.DatabaseFactory
import agent.data.db.SqliteChatRepository
import agent.domain.ChatMessage
import agent.domain.ChatRepository
import agent.domain.LlmModel
import agent.domain.SUMMARY_ROLE
import agent.network.ApiKeyProvider
import agent.network.LlmClient
import agent.network.LlmResult
import agent.network.TokenUsage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// --- Публичные ошибки агента (UI показывает только userMessage) ---
sealed class AgentError(val userMessage: String) {
    data object MissingKey : AgentError(
        "DEEPSEEK_API_KEY не найден. Задайте env-переменную или положите .env с DEEPSEEK_API_KEY рядом с проектом."
    )
    data object Unauthorized : AgentError("DeepSeek отклонил ключ (401). Проверьте DEEPSEEK_API_KEY.")
    // detail — текст ошибки как его дала модель (для Ollama извлекается внутренний
    // message из двойного JSON), UI показывает его как есть.
    data class ContextOverflow(val detail: String = "") : AgentError(
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

// Снапшот сжатия для UI (Task 4): флаг, окно N и текст summary (без system).
data class CompressionSnapshot(
    val enabled: Boolean = true,
    val keepLastN: Int = CompressionSettings.DEFAULT_KEEP_LAST_N,
    val summaryText: String = "",
)

// --- Агент: фасад Clean Architecture. HTTP — в LlmClient, история — в ChatRepository (SQLite+Flyway).
// Task 4: summary хранится отдельным сообщением role="summary" сразу после system.
// В БД лежит как есть, на провод к LLM уходит строго как system (см. effectiveHistoryLocked).
// Публичный API (ask/reset/historySnapshot/close/resolveApiKey) сохранён для UI и тестов. ---
class Agent(
    private var llmModel: LlmModel = LlmModel.DEEPSEEK,
    private val systemPrompt: String? = null,
    temperature: Double = 0.7,
    private val repository: ChatRepository,
    private val conversationId: String = ChatRepository.DEFAULT_CONVERSATION,
    private var llm: LlmClient = LlmClient(model = llmModel.apiId, temperature = temperature, baseUrl = llmModel.baseUrl),
    private val modelDir: File? = null,
    compression: CompressionSettings = CompressionSettings(),
) {
    constructor(
        systemPrompt: String? = null,
        temperature: Double = 0.7,
        dbFile: File = DatabaseFactory.defaultDbFile(),
        conversationId: String = ChatRepository.DEFAULT_CONVERSATION,
    ) : this(
        llmModel = ModelStore.load(dbFile.parentFile),
        systemPrompt = systemPrompt,
        temperature = temperature,
        repository = SqliteChatRepository(dbFile),
        conversationId = conversationId,
        modelDir = dbFile.parentFile,
        compression = CompressionStore.load(dbFile.parentFile),
    )

    val currentModel: LlmModel
        get() = synchronized(lock) { llmModel }

    private val history: MutableList<ChatMessage> = mutableListOf()
    private val lock = Any()
    private val errorJson: Json = Json { ignoreUnknownKeys = true }

    // Токен-статистика сессии — только по факту ответов API.
    private var lastUsage: TokenUsage = TokenUsage()
    private var sessionTokens: Long = 0
    private var sessionCostUsd: Double = 0.0

    // Task 4: окно памяти N задаёт пользователь (>= 1), хранится как ModelStore.
    private var compressionEnabled: Boolean = compression.enabled
    private var keepLastN: Int = compression.keepLastN.coerceAtLeast(1)

    init {
        val stored = try {
            repository.load(conversationId)
        } catch (_: Exception) {
            emptyList()
        }
        synchronized(lock) {
            history += stored
            // Синхронизация system-промпта: у старых БД лежит прошлый текст
            // (например "reply in English") — обновляем на актуальный из кода.
            // Summary при этом не трогаем, он отдельным сообщением.
            if (systemPrompt != null) {
                val first = history.firstOrNull()
                when {
                    first == null -> {
                        history += ChatMessage("system", systemPrompt)
                        persistLocked()
                    }
                    first.role == SUMMARY_ROLE -> {
                        history.add(0, ChatMessage("system", systemPrompt))
                        persistLocked()
                    }
                    first.role == "system" && first.content != systemPrompt -> {
                        history[0] = first.copy(content = systemPrompt)
                        persistLocked()
                    }
                }
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

    // Смена модели: чат стирается чтобы было проще (история другой модели
    // бессмысленна — токены и лимиты разные). Выбор сохраняется в файл.
    fun switchModel(next: LlmModel, temperature: Double = 0.7) {
        synchronized(lock) {
            if (next.id == llmModel.id) return
            try {
                llm.close()
            } catch (_: Exception) {
            }
            llmModel = next
            llm = LlmClient(model = next.apiId, temperature = temperature, baseUrl = next.baseUrl)
            history.clear()
            if (systemPrompt != null) history += ChatMessage("system", systemPrompt)
            persistLocked()
            lastUsage = TokenUsage()
            sessionTokens = 0
            sessionCostUsd = 0.0
            ModelStore.save(modelDir, next)
        }
    }

    fun historySnapshot(): List<Pair<String, String>> =
        synchronized(lock) {
            history.filter { it.role != "system" && it.role != SUMMARY_ROLE }.map { it.role to it.content }.toList()
        }

    // Полный снапшот с токенами для UI (Task 3). Старые сообщения без замера имеют 0.
    // Summary скрыто — UI показывает его отдельно через compressionSnapshot().
    fun historyWithTokens(): List<ChatMessage> =
        synchronized(lock) {
            history.filter { it.role != "system" && it.role != SUMMARY_ROLE }.toList()
        }

    fun statsSnapshot(): AgentStats =
        synchronized(lock) {
            AgentStats(
                sessionTokens = sessionTokens,
                sessionCostUsd = sessionCostUsd,
                contextTokens = lastUsage.promptTokens,
            )
        }

    // Task 4: настройки + текст summary для UI.
    fun compressionSnapshot(): CompressionSnapshot =
        synchronized(lock) {
            CompressionSnapshot(
                enabled = compressionEnabled,
                keepLastN = keepLastN,
                summaryText = history.firstOrNull { it.role == SUMMARY_ROLE }?.content.orEmpty(),
            )
        }

    fun setCompressionEnabled(enabled: Boolean) {
        synchronized(lock) {
            compressionEnabled = enabled
            CompressionStore.save(modelDir, CompressionSettings(enabled, keepLastN))
        }
    }

    fun setKeepLastN(n: Int) {
        synchronized(lock) {
            keepLastN = n.coerceAtLeast(1)
            CompressionStore.save(modelDir, CompressionSettings(compressionEnabled, keepLastN))
        }
    }

    suspend fun ask(prompt: String): AgentResult {
        val clean = prompt.trim()
        if (clean.isEmpty()) return AgentResult.Failure(AgentError.EmptyPrompt)

        val apiKey = resolveApiKey()
        if (currentModel.needsApiKey && apiKey.isNullOrBlank()) {
            return AgentResult.Failure(AgentError.MissingKey)
        }

        val toSend: List<ChatMessage> = synchronized(lock) {
            history += ChatMessage("user", clean)
            persistLocked()
            effectiveHistoryLocked()
        }

        return when (val r = llm.complete(toSend, apiKey)) {
            is LlmResult.Ok -> {
                val model = currentModel
                synchronized(lock) {
                    // Токены user-сообщения знаем только ПОСЛЕ ответа: дельта prompt_tokens
                    // минус предыдущий контекст и предыдущий ответ. Включает system/oверхед
                    // на первом витке — это ограничение API, а не оценка до отправки.
                    // Со сжатием summary уже сидит в system, дельта корректна как есть.
                    val userTokens =
                        (r.usage.promptTokens - lastUsage.promptTokens - lastUsage.completionTokens)
                            .coerceAtLeast(0)
                    val userCost = model.inputCostUsd(userTokens)
                    val assistantCost = model.outputCostUsd(r.usage.completionTokens)
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
                // Task 4: схлопывание старых сообщений в summary (не роняет ответ при фейле).
                try {
                    maybeCompress(apiKey)
                } catch (_: Exception) {
                }
                AgentResult.Success(r.text, r.usage)
            }
            is LlmResult.HttpError -> {
                rollbackLastUser()
                val err = when {
                    r.code == 401 -> AgentError.Unauthorized
                    isContextOverflow(r.code, r.detail) -> AgentError.ContextOverflow(extractModelError(r.detail))
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

    // Task 4: summary на провод уходит как system — API других ролей не знает.
    // Вызывать под lock'ом.
    private fun effectiveHistoryLocked(): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(history.size)
        for (m in history) {
            if (m.role == SUMMARY_ROLE) out += ChatMessage("system", SUMMARY_PREFIX + m.content)
            else out += m
        }
        return out
    }

    // Task 4: если живых сообщений больше N — схлопнуть старые в summary.
    // Держим (N-1) последних живых + 1 summary = N в памяти. N >= 1 всегда.
    // carried-токены/стоимость переезжают в summary, поэтому sessionTokens/Cost
    // монотонны и переживают рестарт через обычный sumOf(history).
    private suspend fun maybeCompress(apiKey: String?) {
        val enabled: Boolean
        val n: Int
        synchronized(lock) {
            enabled = compressionEnabled
            n = keepLastN.coerceAtLeast(1)
        }
        if (!enabled) return

        // Снапшот работы под lock'ом, сеть — без lock'а. Без nullable чтобы не спотыкаться о smart-cast.
        var evict: List<ChatMessage> = emptyList()
        var oldSummaryText: String = ""
        var needCompress = false
        synchronized(lock) {
            val liveIdx = history.indices.filter { history[it].role != "system" && history[it].role != SUMMARY_ROLE }
            val keepLive = (n - 1).coerceAtLeast(1)
            if (liveIdx.size > n) {
                val evictCount = liveIdx.size - keepLive
                evict = liveIdx.take(evictCount).map { history[it] }
                oldSummaryText = history.firstOrNull { it.role == SUMMARY_ROLE }?.content.orEmpty()
                needCompress = true
            }
        }
        if (!needCompress) return
        val evictSnapshot: List<ChatMessage> = evict.toList()

        val batchText = evictSnapshot.joinToString("\n") { "${it.role}: ${it.content}" }.take(6000)
        val prompt = buildList {
            add(ChatMessage("system", SUMMARIZER_SYSTEM))
            add(
                ChatMessage(
                    "user",
                    (if (oldSummaryText.isNotBlank()) "Previous summary:\n${oldSummaryText.take(2000)}\n\n" else "") +
                        "New messages to fold into the summary:\n$batchText",
                ),
            )
        }
        val r = try {
            llm.complete(prompt, apiKey)
        } catch (_: Exception) {
            return
        }
        val ok = r as? LlmResult.Ok ?: return
        val summaryText = ok.text.trim().take(2000).takeIf { it.isNotBlank() } ?: return
        val usage = ok.usage
        val model = currentModel

        synchronized(lock) {
            // Перепроверка: историю могли сбросить/очистить пока шла сеть.
            val liveNow = history.indices.filter { history[it].role != "system" && history[it].role != SUMMARY_ROLE }
            val keepLive = keepLastN.coerceAtLeast(1).let { (it - 1).coerceAtLeast(1) }
            if (liveNow.size <= keepLastN.coerceAtLeast(1)) return
            val evictCount = liveNow.size - keepLive
            val evictIdx = liveNow.take(evictCount)
            // Защита от гонки: схлопываем только если начало окна совпало.
            val evictMsgs = evictIdx.map { history[it] }
            if (evictMsgs.map { it.role to it.content } != evictSnapshot.map { it.role to it.content }) return
            val oldSummary = history.firstOrNull { it.role == SUMMARY_ROLE }
            val carriedTokens = evictMsgs.sumOf { it.tokens.toLong() } + (oldSummary?.tokens?.toLong() ?: 0)
            val carriedCost = evictMsgs.sumOf { it.costUsd } + (oldSummary?.costUsd ?: 0.0)
            val summCost = model.inputCostUsd(usage.promptTokens) + model.outputCostUsd(usage.completionTokens)
            val merged = ChatMessage(
                role = SUMMARY_ROLE,
                content = summaryText,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                totalTokens = usage.totalTokens,
                tokens = (carriedTokens + usage.promptTokens + usage.completionTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                costUsd = carriedCost + summCost,
            )
            // Удаляем evict с конца к началу чтобы не поплыли индексы.
            for (i in evictIdx.sortedDescending()) history.removeAt(i)
            val sysIdx = history.indexOfFirst { it.role == "system" }
            val sumIdx = history.indexOfFirst { it.role == SUMMARY_ROLE }
            if (sumIdx >= 0) history[sumIdx] = merged
            else if (sysIdx >= 0) history.add(sysIdx + 1, merged)
            else history.add(0, merged)
            persistLocked()
            sessionTokens += (usage.promptTokens + usage.completionTokens).toLong()
            sessionCostUsd += summCost
        }
    }

    private fun isContextOverflow(code: Int, detail: String): Boolean {
        if (code != 400 && code != 413 && code != 422) return false
        val d = detail.lowercase()
        return d.contains("context") ||
            d.contains("exceed_context_size_error") ||
            d.contains("exceeds the available context") ||
            d.contains("maximum context length") ||
            d.contains("too many tokens") ||
            d.contains("token limit") ||
            d.contains("contextwindow") ||
            d.contains("insufficient_system_resource")
    }

    // Текст ошибки как его дала модель. Формы:
    // - Ollama /api/chat: {"error":"{\"error\":{...\"message\":\"request (3555 tokens) exceeds...\"}}"}
    // - DeepSeek/OpenAI: {"error":{"message":"...","type":...}} или простой текст.
    private fun extractModelError(detail: String): String {
        try {
            val err = errorJson.parseToJsonElement(detail).jsonObject["error"]
                ?: return detail.take(500)
            if (err is JsonPrimitive) {
                val outer = err.contentOrNull
                if (outer.isNullOrBlank()) return detail.take(500)
                try {
                    errorJson.parseToJsonElement(outer).jsonObject["message"]
                        ?.let { it as? JsonPrimitive }?.contentOrNull
                        ?.takeIf { it.isNotBlank() }?.let { return it }
                } catch (_: Exception) {
                    // outer — уже готовый текст, а не вложенный JSON.
                }
                return outer.take(500)
            }
            if (err is JsonObject) {
                err["message"]?.let { it as? JsonPrimitive }?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (_: Exception) {
            // detail — не JSON, вернём как есть ниже.
        }
        return detail.take(500)
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
        const val SUMMARY_PREFIX: String = "Previous conversation summary (use as context, it replaces earlier messages):\n"
        const val SUMMARIZER_SYSTEM: String =
            "You are a concise dialogue summarizer. Summarize in Russian, keep names, facts, decisions and open questions. " +
                "Max ~200 tokens. Output only the summary, no preamble."

        var apiKeyOverride: String?
            get() = ApiKeyProvider.override
            set(value) {
                ApiKeyProvider.override = value
            }

        fun resolveApiKey(): String? = ApiKeyProvider.resolve()
    }
}
