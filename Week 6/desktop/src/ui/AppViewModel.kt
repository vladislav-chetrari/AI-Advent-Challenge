package desktop.ui

import core.agent.AgentConfig
import core.agent.AgentMachine
import core.agent.AgentRun
import core.agent.AgentRunner
import core.agent.PendingChange
import core.agent.PermissionEffect
import core.agent.PermissionGate
import core.agent.PermissionReply
import core.agent.PermissionStore
import core.agent.RunState
import core.agent.RunStore
import core.agent.ToolState
import core.agent.Workspace
import core.llm.ApiKeyProvider
import core.llm.ChatMsg
import core.llm.LlmClient
import core.llm.LlmSettings
import core.llm.LlmSettingsStore
import core.project.GitTools
import core.project.ProjectEntry
import core.rag.CHAT_KIND_AGENT
import core.rag.CHAT_KIND_RAG
import core.rag.ChatStore
import core.rag.DocInfo
import core.rag.EmbedSettings
import core.rag.EmbedSettingsStore
import core.rag.OllamaEmbedder
import core.rag.RagChat
import core.rag.RagMessage
import core.rag.RagService
import core.rag.TaskMemory
import core.rag.newChatId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    // Какой чат создаём: CHAT_KIND_RAG (вопросы по базе) или CHAT_KIND_AGENT
    // (только работа с файлами). Разделены в сайдбаре на две секции.
    val createKind: String = CHAT_KIND_RAG,
    // Стратегия чанкинга для новых статей: "fixed" | "structure" | "both".
    val addStrategy: String = "both",
    // Week 6 Task 1: проекты вместо wiki-статей.
    val projects: List<ProjectEntry> = emptyList(),
    val selectedProject: String? = null,
    val projectStatus: Map<String, String> = emptyMap(),
    val showAddProject: Boolean = false,
    val projectNameInput: String = "",
    val projectInput: String = "",
    val showWiki: Boolean = false,
    val wikiInput: String = "",
    val viewingDoc: String? = null,
    val viewingText: String = "",
    // Подключение к эмбеддинг-модели
    val embedLabel: String = "",
    // Подключение к LLM (DeepSeek-облако или локальная Ollama)
    val llmLabel: String = "",
    val showSettings: Boolean = false,
    val settingsModel: String = "",
    val settingsUrl: String = "",
    val settingsCheck: String? = null,
    val settingsBusy: Boolean = false,
    // LLM-секция единого диалога настроек (как Week 5 SettingsTab).
    val llmProvider: String = LlmSettings.PROVIDER_DEEPSEEK,
    val llmModel: String = "",
    val llmUrl: String = "",
    val llmKeyInput: String = "",
    val llmHasKey: Boolean = false,
    val llmCheck: String? = null,
    val llmBusy: Boolean = false,
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
    // Task 5: память задачи активного чата (цель + факты: ограничения/термины).
    val taskMemory: TaskMemory? = null,
    // ===== Task 4: агент, работающий с файлами проекта =====
    // Прогон целиком — UI рисует его как карточку в ленте агентного чата.
    val agentRun: AgentRun? = null,
    // Запрошенное изменение: карточка разрешения рисуется в ленте чата,
    // а не отдельным окном — решение видно в том же месте, где идёт прогон.
    val pendingChange: PendingChange? = null,
    // Что агент наговорил между шагами (не финальный ответ).
    val agentLog: List<String> = emptyList(),
    // Прогоны, найденные на диске — для ленты агентного чата и /runs.
    val agentRuns: List<AgentRun> = emptyList(),
) {
    // Две секции сайдбара. Держать один список было путано: у информационного
    // чата вопросы по базе, у агентного — только прогоны по файлам.
    val ragChats: List<RagChat> get() = chats.filter { !it.isAgent }
    val agentChats: List<RagChat> get() = chats.filter { it.isAgent }

    // История прогонов активного агентного чата (у каждого своя).
    val chatRuns: List<AgentRun> get() = agentRuns.filter { it.chatId == activeChatId }

    val indexSummary: String
        get() {
            val sel = selectedProject
            if (sel != null) {
                val files = docs.size
                val chunks = docs.sumOf { it.chunks }
                val st = projectStatus[sel]?.let { " · $it" } ?: ""
                return "проект: $sel · файлов: $files · чанков: $chunks$st"
            }
            if (docs.isEmpty()) return "нет проектов — нажми + в «Проектах»"
            val active = docs.count { it.active }
            val chunks = docs.filter { it.active }.sumOf { it.chunks }
            val idx = docs.count { it.indexing }
            return "проектов: ${projects.size} · документов: ${docs.size} (активно: $active) · чанков: $chunks" +
                if (idx > 0) " · индексируется: $idx" else ""
        }
}

