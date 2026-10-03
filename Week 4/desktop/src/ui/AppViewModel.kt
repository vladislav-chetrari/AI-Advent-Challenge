package desktop.ui

import core.rag.ChatStore
import core.rag.DocInfo
import core.rag.EmbedSettings
import core.rag.EmbedSettingsStore
import core.rag.OllamaEmbedder
import core.rag.RagChat
import core.rag.RagMessage
import core.rag.RagService
import core.rag.newChatId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class UiState(
    val chats: List<RagChat> = emptyList(),
    val activeChatId: String? = null,
    val messages: List<RagMessage> = emptyList(),
    val docs: List<DocInfo> = emptyList(),
    val input: String = "",
    val busy: Boolean = false,
    // Прогресс индексации по документам: source -> 0..1 (null = идёт, доля неизвестна).
    // Глобального спиннера внизу больше нет — прогресс виден в строке документа.
    val indexProgress: Map<String, Float?> = emptyMap(),
    val indexLog: List<String> = emptyList(),
    val status: String? = null,
    val showCreateChat: Boolean = false,
    val createName: String = "",
    // Стратегия чанкинга для новых статей: "fixed" | "structure" | "both".
    val addStrategy: String = "both",
    val showWiki: Boolean = false,
    val wikiInput: String = "",
    val viewingDoc: String? = null,
    val viewingText: String = "",
    // Подключение к эмбеддинг-модели
    val embedLabel: String = "",
    val showSettings: Boolean = false,
    val settingsModel: String = "",
    val settingsUrl: String = "",
    val settingsCheck: String? = null,
    val settingsBusy: Boolean = false,
    // Задание 3: второй этап RAG.
    // topK (до фильтрации) — сколько забрать из индекса;
    // rewrite — перепись query с помощью LLM;
    // filterEnabled вкл/выкл; temperature 0..1 — точность (порог);
    // postFilterK (null = без ограничения, иначе 1..topK) — повторная
    // обрезка чанков после фильтра по температуре.
    val topK: Int = 20,
    val rewriteEnabled: Boolean = false,
    val filterEnabled: Boolean = true,
    val temperature: Float = 0.35f,
    val postFilterK: Int? = 5,
    val showRagSettings: Boolean = false,
) {
    val indexSummary: String
        get() {
            if (docs.isEmpty()) return "база пуста"
            val active = docs.count { it.active }
            val chunks = docs.filter { it.active }.sumOf { it.chunks }
            val idx = docs.count { it.indexing }
            return "документов: ${docs.size} (активно: $active) · чанков: $chunks" +
                if (idx > 0) " · индексируется: $idx" else ""
        }
}

