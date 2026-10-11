package desktop.ui

import core.api.ChatMessageDto
import core.api.DocListItem
import core.api.Task5Client
import core.api.Task5Exception
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class UiState(
    val chats: List<LocalChat> = emptyList(),
    val activeChatId: String? = null,
    val messages: List<LocalMessage> = emptyList(),
    val docs: List<DocListItem> = emptyList(),
    val input: String = "",
    val busy: Boolean = false,
    val docsBusy: Boolean = false,
    val status: String? = null,
    // Строка здоровья сервера: "llm=.. · embed=.. · ollama ok · доков: N · чанков: M"
    // или "сервер недоступен — подними: ./kotlin run --module server".
    val healthLine: String = "…",
    val baseUrl: String = "http://localhost:8080",
    val model: String = "",
    val models: List<String> = emptyList(),
    val temperature: Double = 0.7,
    val maxTokens: Int = 512,
    val system: String = "",
    val showCreateChat: Boolean = false,
    val createName: String = "",
    val showAddDoc: Boolean = false,
    val docType: String = "wiki", // text | wiki
    val docTitle: String = "",
    val docInput: String = "",
    val showSettings: Boolean = false,
    val settingsBaseUrl: String = "",
    val settingsKey: String = "",
    val settingsModel: String = "",
    val settingsTemp: String = "",
    val settingsMaxTokens: String = "",
    val settingsSystem: String = "",
    val settingsCheck: String? = null,
    val settingsBusy: Boolean = false,
) {
    val docsSummary: String
        get() = if (docs.isEmpty()) "база пуста" else
            "документов: ${docs.size} · чанков: ${docs.sumOf { it.chunks }}"
}

class AppViewModel(private val scope: CoroutineScope) {
    private val chats = ChatStore()
    private val settingsStore = DesktopSettingsStore()
    private var settings: DesktopSettings = settingsStore.load()
    private var client = Task5Client(settings.baseUrl, settings.apiKey)

    private val _state = MutableStateFlow(
        UiState(
            chats = chats.state.chats,
            activeChatId = chats.state.chats.firstOrNull()?.id,
            messages = chats.state.messages[chats.state.chats.firstOrNull()?.id].orEmpty(),
            baseUrl = settings.baseUrl,
            model = settings.model,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            system = settings.system,
        ),
    )
    val state: StateFlow<UiState> = _state

    init {
        refreshAll()
    }

    private fun update(fn: (UiState) -> UiState) {
        _state.value = fn(_state.value)
    }

    private fun rebuildClient() {
        runCatching { client.close() }
        client = Task5Client(settings.baseUrl, settings.apiKey)
    }

    // --- init refresh ---

    fun refreshAll() {
        refreshHealth()
        refreshModels()
        refreshDocs()
    }

    fun refreshHealth() {
        scope.launch {
            try {
                val h = client.health()
                val ollama = if (h.ollama_ok) "ollama ok" else "ollama НЕТ"
                update {
                    it.copy(
                        healthLine = "llm=${h.llm.ifBlank { "?" }} · embed=${h.embed.ifBlank { "?" }} · $ollama · " +
                            "доков: ${h.rag_docs} · чанков: ${h.rag_chunks}",
                        status = null,
                    )
                }
            } catch (e: Exception) {
                update {
                    it.copy(
                        healthLine = "сервер недоступен — подними: ./kotlin run --module server",
                        status = "Нет связи с ${settings.baseUrl}: ${e.message?.take(160)}",
                    )
                }
            }
        }
    }

    fun refreshModels() {
        scope.launch {
            try {
                val m = client.models()
                update { it.copy(models = m.data.map { d -> d.id }) }
            } catch (_: Exception) {
                // /v1/models за auth — без ключа придёт 401, это нормально, молчим.
            }
        }
    }

    fun refreshDocs() {
        scope.launch {
            update { it.copy(docsBusy = true) }
            try {
                val docs = client.docsList()
                update { it.copy(docs = docs, docsBusy = false, status = null) }
            } catch (e: Task5Exception) {
                update { it.copy(docsBusy = false, status = "Документы: HTTP ${e.httpStatus} [${e.code}]: ${e.message.take(200)}") }
            } catch (e: Exception) {
                update { it.copy(docsBusy = false, status = "Документы: ${e.message?.take(200)}") }
            }
        }
    }

