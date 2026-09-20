package desktop.ui

import core.ChatService
import core.domain.Chat
import core.domain.ChatMessage
import core.domain.InvariantDoc
import core.domain.MemoryDoc
import core.domain.Project
import core.domain.Scope
import core.domain.Task
import core.domain.TaskStage
import core.domain.TaskState
import core.domain.TaskStatus
import core.domain.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Selection {
    data class ChatSel(val chatId: String) : Selection
    data class DocSel(val docId: String) : Selection
    // Task 4: выбор документа инвариантов
    data class InvariantSel(val docId: String) : Selection
}

enum class CreateKind(val title: String) {
    GENERAL_CHAT("Общий чат"),
    PROJECT("Проект"),
    PROJECT_CHAT("Чат в проекте"),
    TASK("Задача"),
    TASK_CHAT("Чат в задаче"),
    // Task 4: новый тип сущности через меню добавления
    INVARIANTS("Инварианты"),
}

data class SaveDialog(
    val rawText: String,
    val candidates: List<String> = emptyList(),
    val busy: Boolean = false,
    val editedFact: String = "",
    val targetScope: Scope = Scope.GENERAL,
    val targetParentId: String? = null,
    val targetDocId: String? = null,
    val newDocTitle: String = "",
    val useNewDoc: Boolean = false,
)

// Диалог профиля: выбор (null = Аноним) + создание/правка характеристик.
data class ProfileDialog(
    val selectedId: String? = null,
    val name: String = "",
    val style: String = "",
    val format: String = "",
    val constraints: String = "",
)

data class UiState(
    val projects: List<Project> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val chats: List<Chat> = emptyList(),
    val docs: List<MemoryDoc> = emptyList(),
    val docContents: Map<String, String> = emptyMap(),
    // Task 4: инварианты + их контент (автосейв из редактора)
    val invariants: List<InvariantDoc> = emptyList(),
    val invariantContents: Map<String, String> = emptyMap(),
    val messages: Map<String, List<ChatMessage>> = emptyMap(),
    val selection: Selection? = null,
    val input: String = "",
    val busy: Boolean = false,
    val status: String? = null,
    val systemPrompt: String = "",
    val systemExpanded: Boolean = false,
    // Task 4: раскрыта ли панель инвариантов в шапке чата
    val invariantsExpanded: Boolean = false,
    val promptTokens: Int = 0,
    val showCreate: Boolean = false,
    val createKind: CreateKind = CreateKind.GENERAL_CHAT,
    val createName: String = "",
    val createParentId: String? = null,
    val saveDialog: SaveDialog? = null,
    val profiles: List<UserProfile> = emptyList(),
    val activeProfileId: String? = null,
    val profileDialog: ProfileDialog? = null,
    // развернутость узлов дерева (по умолчанию всё свернуто)
    val expandedProjects: Set<String> = emptySet(),
    val expandedTasks: Set<String> = emptySet(),
    // Task 3: состояние задач
    val taskStates: Map<String, TaskState> = emptyMap(),
)