class AppViewModel(private val scope: CoroutineScope) {
    private val svc = RagService()
    private val chats = ChatStore()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        val st = chats.state
        _state.value = _state.value.copy(
            chats = st.chats,
            activeChatId = st.chats.firstOrNull()?.id,
            messages = st.messages[st.chats.firstOrNull()?.id].orEmpty(),
            embedLabel = svc.embedLabel,
        )
        refreshDocs()
    }

    private fun update(fn: (UiState) -> UiState) {
        _state.value = fn(_state.value)
    }

    private fun log(s: String) = update { it.copy(indexLog = (it.indexLog + s).takeLast(100)) }

    // --- чаты ---

    fun openCreateChat() = update { it.copy(showCreateChat = true, createName = "") }
    fun closeCreateChat() = update { it.copy(showCreateChat = false) }
    fun setCreateName(v: String) = update { it.copy(createName = v) }

    fun commitCreateChat() {
        val name = _state.value.createName.trim().ifBlank { "Чат" }
        val chat = RagChat(id = newChatId(), name = name)
        chats.upsertChat(chat)
        update { it.copy(showCreateChat = false, chats = chats.state.chats, activeChatId = chat.id, messages = emptyList(), viewingDoc = null) }
    }

    fun selectChat(id: String) {
        update { it.copy(activeChatId = id, messages = chats.state.messages[id].orEmpty(), viewingDoc = null, status = null) }
    }

    fun deleteChat(id: String) {
        chats.deleteChat(id)
        val rest = chats.state.chats
        update {
            it.copy(
                chats = rest,
                activeChatId = if (it.activeChatId == id) rest.firstOrNull()?.id else it.activeChatId,
                messages = chats.state.messages[if (it.activeChatId == id) rest.firstOrNull()?.id else it.activeChatId].orEmpty(),
                viewingDoc = null,
            )
        }
    }

    fun toggleRag(id: String) {
        val c = chats.state.chats.firstOrNull { it.id == id } ?: return
        if (!c.ragEnabled) {
            // Включение RAG — сначала проба связи с моделью.
            // Нет модели на порту — чистая ошибка, тумблер не двигается.
            update { it.copy(busy = true, status = null) }
            scope.launch {
                try {
                    val ok = svc.checkEmbeddings()
                    chats.upsertChat(c.copy(ragEnabled = true))
                    update { it.copy(chats = chats.state.chats, status = "RAG включён ($ok)") }
                } catch (e: Exception) {
                    update { it.copy(status = "RAG не включён: ${e.message}") }
                } finally {
                    update { it.copy(busy = false) }
                }
            }
        } else {
            chats.upsertChat(c.copy(ragEnabled = false))
            update { it.copy(chats = chats.state.chats) }
        }
    }

    fun clearChat(id: String) {
        chats.clearMessages(id)
        update { it.copy(messages = emptyList()) }
    }

    fun setInput(v: String) = update { it.copy(input = v) }

    fun send() {
        val st = _state.value
        val chatId = st.activeChatId ?: return
        val chat = chats.state.chats.firstOrNull { it.id == chatId } ?: return
        val text = st.input.trim()
        if (st.busy || text.isEmpty()) return
        val history = chats.state.messages[chatId].orEmpty()
        // Снапшот RAG-настроек: джоба в фоне не должна хватать ползунки на лету.
        val topK = st.topK
        val filterEnabled = st.filterEnabled
        val temperature = st.temperature
        val postFilterK = st.postFilterK
        val rewrite = st.rewriteEnabled
        chats.appendMessage(chatId, RagMessage(role = "user", content = text))
        update { it.copy(input = "", busy = true, status = null, messages = chats.state.messages[chatId].orEmpty()) }
        scope.launch {
            try {
                if (chat.ragEnabled) {
                    val ans = svc.ask(
                        text, onlySources = svc.activeSourcesOrNull(),
                        topK = topK, filterEnabled = filterEnabled,
                        temperature = temperature, postFilterK = postFilterK, rewrite = rewrite,
                    )
                    val refs = ans.sources.mapIndexed { i, h ->
                        "[S${i + 1}] ${h.chunk.title}" +
                            (if (h.chunk.section.isNotBlank()) " / ${h.chunk.section}" else "")
                    }
                    // Видно и факт rewrite, и его тексты (иначе эффект "ответ улучшился, а почему — непонятно").
                    val rewriteLines = ans.retrieval.rewritten.map { "↳ rewrite: $it" }
                    val info = (listOf(ans.retrieval.summary(temperature)) + rewriteLines).joinToString("\n")
                    chats.appendMessage(chatId, RagMessage(role = "assistant", content = ans.text, sources = refs, info = info))
                } else {
                    val reply = svc.askPlain(text, history)
                    chats.appendMessage(chatId, RagMessage(role = "assistant", content = reply))
                }
            } catch (e: Exception) {
                chats.appendMessage(chatId, RagMessage(role = "assistant", content = "Ошибка: ${e.message}"))
            } finally {
                update { it.copy(busy = false, messages = chats.state.messages[chatId].orEmpty()) }
            }
        }
    }

    // --- подключение к эмбеддинг-модели ---

    fun openSettings() {
        val cur = EmbedSettingsStore().effective()
        update { it.copy(showSettings = true, settingsModel = cur.model, settingsUrl = cur.baseUrl, settingsCheck = null) }
    }

    fun closeSettings() = update { it.copy(showSettings = false, settingsCheck = null) }
    fun setSettingsModel(v: String) = update { it.copy(settingsModel = v) }
    fun setSettingsUrl(v: String) = update { it.copy(settingsUrl = v) }

    fun saveSettings() {
        val st = _state.value
        val model = st.settingsModel.trim().ifBlank { EmbedSettings.DEFAULT_MODEL }
        val url = st.settingsUrl.trim().trimEnd('/').ifBlank { EmbedSettings.DEFAULT_URL }
        EmbedSettingsStore().save(EmbedSettings(model = model, baseUrl = url))
        svc.resetEmbedder()
        update { it.copy(showSettings = false, settingsCheck = null, embedLabel = svc.embedLabel) }
        log("Эмбеддинги: ${svc.embedLabel}. Нужна переиндексация после смены модели.")
    }

    // "Проверить" — проба по введённым (ещё не сохранённым) значениям.
    fun testSettings() {
        val st = _state.value
        if (st.settingsBusy) return
        val model = st.settingsModel.trim().ifBlank { EmbedSettings.DEFAULT_MODEL }
        val url = st.settingsUrl.trim().trimEnd('/').ifBlank { EmbedSettings.DEFAULT_URL }
        update { it.copy(settingsBusy = true, settingsCheck = null) }
        scope.launch {
            try {
                val probe = RagService(embedder = OllamaEmbedder(model = model, baseUrl = url))
                val ok = probe.checkEmbeddings()
                update { it.copy(settingsCheck = ok) }
            } catch (e: Exception) {
                update { it.copy(settingsCheck = "ОШИБКА: ${e.message}") }
            } finally {
                update { it.copy(settingsBusy = false) }
            }
        }
    }

    // --- Задание 3: настройки второго этапа RAG ---

    fun openRagSettings() = update { it.copy(showRagSettings = true) }
    fun closeRagSettings() = update { it.copy(showRagSettings = false) }
    fun setFilterEnabled(v: Boolean) = update { it.copy(filterEnabled = v) }
    fun setRewriteEnabled(v: Boolean) = update { it.copy(rewriteEnabled = v) }

    // Текстовые поля: невалидный ввод игнорируем, границы жмём в normalize().
    // top-K до фильтрации: 1..50.
    fun setTopK(v: String) {
        v.toIntOrNull()?.let { n ->
            update { st ->
                val t = n.coerceIn(1, 50)
                // top-K после не может превышать top-K до — поджимаем.
                val p = st.postFilterK?.coerceIn(1, t)
                st.copy(topK = t, postFilterK = p)
            }
        }
    }

    // Температура (точность ответа): 0..1.
    fun setTemperature(v: String) {
        v.replace(',', '.').toFloatOrNull()?.let { f -> update { it.copy(temperature = f.coerceIn(0f, 1f)) } }
    }

    fun setTemperatureSlider(v: Float) = update { it.copy(temperature = v.coerceIn(0f, 1f)) }

    // top-K после фильтрации — опционален: пустая строка = без ограничения,
    // иначе число от 1 до top-K (до фильтрации).
    fun setPostFilterK(v: String) {
        val t = v.trim()
        if (t.isEmpty()) {
            update { it.copy(postFilterK = null) }
            return
        }
        t.toIntOrNull()?.let { n ->
            update { st -> st.copy(postFilterK = n.coerceIn(1, st.topK)) }
        }
    }

    // Чекбокс "ограничить": вкл — ставит дефолт (min(topK, 5)), выкл — снимает лимит.
    fun setPostFilterKEnabled(enabled: Boolean) = update { st ->
        if (enabled) st.copy(postFilterK = (st.postFilterK ?: minOf(st.topK, 5)).coerceIn(1, st.topK))
        else st.copy(postFilterK = null)
    }

    // --- база знаний ---

    fun setAddStrategy(v: String) = update { it.copy(addStrategy = v) }
    fun openWiki() = update { it.copy(showWiki = true, wikiInput = "") }
    fun closeWiki() = update { it.copy(showWiki = false) }
    fun setWikiInput(v: String) = update { it.copy(wikiInput = v) }

    // Источники, по которым прямо сейчас идёт фоновая индексация.
    // Повторный add тех же файлов, удаление и просмотр блокируются,
    // чтобы джоба не воскрешала удалённое и не гонялась с чтением.
    private val activeIndexJobs = mutableSetOf<String>()

    fun fetchWiki() {
        val input = _state.value.wikiInput.trim()
        if (input.isEmpty()) return
        val strat = _state.value.addStrategy
        update { it.copy(showWiki = false) }
        scope.launch {
            val doc = try {
                log("Загружаю Wiki: $input ...")
                svc.fetchWikiDoc(input)
            } catch (e: Exception) {
                update { it.copy(status = "Wiki: ${e.message}") }
                return@launch
            }
            if (doc.source in activeIndexJobs) return@launch
            activeIndexJobs += doc.source
            // Документ сразу в списке: некликабельная строка + прогрессбар.
            svc.markIndexing(listOf(doc), strat)
            refreshDocs()
            update { st -> st.copy(indexProgress = st.indexProgress + (doc.source to null)) }
            try {
                try {
                    svc.checkEmbeddings()
                } catch (e: Exception) {
                    svc.markError(listOf(doc.source), e.message ?: "нет связи с моделью")
                    update { it.copy(status = "Индексация: ${e.message}") }
                    return@launch
                }
                svc.indexNewDocs(
                    listOf(doc),
                    strat,
                    onDocProgress = { src, done, total ->
                        val p = if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)
                        update { st -> st.copy(indexProgress = st.indexProgress + (src to p)) }
                    },
                    onLog = { m -> log(m) },
                )
                log("Проиндексировано: ${doc.title}")
            } catch (e: Exception) {
                update { it.copy(status = "Индексация: ${e.message}") }
            } finally {
                activeIndexJobs -= doc.source
                update { st -> st.copy(indexProgress = st.indexProgress - doc.source) }
                refreshDocs()
            }
        }
    }

    fun toggleDoc(source: String) {
        val d = _state.value.docs.firstOrNull { it.source == source } ?: return
        if (d.indexing || source in activeIndexJobs) return
        svc.setDocActive(source, !d.active)
        refreshDocs()
    }

    fun removeDoc(source: String) {
        if (source in activeIndexJobs) return
        // чанки + запись документа — из индекса, копия статьи — из корпуса
        svc.removeSources(listOf(source))
        if (_state.value.viewingDoc == source) update { it.copy(viewingDoc = null, viewingText = "") }
        log("Убран документ: ${source.substringAfterLast("/")}")
        refreshDocs()
    }

    fun viewDoc(source: String) {
        val d = _state.value.docs.firstOrNull { it.source == source }
        if (d?.indexing == true || source in activeIndexJobs) return
        val text = svc.docText(source) ?: "(не удалось прочитать файл)"
        update { it.copy(viewingDoc = source, viewingText = text) }
    }

    fun closeDoc() = update { it.copy(viewingDoc = null, viewingText = "") }

    fun refreshDocs() {
        scope.launch {
            try {
                val docs = svc.indexedDocs()
                update { it.copy(docs = docs) }
            } catch (_: Exception) {
            }
        }
    }
}