    // --- чаты (локальные, сервер stateless) ---

    fun openCreateChat() = update { it.copy(showCreateChat = true, createName = "") }
    fun closeCreateChat() = update { it.copy(showCreateChat = false) }
    fun setCreateName(v: String) = update { it.copy(createName = v) }

    fun commitCreateChat() {
        val name = _state.value.createName.trim().ifBlank { "Чат" }
        val chat = LocalChat(id = newChatId(), name = name)
        chats.upsertChat(chat)
        update {
            it.copy(
                showCreateChat = false, chats = chats.state.chats,
                activeChatId = chat.id, messages = emptyList(), status = null,
            )
        }
    }

    fun selectChat(id: String) {
        update { it.copy(activeChatId = id, messages = chats.state.messages[id].orEmpty(), status = null) }
    }

    fun deleteChat(id: String) {
        chats.deleteChat(id)
        val rest = chats.state.chats
        val next = if (_state.value.activeChatId == id) rest.firstOrNull()?.id else _state.value.activeChatId
        update { it.copy(chats = rest, activeChatId = next, messages = chats.state.messages[next].orEmpty()) }
    }

    fun clearChat() {
        val id = _state.value.activeChatId ?: return
        chats.clearMessages(id)
        update { it.copy(messages = emptyList()) }
    }

    fun setInput(v: String) = update { it.copy(input = v) }

    fun send() {
        val st = _state.value
        val chatId = st.activeChatId ?: return
        val text = st.input.trim()
        if (st.busy || text.isEmpty()) return
        // Снапшот настроек: фоновый запрос не должен хватать ползунки на лету.
        val model = st.model
        val temp = st.temperature
        val maxTokens = st.maxTokens
        val system = st.system.trim()
        val history = chats.state.messages[chatId].orEmpty().takeLast(20)
        chats.appendMessage(chatId, LocalMessage(role = "user", content = text))
        update { it.copy(input = "", busy = true, status = null, messages = chats.state.messages[chatId].orEmpty()) }
        scope.launch {
            try {
                val messages = mutableListOf<ChatMessageDto>()
                if (system.isNotBlank()) messages += ChatMessageDto("system", system)
                for (m in history) {
                    val role = if (m.role == "assistant") "assistant" else "user"
                    messages += ChatMessageDto(role, m.content)
                }
                messages += ChatMessageDto("user", text)
                val r = client.chat(messages, model, temp, maxTokens)
                val answer = r.choices.firstOrNull()?.message?.content.orEmpty().ifBlank { "(пустой ответ)" }
                val u = r.usage
                val meta = if (u.total_tokens > 0) "[in=${u.prompt_tokens} out=${u.completion_tokens} total=${u.total_tokens}]" else ""
                chats.appendMessage(chatId, LocalMessage(role = "assistant", content = answer, meta = meta))
            } catch (e: Task5Exception) {
                val msg = "Ошибка HTTP ${e.httpStatus} [${e.code}]: ${e.message.take(400)}"
                chats.appendMessage(chatId, LocalMessage(role = "assistant", content = msg))
                update { it.copy(status = msg.take(300)) }
            } catch (e: Exception) {
                val msg = "Ошибка: ${e.message?.take(400)}"
                chats.appendMessage(chatId, LocalMessage(role = "assistant", content = msg))
                update { it.copy(status = msg.take(300)) }
            } finally {
                update { it.copy(busy = false, messages = chats.state.messages[chatId].orEmpty()) }
            }
        }
    }

    // --- база знаний (/v1/docs на localhost) ---

    fun openAddDoc() = update { it.copy(showAddDoc = true, docTitle = "", docInput = "") }
    fun closeAddDoc() = update { it.copy(showAddDoc = false) }
    fun setDocType(v: String) = update { it.copy(docType = v) }
    fun setDocTitle(v: String) = update { it.copy(docTitle = v) }
    fun setDocInput(v: String) = update { it.copy(docInput = v) }