class AppViewModel(private val scope: CoroutineScope) {
    private val svc = RagService()
    private val chats = ChatStore()
    // Хранилище прогонов объявлено ДО init: поля инициализируются по порядку,
    // и refreshAgentRuns() в init иначе увидит ещё null.
    private val agentStore = RunStore()
    private val agentPerms = PermissionStore()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        val st = chats.state
        val first = st.chats.firstOrNull()
        _state.value = _state.value.copy(
            chats = st.chats,
            activeChatId = first?.id,
            messages = st.messages[first?.id].orEmpty(),
            taskMemory = first?.id?.let { chats.getMemory(it) },
            agentRun = if (first?.isAgent == true) agentStore.latestForChat(first.id) else null,
            embedLabel = svc.embedLabel,
            llmLabel = svc.llmLabel,
        )
        refreshAgentRuns()
        refreshProjects()
    }

    private fun update(fn: (UiState) -> UiState) {
        _state.value = fn(_state.value)
    }

    private fun log(s: String) = update { it.copy(indexLog = (it.indexLog + s).takeLast(100)) }

    // --- чаты ---

    fun openCreateChat(kind: String = CHAT_KIND_RAG) = update { it.copy(showCreateChat = true, createName = "", createKind = kind) }
    fun closeCreateChat() = update { it.copy(showCreateChat = false) }
    fun setCreateName(v: String) = update { it.copy(createName = v) }

    fun commitCreateChat() {
        val kind = _state.value.createKind
        val name = _state.value.createName.trim().ifBlank { if (kind == CHAT_KIND_AGENT) "Агентный чат" else "Чат" }
        val chat = RagChat(id = newChatId(), name = name, kind = kind, ragEnabled = kind != CHAT_KIND_AGENT)
        chats.upsertChat(chat)
        update {
            it.copy(
                showCreateChat = false, chats = chats.state.chats, activeChatId = chat.id,
                messages = emptyList(), taskMemory = TaskMemory(), viewingDoc = null,
                agentRun = null, agentLog = emptyList(), input = "",
            )
        }
        refreshAgentRuns()
    }

    fun selectChat(id: String) {
        val chat = chats.state.chats.firstOrNull { it.id == id }
        // Уходим из чата, где агент ждёт разрешения: карточка исчезнет из ленты,
        // значит вопрос должен получить ответ — иначе прогон повиснет навсегда.
        leaveChat()
        update {
            it.copy(
                activeChatId = id,
                messages = chats.state.messages[id].orEmpty(),
                taskMemory = chats.getMemory(id),
                viewingDoc = null,
                status = null,
                input = "",
                agentRun = null,
                agentLog = emptyList(),
            )
        }
        if (chat?.isAgent == true) {
            // Открываем чат на его последнем прогоне — лента должна быть не пустой.
            agentStore.load()
            update { it.copy(agentRuns = agentStore.runs, agentRun = agentStore.latestForChat(id)) }
        }
    }

    fun deleteChat(id: String) {
        leaveChat()
        chats.deleteChat(id)
        // Прогоны удалённого чата остаются в agent_runs.json (их видно и откатывать
        // можно из CLI), но из ленты чата исчезают — привязки больше нет.
        agentStore.detachChat(id)
        val rest = chats.state.chats
        val next = if (_state.value.activeChatId == id) rest.firstOrNull()?.id else _state.value.activeChatId
        update {
            it.copy(
                chats = rest,
                activeChatId = next,
                messages = next?.let { cid -> chats.state.messages[cid].orEmpty() }.orEmpty(),
                taskMemory = next?.let { cid -> chats.getMemory(cid) },
                agentRuns = agentStore.runs,
                viewingDoc = null,
            )
        }
    }

    fun toggleRag(id: String) {
        val c = chats.state.chats.firstOrNull { it.id == id } ?: return
        // У агентного чата нет RAG: он работает с файлами проекта, а не с базой знаний.
        if (c.isAgent) return
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
        update { it.copy(messages = emptyList(), taskMemory = TaskMemory()) }
    }

    // Task 5: ручной сброс фактов без очистки переписки.
    fun clearTaskMemory() {
        val chatId = _state.value.activeChatId ?: return
        chats.clearMemory(chatId)
        update { it.copy(taskMemory = TaskMemory()) }
    }

    fun setInput(v: String) = update { it.copy(input = v) }

    fun send() {
        val st = _state.value
        val chatId = st.activeChatId ?: return
        val chat = chats.state.chats.firstOrNull { it.id == chatId } ?: return
        val text = st.input.trim()
        if (st.busy || text.isEmpty()) return
        // Агентный чат — только работа с файлами: вопросов по базе знаний в нём нет,
        // цель ставится полем в панели агента, а не перепиской.
        if (chat.isAgent) {
            update { it.copy(input = "", status = "В агентном чате нет переписки — поставь цель в панели агента.") }
            return
        }
        // Week 6 Task 1: локальные команды проекта (без LLM, через MCP-тулы).
        if (handleLocalCommand(text, chatId)) {
            update { it.copy(input = "") }
            return
        }
        val history = chats.state.messages[chatId].orEmpty()
        // Снапшот RAG-настроек: джоба в фоне не должна хватать ползунки на лету.
        val topK = st.topK
        val filterEnabled = st.filterEnabled
        val temperature = st.temperature
        val postFilterK = st.postFilterK
        val rewrite = st.rewriteEnabled
        // Task 5: память чата — факты вместо всего лога (дешевле и без дрейфа цели).
        val memory = chats.getMemory(chatId)
        chats.appendMessage(chatId, RagMessage(role = "user", content = text))
        update { it.copy(input = "", busy = true, status = null, messages = chats.state.messages[chatId].orEmpty()) }
        scope.launch {
            try {
                if (chat.ragEnabled) {
                    val ans = svc.ask(
                        text, onlySources = svc.activeSourcesOrNull(),
                        topK = topK, filterEnabled = filterEnabled,
                        temperature = temperature, postFilterK = postFilterK, rewrite = rewrite,
                        history = history, memory = memory,
                    )
                    // Task 4: короткая подпись для совместимости + refs для раскрывашки.
                    val refs = ans.refs.map { r ->
                        "[${r.index}] ${r.title}" +
                            (if (r.section.isNotBlank()) " / ${r.section}" else "")
                    }
                    // Видно и факт rewrite, и его тексты (иначе эффект "ответ улучшился, а почему — непонятно").
                    val rewriteLines = ans.retrieval.rewritten.map { "↳ rewrite: $it" }
                    val info = (listOf(ans.retrieval.summary(temperature)) + rewriteLines).joinToString("\n")
                    chats.appendMessage(chatId, RagMessage(role = "assistant", content = ans.text, sources = refs, info = info, refs = ans.refs))
                    // Факты обновляем после ответа — ход уже знает и вопрос, и ответ.
                    // Ошибка/пустой ключ внутри — тихо остаётся старая память.
                    val updated = svc.refreshMemory(memory, text, ans.text)
                    chats.setMemory(chatId, updated)
                    update { it.copy(taskMemory = updated) }
                } else {
                    val reply = svc.askPlain(text, history)
                    chats.appendMessage(chatId, RagMessage(role = "assistant", content = reply))
                    val updated = svc.refreshMemory(memory, text, reply.take(1200))
                    chats.setMemory(chatId, updated)
                    update { it.copy(taskMemory = updated) }
                }
            } catch (e: Exception) {
                chats.appendMessage(chatId, RagMessage(role = "assistant", content = "Ошибка: ${e.message}"))
            } finally {
                update { it.copy(busy = false, messages = chats.state.messages[chatId].orEmpty()) }
            }
        }
    }

    // Week 6 Task 1: команды чата. /help с вопросом = вопрос по проекту (RAG),
    // голый /help = справка. Остальное — прямой ответ через MCP-тулы без LLM.
    // Возвращает true, если команда обработана локально.
    private fun handleLocalCommand(text: String, chatId: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("/")) return false
        val cmd = t.substringBefore(" ").lowercase()
        val arg = t.substringAfter(" ", "").trim()
        when (cmd) {
            "/help" -> {
                if (arg.isNotBlank()) return false // вопрос по проекту — дальше в RAG
                val list = svc.listProjects()
                val sel = svc.selectedProject()?.name
                val body = buildString {
                    // Ответ рендерится Markdown: каждая команда — пункт списка,
                    // иначе одиночные переносы склеятся в один абзац.
                    append("Ассистент разработчика. Проект выбирается кликом в «Проектах».\n\n")
                    append("**Команды:**\n")
                    append("- `/help [вопрос]` — вопрос по выбранному проекту (RAG + ветка)\n")
                    append("- `/branch` — текущая git-ветка и коммит\n")
                    append("- `/files [фильтр]` — список файлов проекта\n")
                    append("- `/diff` — изменения рабочего дерева (diff --stat)\n")
                    append("- `/status` — проект, ветка, файлы/чанки\n")
                    append("- `/projects` — список проектов\n")
                    append("\n**Task 4 — агент, который сам работает с файлами:**\n")
                    append("- `/goal <цель>` — запустить агента (цель, а не файл: файлы выберет он)\n")
                    append("- `/runs` — список прогонов\n")
                    append("- `/run-diff [id]` — что изменил прогон\n")
                    append("- `/revert [id]` — откатить прогон по снапшотам\n")
                    append("- `/autopilot` — писать файлы без подтверждения (выкл = каждый раз спрашиваю)\n")
                    if (list.isEmpty()) append("\nПроектов пока нет — добавь через +.")
                    else {
                        append("\n**Проекты:**\n")
                        list.forEach { p ->
                            append("- " + (if (p.name == sel) "● " else "○ ") + p.name + "\n")
                        }
                    }
                }.trimEnd()
                chats.appendMessage(chatId, RagMessage(role = "user", content = t))
                chats.appendMessage(chatId, RagMessage(role = "assistant", content = body))
                update { it.copy(messages = chats.state.messages[chatId].orEmpty()) }
                return true
            }
            "/branch" -> {
                val p = svc.selectedProject()
                val body = if (p == null) "Проект не выбран." else "${p.name}: ${svc.projectStatusLine(p.name)}"
                chats.appendMessage(chatId, RagMessage(role = "user", content = t))
                chats.appendMessage(chatId, RagMessage(role = "assistant", content = body))
                update { it.copy(messages = chats.state.messages[chatId].orEmpty()) }
                return true
            }
            // Опциональная часть MCP: живой список файлов (git ls-files, fallback — обход).
            // Аргумент — префикс-фильтр: "/files docs" покажет только docs/**.
            "/files" -> {
                val p = svc.selectedProject()
                val body = if (p == null) {
                    "Проект не выбран."
                } else {
                    val dir = svc.projectDir(p.name)
                    if (dir == null) {
                        "Папка проекта пропала: ${p.localPath}"
                    } else {
                        val all = GitTools.listFiles(dir)
                        val f = arg.trim()
                        val filtered = if (f.isBlank()) all else all.filter { it.startsWith(f) }
                        if (filtered.isEmpty()) {
                            "Файлов не найдено" + if (f.isNotBlank()) " по фильтру «$f»." else "."
                        } else {
                            val shown = filtered.take(80)
                            buildString {
                                // Markdown-список: одиночные переносы рендер склеил бы в абзац.
                                append("**${p.name}**: файлов ${filtered.size}")
                                if (filtered.size != all.size) append(" (из ${all.size}, фильтр «$f»)")
                                append("\n")
                                shown.forEach { append("- `$it`\n") }
                                if (filtered.size > shown.size) append("\n…и ещё ${filtered.size - shown.size}")
                            }.trimEnd()
                        }
                    }
                }
                chats.appendMessage(chatId, RagMessage(role = "user", content = t))
                chats.appendMessage(chatId, RagMessage(role = "assistant", content = body))
                update { it.copy(messages = chats.state.messages[chatId].orEmpty()) }
                return true
            }
            // Опциональная часть MCP: diff рабочего дерева (status + diff --stat).
            "/diff" -> {
                val p = svc.selectedProject()
                val body = if (p == null) {
                    "Проект не выбран."
                } else {
                    val dir = svc.projectDir(p.name)
                    if (dir == null) "Папка проекта пропала: ${p.localPath}"
                    else "${p.name}:\n${GitTools.diffStat(dir)}"
                }
                chats.appendMessage(chatId, RagMessage(role = "user", content = t))
                chats.appendMessage(chatId, RagMessage(role = "assistant", content = body))
                update { it.copy(messages = chats.state.messages[chatId].orEmpty()) }
                return true
            }
            "/status" -> {
                val p = svc.selectedProject()
                val body = if (p == null) {
                    "Проект не выбран. Проектов: ${svc.listProjects().size}."
                } else {
                    val files = svc.projectSources(p.name).size
                    "${p.name}\nпуть: ${p.localPath}\n${svc.projectStatusLine(p.name)}\nфайлов в индексе: $files"
                }
                chats.appendMessage(chatId, RagMessage(role = "user", content = t))
                chats.appendMessage(chatId, RagMessage(role = "assistant", content = body))
                update { it.copy(messages = chats.state.messages[chatId].orEmpty()) }
                return true
            }
            "/projects" -> {
                val list = svc.listProjects()
                val sel = svc.selectedProject()?.name
                val body = if (list.isEmpty()) "Проектов пока нет — добавь через +." else
                    "**Проекты:**\n" + list.joinToString("\n") { p ->
                        "- " + (if (p.name == sel) "● " else "○ ") + "`${p.name}` — " + svc.projectStatusLine(p.name)
                    }
                chats.appendMessage(chatId, RagMessage(role = "user", content = t))
                chats.appendMessage(chatId, RagMessage(role = "assistant", content = body))
                update { it.copy(messages = chats.state.messages[chatId].orEmpty()) }
                return true
            }
            // ===== Task 4: команды агента, работающего с файлами =====
            // /goal ставит ЦЕЛЬ, а не указывает файл — файлы выбирает агент сам.
            "/goal" -> {
                if (arg.isBlank()) {
                    pushLocal(chatId, t, "Укажи цель: `/goal найди все места, где используется RagTools`")
                    return true
                }
                pushLocal(chatId, t, "Цель принята: $arg\nАгент запущен — ход виден справа.")
                startAgent(arg, chatId)
                return true
            }
            "/runs" -> {
                refreshAgentRuns()
                val runs = agentStore.runs
                val body = if (runs.isEmpty()) {
                    "Прогонов пока нет. Поставь цель: `/goal <цель>`."
                } else {
                    "**Прогоны** (последние ${runs.size}):\n" +
                        runs.takeLast(10).joinToString("\n") { r ->
                            "- `${r.id}` — ${r.label()} · шагов ${r.steps} · файлов ${r.changedFiles.size}\n  ${r.goal.take(70)}"
                        } +
                        "\n\n`/run-diff <id>` — что изменилось, `/revert <id>` — откатить."
                }
                pushLocal(chatId, t, body)
                return true
            }
            "/run-diff" -> {
                val id = arg.ifBlank { agentStore.runs.lastOrNull()?.id ?: "" }
                val run = if (id.isBlank()) null else agentStore.get(id)
                if (run == null) {
                    pushLocal(chatId, t, "Прогон `$id` не найден — посмотри `/runs`.")
                } else {
                    pushLocal(chatId, t, "Прогон `${run.id}`: ${run.label()} · файлов ${run.changedFiles.size}. Полный trace и diff — в агентном чате.")
                }
                return true
            }
            "/revert" -> {
                val id = arg.ifBlank { agentStore.runs.lastOrNull()?.id ?: "" }
                val run = if (id.isBlank()) null else agentStore.get(id)
                val dir = run?.let { svc.projectDir(it.project) }
                if (run == null || dir == null) {
                    pushLocal(chatId, t, "Откатить нечего: прогон `$id` не найден.")
                } else {
                    val n = agentStore.revert(run, Workspace(dir))
                    update { it.copy(agentRun = agentStore.get(run.id) ?: run) }
                    pushLocal(chatId, t, "Откачено файлов: $n (прогон `${run.id}`).")
                }
                return true
            }
            "/autopilot" -> {
                pushLocal(chatId, t, "Автоподтверждения больше нет: каждое изменение агента спрашивает разрешения в агентном чате.")
                return true
            }
            else -> return false
        }
    }

    // Локальный ответ команды: та же пара сообщений, что у остальных команд,
    // но без повторного копирования в update() в каждом ветке.
    private fun pushLocal(chatId: String, userText: String, answer: String) {
        chats.appendMessage(chatId, RagMessage(role = "user", content = userText))
        chats.appendMessage(chatId, RagMessage(role = "assistant", content = answer))
        update { it.copy(messages = chats.state.messages[chatId].orEmpty()) }
    }

    // ===== Task 4: запуск и управление прогоном агента =====

    // Ответ пользователя на запрос подтверждения. Пока он null, корутина
    // прогона висит на await — как Deferred в opencode.
    private var approval: kotlinx.coroutines.CompletableDeferred<PermissionReply>? = null
    private var agentJob: kotlinx.coroutines.Job? = null

    fun refreshAgentRuns() {
        agentStore.load()
        update { it.copy(agentRuns = agentStore.runs) }
    }

    // Ввод в агентном чате: текст из нижнего поля — это цель для агента.
    fun sendAgent() {
        val st = _state.value
        val chat = st.chats.firstOrNull { it.id == st.activeChatId } ?: return
        val text = st.input.trim()
        if (text.isEmpty()) return
        if (!chat.isAgent) return
        update { it.copy(input = "") }
        startAgent(text, chat.id)
    }

    fun startAgent(goal: String, chatId: String? = null, resumeOf: List<AgentRun> = emptyList()) {
        val p = svc.selectedProject()
        val dir = p?.let { svc.projectDir(it.name) }
        if (p == null || dir == null) {
            update { it.copy(status = "Агенту нужен проект: выбери его в «Проектах».") }
            return
        }
        if (_state.value.busy) {
            update { it.copy(status = "Занято: дождись конца текущего хода или останови прогон (⏹).") }
            return
        }
        val project = p.name
        val ws = Workspace(dir)
        // Автоподтверждения нет: каждое изменение спрашивает разрешения
        // карточкой в ленте чата (см. pendingChange).
        val gate = PermissionGate(agentPerms) { change -> requestApproval(change) }
        val seed = chatId?.let { id ->
            // В агентном чате переписка — это лог прошлых прогонов; скармливать его
            // модели за контекст незачем. Историю задаёт сам агентный чат через RunStore.
            if (chats.state.chats.firstOrNull { it.id == id }?.isAgent == true) emptyList()
            else chats.state.messages[id].orEmpty().takeLast(6).map { ChatMsg(it.role, it.content) }
        }.orEmpty()

        update {
            it.copy(
                agentRun = null, agentLog = emptyList(), pendingChange = null,
                busy = true, status = null,
            )
        }
        approval = null
        agentJob = scope.launch {
            val runner = AgentRunner(
                rag = svc,
                workspace = ws,
                runStore = agentStore,
                gate = gate,
                config = AgentConfig(),
                onUpdate = { r -> update { it.copy(agentRun = r, agentRuns = agentStore.runs) } },
                onLog = { line -> update { it.copy(agentLog = (it.agentLog + line).takeLast(200)) } },
            )
            runner.run(goal, project, seedHistory = seed, chatId = chatId ?: "", resumeOf = resumeOf)
            approval?.cancel()
            approval = null
            update { it.copy(busy = false, agentRuns = agentStore.runs, pendingChange = null) }
        }
    }

    // Продолжить задачу с места остановки. Новый прогон получает бриф по
    // предыдущим прогонам ЭТОГО чата (включая выбранный): цель та же, бюджет
    // свой, а модель видит, что уже сделано и где остановились.
    fun resumeRun(id: String) {
        val run = agentStore.get(id) ?: return
        val chatId = _state.value.activeChatId ?: return
        if (!run.resumable) return
        val previous = _state.value.chatRuns.filter { it.startedAt <= run.startedAt }.ifEmpty { listOf(run) }
        startAgent(run.goal, chatId, resumeOf = previous)
    }

    // Запрос разрешения показывается карточкой В ЛЕНТЕ ЧАТА: решение принимается там
    // же, где идёт прогон, и остаётся в истории вместе с ним.
    private suspend fun requestApproval(change: PendingChange): PermissionReply {
        val d = kotlinx.coroutines.CompletableDeferred<PermissionReply>()
        approval = d
        update { it.copy(pendingChange = change) }
        val reply = try {
            d.await()
        } finally {
            update { it.copy(pendingChange = null) }
            approval = null
        }
        return reply
    }

    fun approveOnce() = approval?.complete(PermissionReply.Once)
    fun approveAlways() = approval?.complete(PermissionReply.Always)

    // Отказ: не просто закрываем карточку — это решение «не делать».
    fun rejectAgent() = approval?.complete(PermissionReply.Reject)

    // Покидаем чат, в котором агент ждёт разрешения: карточка вопроса уходит из
    // ленты вместе с чатом, и ждать ответа уже некому.
    fun leaveChat() {
        if (_state.value.pendingChange != null) rejectAgent()
    }

    fun cancelAgent() {
        agentJob?.cancel()
        agentJob = null
        approval?.complete(PermissionReply.Reject)
        approval = null
        update { it.copy(busy = false, pendingChange = null, status = "Прогон остановлен") }
    }

    fun revertRun(id: String) {
        val run = agentStore.get(id) ?: return
        val dir = svc.projectDir(run.project) ?: return
        val n = agentStore.revert(run, Workspace(dir))
        update { it.copy(agentRuns = agentStore.runs) }
        log("Откачено файлов: $n")
    }

    // --- единый диалог настроек: LLM + эмбеддинги (как Week 5 SettingsTab) ---

    fun openSettings() {
        val cur = EmbedSettingsStore().effective()
        val llm = LlmSettingsStore().effective()
        update {
            it.copy(
                showSettings = true,
                settingsModel = cur.model, settingsUrl = cur.baseUrl, settingsCheck = null,
                llmProvider = llm.provider, llmModel = llm.model, llmUrl = llm.baseUrl,
                llmKeyInput = "", llmHasKey = ApiKeyProvider.hasKey(), llmCheck = null,
            )
        }
    }

    fun closeSettings() = update { it.copy(showSettings = false, settingsCheck = null, llmCheck = null) }
    fun setSettingsModel(v: String) = update { it.copy(settingsModel = v) }
    fun setSettingsUrl(v: String) = update { it.copy(settingsUrl = v) }
    fun setLlmProvider(v: String) = update { st ->
        val provider = if (v == LlmSettings.PROVIDER_OLLAMA) LlmSettings.PROVIDER_OLLAMA else LlmSettings.PROVIDER_DEEPSEEK
        // При смене провайдера подставляем дефолтную модель/URL, чтобы не упираться в чужой endpoint.
        if (provider == st.llmProvider) st.copy(llmProvider = provider)
        else if (provider == LlmSettings.PROVIDER_OLLAMA) st.copy(
            llmProvider = provider,
            llmModel = LlmSettings.DEFAULT_OLLAMA_MODEL,
            llmUrl = LlmSettings.DEFAULT_OLLAMA_URL,
            llmCheck = null,
        )
        else st.copy(
            llmProvider = provider,
            llmModel = LlmSettings.DEFAULT_DEEPSEEK_MODEL,
            llmUrl = LlmSettings.DEFAULT_DEEPSEEK_URL,
            llmCheck = null,
        )
    }
    fun setLlmModel(v: String) = update { it.copy(llmModel = v) }
    fun setLlmUrl(v: String) = update { it.copy(llmUrl = v) }
    fun setLlmKeyInput(v: String) = update { it.copy(llmKeyInput = v) }

    fun saveSettings() {
        val st = _state.value
        val model = st.settingsModel.trim().ifBlank { EmbedSettings.DEFAULT_MODEL }
        val url = st.settingsUrl.trim().trimEnd('/').ifBlank { EmbedSettings.DEFAULT_URL }
        EmbedSettingsStore().save(EmbedSettings(model = model, baseUrl = url))
        svc.resetEmbedder()
        val provider = if (st.llmProvider == LlmSettings.PROVIDER_OLLAMA) LlmSettings.PROVIDER_OLLAMA else LlmSettings.PROVIDER_DEEPSEEK
        val llmModel = st.llmModel.trim().ifBlank {
            if (provider == LlmSettings.PROVIDER_OLLAMA) LlmSettings.DEFAULT_OLLAMA_MODEL else LlmSettings.DEFAULT_DEEPSEEK_MODEL
        }
        val llmUrl = st.llmUrl.trim().trimEnd('/').ifBlank {
            if (provider == LlmSettings.PROVIDER_OLLAMA) LlmSettings.DEFAULT_OLLAMA_URL else LlmSettings.DEFAULT_DEEPSEEK_URL
        }
        LlmSettingsStore().save(LlmSettings(provider = provider, model = llmModel, baseUrl = llmUrl))
        svc.resetLlm()
        val key = st.llmKeyInput.trim()
        if (key.isNotBlank()) {
            ApiKeyProvider.saveKey(key)
        }
        update { it.copy(showSettings = false, settingsCheck = null, llmCheck = null, llmKeyInput = "", embedLabel = svc.embedLabel, llmLabel = svc.llmLabel, llmHasKey = ApiKeyProvider.hasKey()) }
        log("LLM: ${svc.llmLabel}. Эмбеддинги: ${svc.embedLabel}. После смены эмбеддингов нужна переиндексация.")
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

    // "Проверить" LLM — проба по введённым значениям без сохранения.
    // Облако: ключ из поля ввода, иначе сохранённый; локалка: ключ не нужен.
    fun testLlm() {
        val st = _state.value
        if (st.llmBusy) return
        val provider = if (st.llmProvider == LlmSettings.PROVIDER_OLLAMA) LlmSettings.PROVIDER_OLLAMA else LlmSettings.PROVIDER_DEEPSEEK
        val model = st.llmModel.trim().ifBlank {
            if (provider == LlmSettings.PROVIDER_OLLAMA) LlmSettings.DEFAULT_OLLAMA_MODEL else LlmSettings.DEFAULT_DEEPSEEK_MODEL
        }
        val url = st.llmUrl.trim().trimEnd('/').ifBlank {
            if (provider == LlmSettings.PROVIDER_OLLAMA) LlmSettings.DEFAULT_OLLAMA_URL else LlmSettings.DEFAULT_DEEPSEEK_URL
        }
        val keyInput = st.llmKeyInput.trim()
        val key = if (keyInput.isNotBlank()) keyInput else try { ApiKeyProvider.resolve() } catch (_: Exception) { null }
        update { it.copy(llmBusy = true, llmCheck = null) }
        scope.launch {
            try {
                val probe = RagService(
                    llm = LlmClient(model = model, baseUrl = url, provider = provider),
                )
                // Облачный ключ для пробы подсовываем через override, чтобы не сохранять раньше времени.
                val prev = ApiKeyProvider.override
                try {
                    if (provider == LlmSettings.PROVIDER_DEEPSEEK && keyInput.isNotBlank()) ApiKeyProvider.override = keyInput
                    val ok = probe.checkLlm()
                    update { it.copy(llmCheck = ok) }
                } finally {
                    ApiKeyProvider.override = prev
                }
                if (provider == LlmSettings.PROVIDER_DEEPSEEK && key.isNullOrBlank()) {
                    update { it.copy(llmCheck = (it.llmCheck ?: "") + " · без ключа облако не ответит") }
                }
            } catch (e: Exception) {
                update { it.copy(llmCheck = "ОШИБКА: ${e.message}") }
            } finally {
                update { it.copy(llmBusy = false) }
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

    // Снап к шагу ползунка 0.05: float не хранит 0.10 точно (0.10000001),
    // поэтому округляем через целые шаги — значение всегда равно выбранному.
    fun setTemperatureSlider(v: Float) = update {
        val snapped = ((v * 20).roundToInt().coerceIn(0, 20)) / 20f
        it.copy(temperature = snapped.coerceIn(0f, 1f))
    }

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

    // Чанкинг — мультивыбор: «фикс» и «структура» тогглятся независимо,
    // хотя бы один должен остаться включённым.
    fun toggleChunkPart(part: String) = update { st ->
        val fixed = st.addStrategy != "structure"
        val structure = st.addStrategy != "fixed"
        val nf = if (part == "fixed") !fixed else fixed
        val ns = if (part == "structure") !structure else structure
        if (!nf && !ns) st
        else st.copy(
            addStrategy = when {
                nf && ns -> "both"
                nf -> "fixed"
                else -> "structure"
            },
        )
    }
    fun openWiki() = update { it.copy(showWiki = true, wikiInput = "") }
    fun closeWiki() = update { it.copy(showWiki = false) }
    fun setWikiInput(v: String) = update { it.copy(wikiInput = v) }

    // Источники, по которым прямо сейчас идёт фоновая индексация.
    // Повторный add тех же файлов, удаление и просмотр блокируются,
    // чтобы джоба не воскрешала удалённое и не гонялась с чтением.
    private val activeIndexJobs = mutableSetOf<String>()

    // ===== Week 6 Task 1: проекты =====

    fun openAddProject() = update { it.copy(showAddProject = true, projectNameInput = "", projectInput = "") }
    fun closeAddProject() = update { it.copy(showAddProject = false) }
    fun setProjectNameInput(v: String) = update { it.copy(projectNameInput = v) }

    // Вставка URL/пути подставляет имя проекта из хвоста (после последнего
    // слеша, без .git) — но только пока поле имени пустое: введённое
    // вручную имя пользователя не затираем.
    fun setProjectInput(v: String) = update { st ->
        val auto = deriveProjectName(v)
        st.copy(
            projectInput = v,
            projectNameInput = if (st.projectNameInput.isBlank() && auto.isNotBlank()) auto else st.projectNameInput,
        )
    }

    private fun deriveProjectName(input: String): String {
        val t = input.trim().trimEnd('/').substringAfterLast('/').substringAfterLast('\\').trim()
        if (t.isEmpty()) return ""
        return if (t.endsWith(".git", ignoreCase = true)) t.dropLast(4).trim() else t
    }

    fun selectProject(name: String) {
        svc.selectProject(name)
        update { it.copy(selectedProject = name, viewingDoc = null, status = null) }
        refreshProjects()
    }

    // add: git-URL клонируется в repos/<name>, путь — на месте; дальше scan+index.
    fun addProject() {
        val name = _state.value.projectNameInput.trim()
        val input = _state.value.projectInput.trim()
        if (name.isEmpty() || input.isEmpty()) return
        val strat = _state.value.addStrategy
        if (name in activeIndexJobs) return
        activeIndexJobs += name
        update { it.copy(showAddProject = false, status = null) }
        log("Добавляю проект «$name» ...")
        scope.launch {
            try {
                try {
                    svc.checkEmbeddings()
                } catch (e: Exception) {
                    update { it.copy(status = "Нет связи с эмбеддингами: ${e.message}") }
                    return@launch
                }
                val n = svc.addProject(
                    name, input, strat,
                    onDocProgress = { src, done, total ->
                        val p = if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)
                        update { st -> st.copy(indexProgress = st.indexProgress + (src to p)) }
                    },
                    onLog = { m -> log(m) },
                )
                log("Проект «$name»: файлов $n")
            } catch (e: Exception) {
                update { it.copy(status = "Проект: ${e.message}") }
            } finally {
                activeIndexJobs -= name
                update { st -> st.copy(indexProgress = emptyMap()) }
                refreshProjects()
            }
        }
    }

    fun updateProject(name: String) {
        if (name in activeIndexJobs) return
        activeIndexJobs += name
        val strat = _state.value.addStrategy
        log("Обновляю проект «$name» ...")
        scope.launch {
            try {
                val n = svc.updateProject(
                    name, strat,
                    onDocProgress = { src, done, total ->
                        val p = if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)
                        update { st -> st.copy(indexProgress = st.indexProgress + (src to p)) }
                    },
                    onLog = { m -> log(m) },
                )
                log("Проект «$name» обновлён: файлов $n")
            } catch (e: Exception) {
                update { it.copy(status = "Обновление: ${e.message}") }
            } finally {
                activeIndexJobs -= name
                update { st -> st.copy(indexProgress = emptyMap()) }
                refreshProjects()
            }
        }
    }

    fun removeProject(name: String) {
        if (name in activeIndexJobs) return
        svc.removeProject(name)
        if (_state.value.selectedProject == name) update { it.copy(selectedProject = null, viewingDoc = null) }
        log("Проект «$name» убран")
        refreshProjects()
    }

    fun refreshProjects() {
        scope.launch {
            try {
                val list = svc.listProjects()
                val sel = svc.selectedProject()?.name ?: list.firstOrNull()?.name?.also { svc.selectProject(it) }
                val status = list.associate { p ->
                    p.name to try {
                        svc.projectStatusLine(p.name)
                    } catch (_: Exception) {
                        "—"
                    }
                }
                update { it.copy(projects = list, selectedProject = sel, projectStatus = status) }
            } catch (_: Exception) {
            }
            refreshDocs()
        }
    }

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
                val all = svc.indexedDocs()
                // Показываем файлы выбранного проекта; без выбора — всё (back-compat).
                val sel = _state.value.selectedProject
                    ?: svc.selectedProject()?.name
                val docs = if (sel != null) all.filter { it.source.startsWith("proj:$sel:") } else all
                update { it.copy(docs = docs) }
            } catch (_: Exception) {
            }
        }
    }
}
