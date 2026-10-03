package desktop.ui

import core.rag.ChatStore
import core.rag.DocInfo
import core.rag.DocumentLoader
import core.rag.EmbedSettings
import core.rag.EmbedSettingsStore
import core.rag.OllamaEmbedder
import core.rag.RagChat
import core.rag.RagMessage
import core.rag.RagService
import core.rag.SourcesStore
import core.rag.newChatId
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val showAddMenu: Boolean = false,
    // Стратегия чанкинга, выбранная при добавлении: "fixed" | "structure" | "both".
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
    private val sources = SourcesStore()
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
        chats.appendMessage(chatId, RagMessage(role = "user", content = text))
        update { it.copy(input = "", busy = true, status = null, messages = chats.state.messages[chatId].orEmpty()) }
        scope.launch {
            try {
                if (chat.ragEnabled) {
                    val ans = svc.ask(text, onlySources = svc.activeSourcesOrNull())
                    val refs = ans.sources.mapIndexed { i, h ->
                        "[S${i + 1}] ${h.chunk.title}" +
                            (if (h.chunk.section.isNotBlank()) " / ${h.chunk.section}" else "")
                    }
                    chats.appendMessage(chatId, RagMessage(role = "assistant", content = ans.text, sources = refs))
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

    // --- база знаний ---

    fun openAddMenu() = update { it.copy(showAddMenu = true) }
    fun closeAddMenu() = update { it.copy(showAddMenu = false) }
    fun setAddStrategy(v: String) = update { it.copy(addStrategy = v) }
    fun openWiki() = update { it.copy(showAddMenu = false, showWiki = true, wikiInput = "") }
    fun closeWiki() = update { it.copy(showWiki = false) }
    fun setWikiInput(v: String) = update { it.copy(wikiInput = v) }

    // Источники, по которым прямо сейчас идёт фоновая индексация.
    // Повторный add тех же файлов, удаление и просмотр блокируются,
    // чтобы джоба не воскрешала удалённое и не гонялась с чтением.
    private val activeIndexJobs = mutableSetOf<String>()

    fun addRoots(paths: List<String>, strategy: String = _state.value.addStrategy) {
        if (paths.isEmpty()) return
        val clean = paths.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return
        val strat = strategy.takeIf { it == "fixed" || it == "structure" } ?: "both"
        val cur = sources.loadState()
        val merged = (cur.roots + clean).distinct()
        sources.save(merged, cur.inactive)
        update { it.copy(showAddMenu = false, addStrategy = strat) }
        log("Добавлено: ${clean.size}")
        scope.launch {
            // Быстрое раскрытие в документы (без эмбеддингов) — дальше уже фон.
            val loaded = withContext(Dispatchers.IO) {
                DocumentLoader.loadRoots(clean.map { File(it) })
            }
            if (loaded.skipped > 0) log("Пропущено файлов: ${loaded.skipped}")
            if (loaded.docs.isEmpty()) {
                update { it.copy(status = "Нет документов для индексации (пустые/неподдерживаемые файлы)") }
                return@launch
            }
            val fresh = loaded.docs.filter { it.source !in activeIndexJobs }
            if (fresh.isEmpty()) return@launch
            activeIndexJobs += fresh.map { it.source }
            // Документ сразу в списке: некликабельная строка + прогрессбар.
            svc.markIndexing(fresh, strat)
            refreshDocs()
            update { st -> st.copy(indexProgress = st.indexProgress + fresh.associate { it.source to null }) }
            try {
                try {
                    svc.checkEmbeddings()
                } catch (e: Exception) {
                    svc.markError(fresh.map { it.source }, e.message ?: "нет связи с моделью")
                    update { it.copy(status = "Индексация: ${e.message}") }
                    return@launch
                }
                svc.indexNewDocs(
                    fresh,
                    strat,
                    onDocProgress = { src, done, total ->
                        val p = if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)
                        update { st -> st.copy(indexProgress = st.indexProgress + (src to p)) }
                    },
                    onLog = { m -> log(m) },
                )
                log("Проиндексировано: ${fresh.size} док.")
            } catch (e: Exception) {
                update { it.copy(status = "Индексация: ${e.message}") }
            } finally {
                activeIndexJobs -= fresh.map { it.source }.toSet()
                update { st -> st.copy(indexProgress = st.indexProgress - fresh.map { it.source }.toSet()) }
                refreshDocs()
            }
        }
    }

    fun fetchWiki() {
        val input = _state.value.wikiInput.trim()
        if (input.isEmpty()) return
        val strat = _state.value.addStrategy
        update { it.copy(showWiki = false) }
        scope.launch {
            try {
                log("Загружаю Wiki: $input ...")
                val f = svc.fetchWikiAndSave(input)
                log("Сохранено: ${f.name} (${f.length() / 1024} КБ)")
                addRoots(listOf(f.absolutePath), strat)
            } catch (e: Exception) {
                update { it.copy(status = "Wiki: ${e.message}") }
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
        // чанки + запись документа — из индекса; файловый корень — из списка; wiki-файл из corpus — с диска
        svc.removeSources(listOf(source))
        for (r in sources.load()) {
            val f = File(r)
            if (f.isFile && (f.absolutePath == source || f.absolutePath.endsWith("/" + source) || f.name == source.substringAfterLast("/"))) {
                val cur = sources.loadState()
                sources.save(cur.roots - r, cur.inactive)
                try {
                    if (f.parentFile?.name == "corpus") f.delete()
                } catch (_: Exception) {
                }
            }
        }
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
