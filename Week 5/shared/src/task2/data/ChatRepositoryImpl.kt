package task2.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import task2.db.ChatDao
import task2.db.MessageEntity
import task2.domain.AiModel
import task2.domain.ChatMessage
import task2.domain.ChatRepository
import task2.domain.EngineStatus
import task2.domain.MemoryStore
import task2.llama.LlamaBridge

/**
 * SSOT: _messages — единственный источник сообщений для UI.
 * Долговременное хранение — Room (таблицы messages/facts, файл chat.db):
 * история и факты переживают перезапуск. Запись сквозная (write-through),
 * чтение истории — один раз при старте.
 * Контекст модели = sliding window (10 последних) + факты из MemoryStore.
 * Факты извлекаются детерминированными правилами прямо из реплики
 * пользователя (мгновенно, без второго прохода LLM).
 * KISS: модель лежит в modelsDir/fileName, грузится один раз на выбор.
 */
class ChatRepositoryImpl(
    private val bridge: LlamaBridge,
    private val memory: MemoryStore,
    private val dao: ChatDao,
) : ChatRepository {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private var loadedPath: String? = null
    private var historyLoaded = false
    private var idSeq = 1L

    override suspend fun ensureModel(model: AiModel, modelsDir: String) {
        memory.load()
        if (!historyLoaded) {
            historyLoaded = true
            runCatching {
                _messages.value = dao.getMessages().map {
                    ChatMessage(idSeq++, mapRole(it.role), it.text)
                }
            }
        }
        val dest = "$modelsDir/${model.fileName}"
        if (!platformFileExists(dest)) {
            _status.value = EngineStatus.Downloading(0)
            try {
                downloadFile(model.downloadUrl, dest) { done, total ->
                    val p = total?.let { ((done * 100) / it).toInt().coerceIn(0, 100) } ?: 0
                    _status.value = EngineStatus.Downloading(p)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _status.value = EngineStatus.Error("Не скачалась модель: ${e.message}")
                return
            }
        }
        if (loadedPath != dest || !bridge.isReady) {
            _status.value = EngineStatus.LoadingModel
            try {
                bridge.load(dest, model.defaultCtx, platformCpuThreads())
                loadedPath = dest
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _status.value = EngineStatus.Error("Не загрузилась в LiteRT: ${e.message}")
                return
            }
        }
        _status.value = EngineStatus.Ready
    }

    override suspend fun send(model: AiModel, modelsDir: String, prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty() || _status.value is EngineStatus.Generating) return
        ensureModel(model, modelsDir)
        if (_status.value !is EngineStatus.Ready) return

        // Факты — синхронно и сразу: правила мгновенные, а текущий ответ
        // уже сможет использовать только что названное имя.
        val fresh = FactExtractor.extractFrom(clean)
        if (fresh.isNotEmpty()) {
            runCatching { memory.addFacts(fresh) }
        }

        val userMsg = ChatMessage(idSeq++, ChatMessage.Role.USER, clean)
        val draftId = idSeq++
        _messages.update { it + userMsg + ChatMessage(draftId, ChatMessage.Role.ASSISTANT, "") }
        runCatching {
            dao.insertMessage(MessageEntity(role = "USER", text = clean, createdAt = nowMillis()))
        }
        _status.value = EngineStatus.Generating
        try {
            val system = PromptBuilder.chatSystem(facts = memory.facts.value.map { it.text })
            val user = PromptBuilder.chatUser(
                history = _messages.value.dropLast(2),
                userPrompt = clean,
            )
            var acc = ""
            bridge.generateChat(system, user).collect { delta ->
                acc += delta
                _messages.update { list ->
                    list.map { if (it.id == draftId) it.copy(text = acc) else it }
                }
            }
            val clean = PromptBuilder.cleanReply(acc).ifEmpty { acc.trim() }
            _messages.update { list ->
                list.map { if (it.id == draftId) it.copy(text = clean) else it }
            }
            runCatching {
                dao.insertMessage(MessageEntity(role = "ASSISTANT", text = clean, createdAt = nowMillis()))
            }
            _status.value = EngineStatus.Ready
        } catch (e: CancellationException) {
            bridge.cancel()
            _status.value = EngineStatus.Ready
        } catch (e: Exception) {
            _status.value = EngineStatus.Error("Генерация упала: ${e.message}")
        }
    }

    override fun cancel() {
        bridge.cancel()
        _status.value = EngineStatus.Ready
    }

    override suspend fun clear() {
        _messages.value = emptyList()
        runCatching { dao.clearMessages() }
    }

    private fun mapRole(role: String): ChatMessage.Role =
        if (role == "ASSISTANT") ChatMessage.Role.ASSISTANT else ChatMessage.Role.USER
}

expect fun platformFileExists(path: String): Boolean
expect fun platformCpuThreads(): Int