    fun commitAddDoc() {
        val st = _state.value
        val type = st.docType
        val title = st.docTitle.trim()
        val input = st.docInput.trim()
        if (input.isEmpty() || st.docsBusy) return
        update { it.copy(showAddDoc = false, docsBusy = true, status = null) }
        scope.launch {
            try {
                val r = client.docsAdd(type, title, input)
                update { it.copy(status = "Проиндексирован: ${r.title} (чанков: ${r.chunks})") }
                refreshDocs()
                refreshHealth()
            } catch (e: Task5Exception) {
                update { it.copy(docsBusy = false, status = "Индексация: HTTP ${e.httpStatus} [${e.code}]: ${e.message.take(300)}") }
            } catch (e: Exception) {
                update { it.copy(docsBusy = false, status = "Индексация: ${e.message?.take(300)}") }
            }
        }
    }

    fun deleteDoc(id: String) {
        scope.launch {
            try {
                client.docsDelete(id)
                update { it.copy(status = "Удалён: $id") }
                refreshDocs()
                refreshHealth()
            } catch (e: Task5Exception) {
                update { it.copy(status = "Удаление: HTTP ${e.httpStatus} [${e.code}]: ${e.message.take(300)}") }
            } catch (e: Exception) {
                update { it.copy(status = "Удаление: ${e.message?.take(300)}") }
            }
        }
    }

    // --- подключение (только localhost-сервер) ---

    fun openSettings() {
        val s = settings
        update {
            it.copy(
                showSettings = true, settingsCheck = null,
                settingsBaseUrl = s.baseUrl, settingsKey = s.apiKey, settingsModel = s.model,
                settingsTemp = s.temperature.toString(), settingsMaxTokens = s.maxTokens.toString(),
                settingsSystem = s.system,
            )
        }
    }

    fun closeSettings() = update { it.copy(showSettings = false, settingsCheck = null) }
    fun setSettingsBaseUrl(v: String) = update { it.copy(settingsBaseUrl = v) }
    fun setSettingsKey(v: String) = update { it.copy(settingsKey = v) }
    fun setSettingsModel(v: String) = update { it.copy(settingsModel = v) }
    fun setSettingsTemp(v: String) = update { it.copy(settingsTemp = v) }
    fun setSettingsMaxTokens(v: String) = update { it.copy(settingsMaxTokens = v) }
    fun setSettingsSystem(v: String) = update { it.copy(settingsSystem = v) }

    fun testSettings() {
        val st = _state.value
        if (st.settingsBusy) return
        val base = st.settingsBaseUrl.trim().trimEnd('/').ifBlank { "http://localhost:8080" }
        val key = st.settingsKey
        update { it.copy(settingsBusy = true, settingsCheck = null) }
        scope.launch {
            val probe = Task5Client(base, key)
            try {
                val h = probe.health()
                update { it.copy(settingsCheck = "OK: llm=${h.llm} embed=${h.embed} docs=${h.rag_docs}") }
            } catch (e: Task5Exception) {
                update { it.copy(settingsCheck = "ОШИБКА HTTP ${e.httpStatus} [${e.code}]: ${e.message.take(200)}") }
            } catch (e: Exception) {
                update { it.copy(settingsCheck = "ОШИБКА: ${e.message?.take(200)}") }
            } finally {
                runCatching { probe.close() }
                update { it.copy(settingsBusy = false) }
            }
        }
    }

    fun saveSettings() {
        val st = _state.value
        val base = st.settingsBaseUrl.trim().trimEnd('/').ifBlank { "http://localhost:8080" }
        val temp = st.settingsTemp.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: settings.temperature
        val maxTokens = st.settingsMaxTokens.toIntOrNull()?.coerceIn(1, 4096) ?: settings.maxTokens
        settings = DesktopSettings(
            baseUrl = base,
            apiKey = st.settingsKey,
            model = st.settingsModel.trim(),
            temperature = temp,
            maxTokens = maxTokens,
            system = st.settingsSystem,
        )
        settingsStore.save(settings)
        rebuildClient()
        update {
            it.copy(
                showSettings = false, settingsCheck = null,
                baseUrl = settings.baseUrl, model = settings.model,
                temperature = settings.temperature, maxTokens = settings.maxTokens,
                system = settings.system, models = emptyList(),
            )
        }
        refreshAll()
    }
}