class AppViewModel(
    val service: ChatService = ChatService(),
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh(null) }

    fun refresh(sel: Selection? = _state.value.selection) {
        val s = service.state()
        val contents = s.memoryDocs.associate { it.id to service.docContent(it) }
        val invContents = s.invariantDocs.associate { it.id to service.invariantContent(it) }
        // system prompt для выбранного чата (уже с активным профилем, null = Аноним)
        var sys = ""
        val selChat = (sel as? Selection.ChatSel)?.chatId?.let { id -> s.chats.firstOrNull { it.id == id } }
        if (selChat != null) sys = service.buildSystemPrompt(selChat)
        _state.update {
            it.copy(
                projects = s.projects, tasks = s.tasks, chats = s.chats,
                docs = s.memoryDocs, docContents = contents,
                invariants = s.invariantDocs, invariantContents = invContents,
                messages = s.messages,
                profiles = s.profiles, activeProfileId = s.activeProfileId,
                taskStates = s.taskStates,
                selection = sel ?: selChat?.let { c -> Selection.ChatSel(c.id) },
                systemPrompt = sys.ifBlank { it.systemPrompt },
            )
        }
    }

    fun select(sel: Selection) {
        // авто-раскрытие предков, чтобы выбранный узел не остался в свернутой ветке
        // (general-узлы лежат в корне, раскрывать для них нечего)
        _state.update { cur ->
            var projects = cur.expandedProjects
            var tasks = cur.expandedTasks
            when (sel) {
                is Selection.ChatSel -> {
                    val chat = cur.chats.firstOrNull { it.id == sel.chatId }
                    when (chat?.scope) {
                        Scope.PROJECT -> chat.parentId?.let { projects += it }
                        Scope.TASK -> {
                            chat.parentId?.let { tasks += it }
                            cur.tasks.firstOrNull { t -> t.id == chat.parentId }?.let { projects += it.projectId }
                        }
                        else -> Unit
                    }
                }
                is Selection.DocSel -> {
                    val doc = cur.docs.firstOrNull { it.id == sel.docId }
                    when (doc?.scope) {
                        Scope.PROJECT -> doc.parentId?.let { projects += it }
                        Scope.TASK -> {
                            doc.parentId?.let { tasks += it }
                            cur.tasks.firstOrNull { t -> t.id == doc.parentId }?.let { projects += it.projectId }
                        }
                        else -> Unit
                    }
                }
                is Selection.InvariantSel -> {
                    val doc = cur.invariants.firstOrNull { it.id == sel.docId }
                    when (doc?.scope) {
                        Scope.PROJECT -> doc.parentId?.let { projects += it }
                        Scope.TASK -> {
                            doc.parentId?.let { tasks += it }
                            cur.tasks.firstOrNull { t -> t.id == doc.parentId }?.let { projects += it.projectId }
                        }
                        else -> Unit
                    }
                }
            }
            cur.copy(expandedProjects = projects, expandedTasks = tasks)
        }
        refresh(sel)
    }

    fun onInput(v: String) = _state.update { it.copy(input = v) }

    fun toggleSystem() = _state.update { it.copy(systemExpanded = !it.systemExpanded) }

    fun toggleInvariants() = _state.update { it.copy(invariantsExpanded = !it.invariantsExpanded) }

    fun toggleProject(id: String) = _state.update {
        it.copy(expandedProjects = if (id in it.expandedProjects) it.expandedProjects - id else it.expandedProjects + id)
    }

    fun toggleTask(id: String) = _state.update {
        it.copy(expandedTasks = if (id in it.expandedTasks) it.expandedTasks - id else it.expandedTasks + id)
    }

    fun send() {
        val st = _state.value
        val sel = st.selection as? Selection.ChatSel ?: return
        val q = st.input.trim()
        if (q.isEmpty() || st.busy) return
        val chat = service.state().chats.firstOrNull { it.id == sel.chatId } ?: return
        // Task 5: DONE-задача — отправка запрещена, только чтение истории
        if (chat.scope == Scope.TASK && service.getTaskState(chat.parentId ?: "")?.stage == TaskStage.DONE) {
            _state.update { it.copy(status = "Задача завершена (DONE) — новые сообщения запрещены, создай новую задачу") }
            return
        }
        // Optimistic echo: user-сообщение в стор синхронно + refresh,
        // чтобы оно и индикатор "печатает…" появились мгновенно, до ответа LLM
        service.appendUserMessage(chat, q)
        _state.update { it.copy(input = "", busy = true, status = null) }
        refresh(sel)
        scope.launch {
            when (val r = service.completeAsk(chat)) {
                is core.AskResult.Success -> {
                    refresh(sel)
                    _state.update {
                        it.copy(busy = false, promptTokens = r.promptTokens, systemPrompt = service.buildSystemPrompt(chat))
                    }
                }
                is core.AskResult.Failure -> {
                    refresh(sel)
                    _state.update { it.copy(busy = false, status = r.message) }
                }
            }
        }
    }

    fun clearChat() {
        val sel = _state.value.selection as? Selection.ChatSel ?: return
        service.clearChat(sel.chatId)
        refresh(sel)
    }

    // --- create dialog ---

    fun openCreate() = _state.update { it.copy(showCreate = true, createName = "", createParentId = null) }
    fun closeCreate() = _state.update { it.copy(showCreate = false) }
    fun setCreateKind(k: CreateKind) = _state.update { it.copy(createKind = k, createParentId = null) }
    fun setCreateName(v: String) = _state.update { it.copy(createName = v.take(30)) }
    fun setCreateParent(id: String?) = _state.update { it.copy(createParentId = id) }

    fun commitCreate() {
        val st = _state.value
        val name = st.createName.trim().ifBlank { "untitled" }
        var sel: Selection? = st.selection
        // какие узлы раскрыть после создания, чтобы новый чат/проект был виден
        var expandProjects: Set<String> = emptySet()
        var expandTasks: Set<String> = emptySet()
        when (st.createKind) {
            CreateKind.GENERAL_CHAT -> {
                val c = service.createChat(name, Scope.GENERAL, null)
                sel = Selection.ChatSel(c.id)
            }
            CreateKind.PROJECT -> {
                val p = service.createProject(name)
                // только папка: без стартовых доков и чата
                expandProjects = setOf(p.id)
            }
            CreateKind.PROJECT_CHAT -> {
                val pid = st.createParentId ?: return
                val c = service.createChat(name, Scope.PROJECT, pid)
                sel = Selection.ChatSel(c.id)
                expandProjects = setOf(pid)
            }
            CreateKind.TASK -> {
                val pid = st.createParentId ?: return
                val t = service.createTask(pid, name)
                // только папка: без стартового дока и чата
                expandProjects = setOf(pid)
                expandTasks = setOf(t.id)
            }
            CreateKind.TASK_CHAT -> {
                val tid = st.createParentId ?: return
                // DONE — неактивная задача: новые привязки запрещены
                if (service.getTaskState(tid)?.stage == TaskStage.DONE) {
                    _state.update { it.copy(status = "Задача завершена (DONE) — новые чаты запрещены") }
                    return
                }
                val c = service.createChat(name, Scope.TASK, tid)
                sel = Selection.ChatSel(c.id)
                expandTasks = setOf(tid)
            }
            CreateKind.INVARIANTS -> {
                // Родитель — проект или задача: scope выводим по типу родителя
                val pid = st.createParentId ?: return
                val isProject = service.state().projects.any { it.id == pid }
                if (isProject) {
                    val d = service.createInvariantDoc(name.ifBlank { "инварианты" }, Scope.PROJECT, pid)
                    sel = Selection.InvariantSel(d.id)
                    expandProjects = setOf(pid)
                } else {
                    // DONE — неактивная задача: новые привязки запрещены
                    if (service.getTaskState(pid)?.stage == TaskStage.DONE) {
                        _state.update { it.copy(status = "Задача завершена (DONE) — новые инварианты запрещены") }
                        return
                    }
                    val d = service.createInvariantDoc(name.ifBlank { "инварианты" }, Scope.TASK, pid)
                    sel = Selection.InvariantSel(d.id)
                    expandTasks = setOf(pid)
                    service.state().tasks.firstOrNull { it.id == pid }?.let { expandProjects = setOf(it.projectId) }
                }
            }
        }
        _state.update {
            it.copy(
                showCreate = false,
                expandedProjects = it.expandedProjects + expandProjects,
                expandedTasks = it.expandedTasks + expandTasks,
            )
        }
        refresh(sel)
    }

    fun toggleDoc(docId: String) {
        service.toggleDocActive(docId)
        refresh()
    }

    fun deleteDoc(docId: String) {
        service.deleteDoc(docId)
        val sel = _state.value.selection
        refresh(if (sel is Selection.DocSel && sel.docId == docId) null else sel)
    }

    // --- Task 4: инварианты — toggle/delete + автосейв при изменениях ---

    fun toggleInvariant(docId: String) {
        service.toggleInvariantActive(docId)
        refresh()
    }

    fun deleteInvariant(docId: String) {
        service.deleteInvariant(docId)
        val sel = _state.value.selection
        refresh(if (sel is Selection.InvariantSel && sel.docId == docId) null else sel)
    }

    private var invariantSaveJob: Job? = null

    /** Автосейв: мгновенно в UI-стейт, на диск — с debounce 600мс. */
    fun onInvariantEdit(docId: String, text: String) {
        val capped = text.take(8000)
        _state.update { cur ->
            cur.copy(invariantContents = cur.invariantContents + (docId to capped))
        }
        invariantSaveJob?.cancel()
        invariantSaveJob = scope.launch {
            delay(600)
            try { service.saveInvariantContent(docId, capped) } catch (_: Exception) { }
        }
    }

    // --- ПКМ save flow: distill -> диалог -> commit ---

    fun startSave(raw: String, chatScope: Scope, chatParentId: String?) {
        val st = _state.value
        // дефолтная цель = текущий scope чата
        val defaultParent: String? = when (chatScope) {
            Scope.GENERAL -> null
            Scope.PROJECT -> chatParentId
            Scope.TASK -> chatParentId
        }
        _state.update {
            it.copy(
                saveDialog = SaveDialog(
                    rawText = raw.trim().take(2000),
                    busy = true,
                    targetScope = chatScope,
                    targetParentId = defaultParent,
                )
            )
        }
        scope.launch {
            val facts = try { service.distill(raw) } catch (_: Exception) { listOf(raw.trim().take(500)) }
            _state.update {
                it.copy(
                    saveDialog = it.saveDialog?.copy(
                        busy = false,
                        candidates = facts.ifEmpty { listOf(raw.trim().take(500)) },
                        editedFact = facts.firstOrNull().orEmpty(),
                    )
                )
            }
        }
    }

    fun closeSave() = _state.update { it.copy(saveDialog = null) }

    fun setSaveEdited(v: String) = _state.update { it.copy(saveDialog = it.saveDialog?.copy(editedFact = v)) }
    fun setSaveScope(s: Scope) = _state.update {
        it.copy(saveDialog = it.saveDialog?.copy(targetScope = s, targetParentId = null, targetDocId = null, useNewDoc = false))
    }
    fun setSaveParent(id: String?) = _state.update {
        it.copy(saveDialog = it.saveDialog?.copy(targetParentId = id, targetDocId = null))
    }
    fun setSaveDoc(id: String?) = _state.update {
        it.copy(saveDialog = it.saveDialog?.copy(targetDocId = id, useNewDoc = false))
    }
    fun setSaveNewDoc(use: Boolean, title: String = _state.value.saveDialog?.newDocTitle.orEmpty()) = _state.update {
        it.copy(saveDialog = it.saveDialog?.copy(useNewDoc = use, newDocTitle = title))
    }
    fun pickCandidate(i: Int) = _state.update {
        val c = it.saveDialog?.candidates?.getOrNull(i) ?: return@update it
        it.copy(saveDialog = it.saveDialog.copy(editedFact = c))
    }

    fun commitSave() {
        val d = _state.value.saveDialog ?: return
        val fact = d.editedFact.trim().take(500)
        if (fact.isEmpty()) return
        val docId: String = if (d.useNewDoc || d.targetDocId == null) {
            val title = d.newDocTitle.trim().take(60).ifBlank { "memory" }
            service.createMemoryDoc(title, d.targetScope, d.targetParentId).id
        } else d.targetDocId
        service.commitFact(docId, fact)
        _state.update { it.copy(saveDialog = null) }
        refresh()
    }

    fun messagesOf(chatId: String): List<ChatMessage> =
        service.state().messages[chatId].orEmpty()

    // --- диалог профиля: Аноним (null) + выбор/создание/правка ---

    fun openProfile() {
        val s = service.state()
        val active = s.profiles.firstOrNull { it.id == s.activeProfileId }
        _state.update {
            it.copy(
                profileDialog = ProfileDialog(
                    selectedId = s.activeProfileId,
                    name = active?.name.orEmpty(),
                    style = active?.style.orEmpty(),
                    format = active?.format.orEmpty(),
                    constraints = active?.constraints.orEmpty(),
                )
            )
        }
    }

    fun closeProfile() = _state.update { it.copy(profileDialog = null) }

    fun setProfileSelected(id: String?) {
        val p = service.state().profiles.firstOrNull { it.id == id }
        service.setActiveProfile(id)
        _state.update {
            it.copy(
                profileDialog = it.profileDialog?.copy(
                    selectedId = id,
                    name = p?.name.orEmpty(),
                    style = p?.style.orEmpty(),
                    format = p?.format.orEmpty(),
                    constraints = p?.constraints.orEmpty(),
                )
            )
        }
        refresh()
    }

    fun setProfileName(v: String) = _state.update { it.copy(profileDialog = it.profileDialog?.copy(name = v.take(60))) }
    fun setProfileStyle(v: String) = _state.update { it.copy(profileDialog = it.profileDialog?.copy(style = v.take(500))) }
    fun setProfileFormat(v: String) = _state.update { it.copy(profileDialog = it.profileDialog?.copy(format = v.take(500))) }
    fun setProfileConstraints(v: String) = _state.update { it.copy(profileDialog = it.profileDialog?.copy(constraints = v.take(500))) }

    fun createProfile() {
        val d = _state.value.profileDialog ?: return
        if (d.name.isBlank()) return
        val p = service.createProfile(d.name, d.style, d.format, d.constraints)
        _state.update { it.copy(profileDialog = d.copy(selectedId = p.id)) }
        refresh()
    }

    fun saveProfile() {
        val d = _state.value.profileDialog ?: return
        val id = d.selectedId ?: return
        service.updateProfile(id, d.name, d.style, d.format, d.constraints)
        refresh()
    }

    fun deleteProfile() {
        val d = _state.value.profileDialog ?: return
        val id = d.selectedId ?: return
        service.deleteProfile(id)
        _state.update { it.copy(profileDialog = ProfileDialog()) }
        refresh()
    }

    // --- Task State Machine (Task 3) + автоплан + авто-EXECUTION ---

    private var autoJob: Job? = null

    /** Чат для автозапуска: текущий выбранный TASK-чат этой задачи, иначе первый чат задачи. */
    private fun resolveTaskChat(taskId: String): Chat? {
        val sel = _state.value.selection as? Selection.ChatSel
        val selChat = sel?.chatId?.let { id -> service.state().chats.firstOrNull { it.id == id } }
        if (selChat != null && selChat.scope == Scope.TASK && selChat.parentId == taskId) return selChat
        return service.findTaskChat(taskId)
    }

    fun advanceTask(taskId: String) {
        val stageBefore = service.getTaskState(taskId)?.stage
        val chat = resolveTaskChat(taskId)
        // planning → execution: дистиллировать ВЕСЬ разговор в чистый план через API,
        // выложить в отдельный документ «план», затем запустить АВТО-цикл по всем шагам.
        if (stageBefore == TaskStage.PLANNING && chat != null) {
            if (_state.value.busy) return
            autoJob?.cancel()
            _state.update { it.copy(busy = true, status = null) }
            refresh(Selection.ChatSel(chat.id))
            autoJob = scope.launch {
                try {
                    service.finalizePlanAndAdvance(taskId, chat.id)
                } catch (e: Exception) {
                    service.advanceTask(taskId, reason = "fallback: ${e.message?.take(100)}")
                }
                refresh(Selection.ChatSel(chat.id))
                val stageAfter = service.getTaskState(taskId)?.stage
                if (stageAfter == TaskStage.EXECUTION) {
                    // не сбрасываем busy — startAutoExecution продолжит с тем же индикатором
                    startAutoExecutionLocked(taskId, chat.id)
                } else {
                    // Task 5: отказ гарда (нет плана) — показать причину вместо молчания
                    _state.update { it.copy(busy = false, status = service.lastDeniedReason) }
                    refresh()
                }
            }
            return
        }
        // EXECUTION → VALIDATION: переход + автопроверка инвариантов.
        // success — ждём валидации пользователя, failure — показываем ошибки, спрашиваем Retry.
        if (stageBefore == TaskStage.EXECUTION) {
            val chatForCheck = chat
            if (chatForCheck == null) {
                autoJob?.cancel()
                service.advanceTask(taskId)
                _state.update { it.copy(busy = false, status = service.lastDeniedReason) }
                refresh()
                return
            }
            if (_state.value.busy) return
            autoJob?.cancel()
            _state.update { it.copy(busy = true, status = null) }
            refresh(Selection.ChatSel(chatForCheck.id))
            autoJob = scope.launch {
                try {
                    service.advanceToValidationWithCheck(taskId, chatForCheck.id, reason = "manual: execution done")
                } catch (_: Exception) {
                    service.advanceTask(taskId)
                }
                _state.update { it.copy(busy = false, status = service.lastDeniedReason) }
                refresh(Selection.ChatSel(chatForCheck.id))
            }
            return
        }
        // Task 5: VALIDATION → DONE только через completeTask (гард SUCCESS-валидации).
        if (stageBefore == TaskStage.VALIDATION) {
            autoJob?.cancel()
            service.completeTask(taskId, reason = "manual: validation approved")
            _state.update { it.copy(busy = false, status = service.lastDeniedReason) }
            refresh()
            return
        }
        // остальные переходы — синхронно без дистилляции (VALIDATION → DONE = подтверждение юзера)
        autoJob?.cancel()
        service.advanceTask(taskId)
        _state.update { it.copy(busy = false, status = service.lastDeniedReason) }
        refresh()
    }

    /** Task 5: явное завершение из UI с показом причины отказа. */
    fun completeTask(taskId: String) {
        service.completeTask(taskId, reason = "manual: validation approved")
        _state.update { it.copy(status = service.lastDeniedReason) }
        refresh()
    }

    /**
     * Авто-цикл EXECUTION: шаг за шагом через API, выдаёт код.
     * Пауза (status=PAUSED или cancel job) останавливает цикл на НЕвыполненном шаге:
     * in-flight шаг не помечается done, resume продолжит с него же.
     * После последнего шага — автопереход в VALIDATION и ожидание подтверждения юзера.
     */
    private suspend fun startAutoExecutionLocked(taskId: String, chatId: String) {
        val chat = service.state().chats.firstOrNull { it.id == chatId } ?: run {
            _state.update { it.copy(busy = false) }
            return
        }
        while (currentCoroutineContext().isActive) {
            val ts = service.getTaskState(taskId) ?: break
            if (ts.stage != TaskStage.EXECUTION) break
            if (ts.status != TaskStatus.ACTIVE) break // пауза — стоим на невыполненном шаге
            if (ts.stepIndex >= ts.steps.size) break
            if (ts.steps.isEmpty()) break
            val r: core.AskResult = try {
                service.autoExecuteCurrentStep(chat)
            } catch (e: CancellationException) {
                // Пауза во время запроса: in-flight шаг НЕ помечаем done, стоим на нём
                _state.update { it.copy(busy = false) }
                refresh(Selection.ChatSel(chatId))
                throw e
            }
            when (r) {
                is core.AskResult.Success -> {
                    refresh(Selection.ChatSel(chatId))
                    _state.update {
                        it.copy(promptTokens = r.promptTokens,
                            systemPrompt = service.buildSystemPrompt(chat))
                    }
                    // После ответа проверить паузу ДО пометки done:
                    // если юзер нажал паузу во время запроса — шаг остаётся невыполненным
                    val after = service.getTaskState(taskId) ?: break
                    if (after.status != TaskStatus.ACTIVE || after.stage != TaskStage.EXECUTION) break
                    if (!currentCoroutineContext().isActive) break
                    val isLast = after.stepIndex >= after.steps.size - 1
                    if (isLast) {
                        service.completeCurrentStep(taskId)
                        refresh(Selection.ChatSel(chatId))
                        // Все шаги готовы → автопереход в VALIDATION + автопроверка инвариантов
                        try {
                            service.advanceToValidationWithCheck(taskId, chatId, reason = "auto: all steps done")
                        } catch (_: Exception) {
                            service.advanceTask(taskId, reason = "auto: all steps done")
                        }
                        _state.update { it.copy(busy = false, status = null) }
                        refresh(Selection.ChatSel(chatId))
                        break
                    } else {
                        service.nextStep(taskId) // помечает текущий done + двигает индекс
                        refresh(Selection.ChatSel(chatId))
                        // цикл продолжится со следующим шагом
                    }
                }
                is core.AskResult.Failure -> {
                    refresh(Selection.ChatSel(chatId))
                    // «на паузе» — не ошибка, просто стоим на текущем шаге
                    if (r.message.contains("пауз")) {
                        _state.update { it.copy(busy = false, status = null) }
                    } else {
                        _state.update { it.copy(busy = false, status = r.message) }
                    }
                    break
                }
            }
        }
        // страховка: если вышли не в VALIDATION/DONE — снять busy
        val end = service.getTaskState(taskId)
        if (end?.stage == TaskStage.EXECUTION && end.status == TaskStatus.ACTIVE) {
            // цикл прерван отменой job (пауза уже выставит свой статус) — ничего
        }
        refresh()
    }

    /** Старт/рестарт авто-цикла с текущего (невыполненного) шага. Используется resume. */
    private fun startAutoExecution(taskId: String, chatId: String) {
        if (_state.value.busy) return
        autoJob?.cancel()
        _state.update { it.copy(busy = true, status = null) }
        refresh(Selection.ChatSel(chatId))
        autoJob = scope.launch { startAutoExecutionLocked(taskId, chatId) }
    }

    /** Legacy одиночный прогон одного шага (оставлен для ручного режима, авто-цикл выше — основной). */
    private fun autoRunStep(taskId: String, chatId: String) {
        val chat = service.state().chats.firstOrNull { it.id == chatId } ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, status = null) }
        refresh(Selection.ChatSel(chatId))
        scope.launch {
            when (val r = service.autoExecuteCurrentStep(chat)) {
                is core.AskResult.Success -> {
                    refresh(Selection.ChatSel(chatId))
                    _state.update {
                        it.copy(busy = false, promptTokens = r.promptTokens,
                            systemPrompt = service.buildSystemPrompt(chat))
                    }
                }
                is core.AskResult.Failure -> {
                    refresh(Selection.ChatSel(chatId))
                    _state.update { it.copy(busy = false, status = r.message) }
                }
            }
        }
    }

    /** Ручной шаг (не авто): оставлен для PLANNING, в EXECUTION авто-цикл — основной. */
    fun nextStepAndRun(taskId: String) {
        val chat = resolveTaskChat(taskId) ?: run { nextStep(taskId); return }
        // В EXECUTION одиночный шаг не нужен — есть авто-цикл; но для совместимости прогнать один
        autoRunStep(taskId, chat.id)
    }

    fun setTaskStage(taskId: String, stage: TaskStage) {
        service.setTaskStage(taskId, stage)
        // Task 5: прямой прыжок с отказом — показать причину (демо недопустимого перехода)
        _state.update { it.copy(status = service.lastDeniedReason) }
        refresh()
    }

    fun pauseTask(taskId: String) {
        // Остановить авто-цикл ПЕРВЫМ, затем статус PAUSED — цикл встанет на невыполненном шаге
        autoJob?.cancel()
        autoJob = null
        service.pauseTask(taskId)
        _state.update { it.copy(busy = false) }
        refresh()
    }

    fun resumeTask(taskId: String) {
        service.resumeTask(taskId)
        refresh()
        // В EXECUTION resume = продолжить авто с текущего невыполненного шага
        val ts = service.getTaskState(taskId)
        if (ts?.stage == TaskStage.EXECUTION && ts.status == TaskStatus.ACTIVE) {
            val chat = resolveTaskChat(taskId) ?: return
            startAutoExecution(taskId, chat.id)
        }
        // В VALIDATION resume ничего не запускает — ждём подтверждения юзера
    }

    fun nextStep(taskId: String) {
        service.nextStep(taskId)
        refresh()
    }

    fun prevStep(taskId: String) {
        service.prevStep(taskId)
        refresh()
    }

    fun completeStep(taskId: String) {
        service.completeCurrentStep(taskId)
        refresh()
    }

    fun updateNextAction(taskId: String, action: String) {
        service.updateNextAction(taskId, action)
        refresh()
    }

    // --- Task 4: валидация инвариантов + Retry EXECUTION ---

    /** Ручной ре-чек в VALIDATION (кнопка «Проверить снова»). */
    fun runValidation(taskId: String) {
        val chat = resolveTaskChat(taskId) ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, status = null) }
        refresh(Selection.ChatSel(chat.id))
        scope.launch {
            try {
                service.validateAgainstInvariants(taskId, chat.id)
            } catch (e: Exception) {
                _state.update { it.copy(status = "Проверка не удалась: ${e.message?.take(150)}") }
            }
            _state.update { it.copy(busy = false) }
            refresh(Selection.ChatSel(chat.id))
        }
    }

    /** failure → Retry EXECUTION: удалить реплики прошлой execution, откат VALIDATION -> EXECUTION + рестарт авто. */
    fun retryExecution(taskId: String) {
        val chat = resolveTaskChat(taskId)
        autoJob?.cancel()
        autoJob = null
        service.clearExecutionReplies(taskId)
        if (!service.retryExecution(taskId)) {
            _state.update { it.copy(status = "Retry невозможен (только из VALIDATION)") }
            refresh()
            return
        }
        _state.update { it.copy(busy = false, status = null) }
        refresh(chat?.let { Selection.ChatSel(it.id) })
        // сразу рестарт авто-цикла с невыполненных шагов
        if (chat != null) startAutoExecution(taskId, chat.id)
    }

    /** Плей/пауза EXECUTION: плей = автоматический режим выдачи кода. */
    fun toggleAuto(taskId: String) {
        val ts = service.getTaskState(taskId) ?: return
        if (ts.stage != TaskStage.EXECUTION) return
        if (ts.status == TaskStatus.PAUSED) {
            resumeTask(taskId)
            return
        }
        // ACTIVE: если авто крутится — пауза, иначе старт авто с текущего шага
        if (_state.value.busy) {
            pauseTask(taskId)
        } else {
            val chat = resolveTaskChat(taskId) ?: return
            startAutoExecution(taskId, chat.id)
        }
    }

    /** REPLAN: остановить авто, почистить все реплики, вернуться в PLANNING и ждать планирования. */
    fun replanTask(taskId: String) {
        autoJob?.cancel()
        autoJob = null
        service.clearTaskChats(taskId)
        service.replanTask(taskId)
        _state.update { it.copy(busy = false, status = null) }
        val chat = resolveTaskChat(taskId)
        refresh(chat?.let { Selection.ChatSel(it.id) })
    }
}
