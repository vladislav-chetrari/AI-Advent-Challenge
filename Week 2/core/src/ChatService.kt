package core

import core.data.Store
import core.domain.AppState
import core.domain.Chat
import core.domain.ChatMessage
import core.domain.DISTILL_SYSTEM
import core.domain.InvariantDoc
import core.domain.InvariantViolation
import core.domain.MemoryDoc
import core.domain.Project
import core.domain.PromptBuilder
import core.domain.Scope
import core.domain.Task
import core.domain.UserProfile
import core.domain.VALIDATE_SYSTEM
import core.domain.ValidationResult
import core.domain.ValidationVerdict
import core.domain.parseValidationJson
import core.domain.StateTransition
import core.domain.TaskStage
import core.domain.TaskState
import core.domain.TaskStateMachine
import core.domain.TaskStatus
import core.domain.TaskStep
import core.domain.defaultNextAction
import core.domain.defaultStepsFor
import core.domain.PLAN_DISTILL_SYSTEM
import core.domain.newId
import core.domain.parseDistillJson
import core.domain.parsePlanDistillJson
import core.network.ApiKeyProvider
import core.network.LlmClient
import core.network.LlmResult

sealed interface AskResult {
    data class Success(val text: String, val promptTokens: Int = 0) : AskResult
    data class Failure(val message: String) : AskResult
}

// Фасад: дерево + память + LLM. История — в Store.state, контент памяти — в .md.
class ChatService(
    val store: Store = Store(Store.defaultDir()),
    private var llm: LlmClient = LlmClient(),
) {
    var lastPromptTokens: Int = 0
        private set
    var lastSystemPrompt: String = PromptBuilder.BASE
        private set
    // Task 5: причина последнего отказа в переходе/запросе — UI показывает в статусе.
    var lastDeniedReason: String? = null
        private set

    fun state(): AppState = store.state

    // --- создание сущностей дерева ---

    fun createProject(name: String): Project {
        val p = Project(newId(), name.trim().take(60).ifBlank { "project" })
        store.update { it.copy(projects = it.projects + p) }
        return p
    }

    fun createTask(projectId: String, name: String): Task {
        val t = Task(newId(), projectId, name.trim().take(60).ifBlank { "task" })
        val init = TaskState(
            taskId = t.id,
            stage = TaskStage.PLANNING,
            status = TaskStatus.ACTIVE,
            steps = defaultStepsFor(TaskStage.PLANNING),
        )
        store.update { it.copy(tasks = it.tasks + t, taskStates = it.taskStates + (t.id to init)) }
        return t
    }

    fun createChat(name: String, scope: Scope, parentId: String?): Chat {
        val c = Chat(newId(), name.trim().take(60).ifBlank { "chat" }, scope, parentId)
        store.update { it.copy(chats = it.chats + c, messages = it.messages + (c.id to emptyList())) }
        return c
    }

    fun createMemoryDoc(title: String, scope: Scope, parentId: String?): MemoryDoc {
        val clean = title.trim().take(60).ifBlank { "memory" }
        // Дедуп по title в том же scope+parent
        store.state.memoryDocs.firstOrNull {
            it.scope == scope && it.parentId == parentId && it.title == clean
        }?.let { return it }
        val d = MemoryDoc(newId(), clean, scope, parentId)
        store.update { it.copy(memoryDocs = it.memoryDocs + d) }
        store.writeDocContent(d.id, "# $clean\n")
        return d
    }

    fun toggleDocActive(docId: String) {
        store.update { s ->
            s.copy(memoryDocs = s.memoryDocs.map { if (it.id == docId) it.copy(active = !it.active) else it })
        }
    }

    fun deleteDoc(docId: String) {
        store.update { s -> s.copy(memoryDocs = s.memoryDocs.filterNot { it.id == docId }) }
        store.deleteDocContent(docId)
    }

    fun docContent(doc: MemoryDoc): String = store.readDocContent(doc)

    // --- Task 4: инварианты (отдельный тип сущности) ---

    fun createInvariantDoc(title: String, scope: Scope, parentId: String?): InvariantDoc {
        val clean = title.trim().take(60).ifBlank { "инварианты" }
        store.state.invariantDocs.firstOrNull {
            it.scope == scope && it.parentId == parentId && it.title == clean
        }?.let { return it }
        val d = InvariantDoc(newId(), clean, scope, parentId)
        store.update { it.copy(invariantDocs = it.invariantDocs + d) }
        store.writeInvariantContent(d.id, "")
        return d
    }

    fun toggleInvariantActive(docId: String) {
        store.update { s ->
            s.copy(invariantDocs = s.invariantDocs.map { if (it.id == docId) it.copy(active = !it.active) else it })
        }
    }

    fun deleteInvariant(docId: String) {
        store.update { s -> s.copy(invariantDocs = s.invariantDocs.filterNot { it.id == docId }) }
        store.deleteInvariantContent(docId)
    }

    fun invariantContent(doc: InvariantDoc): String = store.readInvariantContent(doc)

    fun saveInvariantContent(docId: String, content: String) {
        store.writeInvariantContent(docId, content.take(8000))
    }

    // --- релевантные доки с наследованием ---

    fun relevantDocs(chat: Chat): List<MemoryDoc> {
        val s = store.state
        val general = s.memoryDocs.filter { it.scope == Scope.GENERAL && it.active }
        return when (chat.scope) {
            Scope.GENERAL -> general.sortedBy { it.title }
            Scope.PROJECT -> {
                val proj = s.memoryDocs.filter {
                    it.scope == Scope.PROJECT && it.parentId == chat.parentId && it.active
                }
                (general + proj).sortedBy { it.scope.ordinal * 1000 + it.title.hashCode() % 1000 }
                    .sortedWith(compareBy({ it.scope.ordinal }, { it.title }))
            }
            Scope.TASK -> {
                val task = s.memoryDocs.filter {
                    it.scope == Scope.TASK && it.parentId == chat.parentId && it.active
                }
                val taskObj = s.tasks.firstOrNull { it.id == chat.parentId }
                val proj = if (taskObj != null) s.memoryDocs.filter {
                    it.scope == Scope.PROJECT && it.parentId == taskObj.projectId && it.active
                } else emptyList()
                (general + proj + task).sortedWith(compareBy({ it.scope.ordinal }, { it.title }))
            }
        }
    }

    // Task 4: релевантные инварианты с тем же наследованием что и память:
    // PROJECT-чат -> инварианты проекта, TASK-чат -> проекта + задачи. Только active.
    fun relevantInvariants(chat: Chat): List<InvariantDoc> {
        val s = store.state
        return when (chat.scope) {
            Scope.GENERAL -> emptyList()
            Scope.PROJECT -> s.invariantDocs.filter {
                it.scope == Scope.PROJECT && it.parentId == chat.parentId && it.active
            }.sortedBy { it.title }
            Scope.TASK -> {
                val taskObj = s.tasks.firstOrNull { it.id == chat.parentId }
                val proj = if (taskObj != null) s.invariantDocs.filter {
                    it.scope == Scope.PROJECT && it.parentId == taskObj.projectId && it.active
                } else emptyList()
                val task = s.invariantDocs.filter {
                    it.scope == Scope.TASK && it.parentId == chat.parentId && it.active
                }
                (proj + task).sortedWith(compareBy({ it.scope.ordinal }, { it.title }))
            }
        }
    }

    fun invariantsTextForChat(chat: Chat): String =
        relevantInvariants(chat).map { store.readInvariantContent(it).trim() }
            .filter { it.isNotBlank() }.joinToString("\n\n").take(3200)

    fun buildSystemPrompt(chat: Chat): String {
        val docs = relevantDocs(chat).map { it.title to store.readDocContent(it) }
        val tState: TaskState? = when (chat.scope) {
            Scope.TASK -> getTaskState(chat.parentId ?: "")
            Scope.PROJECT -> null
            Scope.GENERAL -> null
        }
        val tName: String? = when (chat.scope) {
            Scope.TASK -> store.state.tasks.firstOrNull { it.id == chat.parentId }?.name
            else -> null
        }
        return PromptBuilder.buildSystemPrompt(docs, activeProfile(), tState, tName, invariantsTextForChat(chat))
    }

    // --- профили (День 12): null = Аноним, в промпт ничего не инжектится ---

    fun activeProfile(): UserProfile? {
        val id = store.state.activeProfileId ?: return null
        return store.state.profiles.firstOrNull { it.id == id }
    }

    fun createProfile(name: String, style: String, format: String, constraints: String): UserProfile {
        val p = UserProfile(
            id = newId(),
            name = name.trim().take(60).ifBlank { "пользователь" },
            style = style.trim().take(500),
            format = format.trim().take(500),
            constraints = constraints.trim().take(500),
        )
        store.update { it.copy(profiles = it.profiles + p, activeProfileId = p.id) }
        return p
    }

    fun updateProfile(id: String, name: String, style: String, format: String, constraints: String) {
        store.update { s ->
            s.copy(profiles = s.profiles.map {
                if (it.id == id) it.copy(
                    name = name.trim().take(60).ifBlank { it.name },
                    style = style.trim().take(500),
                    format = format.trim().take(500),
                    constraints = constraints.trim().take(500),
                ) else it
            })
        }
    }

    fun deleteProfile(id: String) {
        store.update { s ->
            s.copy(
                profiles = s.profiles.filterNot { it.id == id },
                activeProfileId = if (s.activeProfileId == id) null else s.activeProfileId,
            )
        }
    }

    fun setActiveProfile(id: String?) {
        // null = Аноним; чужой id игнорируем, чтобы не зависнуть на битой ссылке
        val clean = if (id != null && store.state.profiles.none { it.id == id }) null else id
        store.update { it.copy(activeProfileId = clean) }
    }

    fun clearChat(chatId: String) {
        store.update { it.copy(messages = it.messages + (chatId to emptyList())) }
    }

    // --- ask: sliding window + facts injection ---
    // Разбит на два шага, чтобы UI мог показать user-сообщение мгновенно (optimistic echo),
    // а не после ответа LLM: сначала appendUserMessage (sync) + refresh, потом completeAsk.

    fun appendUserMessage(chat: Chat, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val live = store.state.messages[chat.id].orEmpty() + ChatMessage("user", clean)
        store.update { it.copy(messages = it.messages + (chat.id to live)) }
    }

    private fun rollbackUserMessage(chatId: String) {
        val cur = store.state.messages[chatId].orEmpty()
        if (cur.lastOrNull()?.role == "user") {
            store.update { it.copy(messages = it.messages + (chatId to cur.dropLast(1))) }
        }
    }

    suspend fun completeAsk(chat: Chat): AskResult {
        // Task 5: программный гард стадии — отказ без вызова LLM, но с видимой реакцией.
        // user-сообщение уже в сторе (optimistic echo), дописываем отказ ассистента.
        checkStageRefusal(chat)?.let { refusal ->
            val updated = store.state.messages[chat.id].orEmpty() + ChatMessage("assistant", refusal)
            store.update { it.copy(messages = it.messages + (chat.id to updated)) }
            return AskResult.Success(refusal)
        }
        val apiKey = ApiKeyProvider.resolve()
        if (apiKey.isNullOrBlank()) {
            rollbackUserMessage(chat.id)
            return AskResult.Failure("DEEPSEEK_API_KEY не найден (env или .env).")
        }

        val system = buildSystemPrompt(chat)
        lastSystemPrompt = system
        val live = store.state.messages[chat.id].orEmpty()
        if (live.lastOrNull()?.role != "user") return AskResult.Failure("Пустой запрос.")

        val toSend = PromptBuilder.effectiveHistory(system, live)
        return when (val r = llm.complete(toSend, apiKey)) {
            is LlmResult.Ok -> {
                lastPromptTokens = r.usage.promptTokens
                val updated = store.state.messages[chat.id].orEmpty() + ChatMessage("assistant", r.text)
                store.update { it.copy(messages = it.messages + (chat.id to updated)) }
                AskResult.Success(r.text, r.usage.promptTokens)
            }
            is LlmResult.HttpError -> {
                // откат user-сообщения
                rollbackUserMessage(chat.id)
                AskResult.Failure(
                    if (r.code == 401) "DeepSeek отклонил ключ (401)."
                    else "DeepSeek API: HTTP ${r.code} ${r.detail.take(200)}"
                )
            }
            is LlmResult.Empty -> AskResult.Failure("Пустой ответ модели.")
            is LlmResult.NetworkError -> {
                rollbackUserMessage(chat.id)
                AskResult.Failure("Сеть: ${r.detail.take(200)}")
            }
        }
    }

    suspend fun ask(chat: Chat, prompt: String): AskResult {
        val clean = prompt.trim()
        if (clean.isEmpty()) return AskResult.Failure("Пустой запрос.")
        appendUserMessage(chat, clean)
        return completeAsk(chat)
    }

    // --- distill + commit (ПКМ "сохранить в память") ---

    suspend fun distill(raw: String): List<String> {
        val apiKey = ApiKeyProvider.resolve() ?: return listOf(raw.trim().take(500))
        val r = llm.complete(
            listOf(
                ChatMessage("system", DISTILL_SYSTEM),
                ChatMessage("user", raw.trim().take(2000)),
            ),
            apiKey,
        )
        val ok = r as? LlmResult.Ok ?: return listOf(raw.trim().take(500))
        val parsed = parseDistillJson(ok.text)
        return parsed.ifEmpty { listOf(raw.trim().take(500)) }
    }

    fun commitFact(docId: String, fact: String) {
        store.appendFact(docId, fact)
    }

    // --- Task State Machine (Task 3) ---

    fun getTaskState(taskId: String): TaskState? = store.state.taskStates[taskId]

    fun ensureTaskState(taskId: String): TaskState {
        getTaskState(taskId)?.let { return it }
        val init = TaskState(taskId = taskId, steps = defaultStepsFor(TaskStage.PLANNING))
        store.update { it.copy(taskStates = it.taskStates + (taskId to init)) }
        return init
    }

    fun updateTaskState(taskId: String, fn: (TaskState) -> TaskState) {
        val cur = getTaskState(taskId) ?: TaskState(taskId = taskId, steps = defaultStepsFor(TaskStage.PLANNING))
        val next = fn(cur).copy(updatedAt = System.currentTimeMillis())
        store.update { it.copy(taskStates = it.taskStates + (taskId to next)) }
    }

    // --- Task 5 (День 15): контролируемые переходы ---

    /** Контекст гардов для requestTransition: план утверждён? шаги готовы? валидация успешна? */
    fun transitionContext(taskId: String): TaskStateMachine.TransitionContext {
        val ts = getTaskState(taskId) ?: return TaskStateMachine.TransitionContext()
        val planOk = ts.steps.size >= 2 &&
            (planDoc(taskId)?.let { store.readDocContent(it).trim().isNotBlank() } == true ||
                ts.stage != TaskStage.PLANNING)
        return TaskStateMachine.TransitionContext(
            status = ts.status,
            hasApprovedPlan = planOk,
            allStepsDone = ts.steps.isNotEmpty() && ts.steps.all { it.done },
            validation = ts.lastValidation,
        )
    }

    /** Единая попытка перехода с объяснимой причиной. Успех — выполняет переход, отказ — пишет lastDeniedReason. */
    fun tryTransition(taskId: String, to: TaskStage, reason: String = ""): TaskStateMachine.TransitionResult {
        val cur = getTaskState(taskId)
            ?: return TaskStateMachine.TransitionResult.Denied("Нет состояния задачи").also { lastDeniedReason = it.reason }
        val r = TaskStateMachine.requestTransition(cur.stage, to, transitionContext(taskId))
        if (r is TaskStateMachine.TransitionResult.Denied) lastDeniedReason = r.reason
        else lastDeniedReason = null
        return r
    }

    private val codeRequestRe = Regex("давай код|напиши код|реализуй|имплемент|сделай реализацию|write (the )?code|implement", RegexOption.IGNORE_CASE)
    private val finalRequestRe = Regex("финал|заверши|готово|done|подтверди завершение|finish", RegexOption.IGNORE_CASE)

    /**
     * Task 5: проверка запроса юзера против стадии. Возвращает текст отказа или null если можно дальше.
     * Консервативно: срабатывает только на явные маркеры, обычный диалог не блокируется.
     */
    fun checkStageRefusal(chat: Chat): String? {
        if (chat.scope != Scope.TASK) return null
        val taskId = chat.parentId ?: return null
        val ts = getTaskState(taskId) ?: return null
        if (ts.status == TaskStatus.PAUSED) return "⏸ Задача на паузе (этап ${ts.stage.name}). Нажми «▶ Продолжить» — продолжим с шага ${ts.stepIndex + 1}/${ts.steps.size} без повторов."
        val lastUser = store.state.messages[chat.id].orEmpty().lastOrNull { it.role == "user" }?.content.orEmpty()
        if (lastUser.isBlank()) return null
        return when (ts.stage) {
            TaskStage.PLANNING ->
                if (codeRequestRe.containsMatchIn(lastUser))
                    "Не могу писать код на этапе PLANNING — сначала утвердим план. ${ts.nextAction}. Нажми «→ Execution», затем код пойдёт по шагам."
                else null
            TaskStage.EXECUTION ->
                if (finalRequestRe.containsMatchIn(lastUser) && !(ts.steps.isNotEmpty() && ts.steps.all { it.done }))
                    "Не могу завершить: EXECUTION не готов — выполнено ${ts.steps.count { it.done }}/${ts.steps.size}. Доведи шаги до конца, затем VALIDATION."
                else null
            TaskStage.VALIDATION ->
                if (codeRequestRe.containsMatchIn(lastUser))
                    "На этапе VALIDATION новый код запрещён — только проверка. Если нужно менять код — нажми «↻ Retry», вернёмся в EXECUTION."
                else null
            TaskStage.DONE -> "Задача завершена (DONE) — новая работа запрещена. Создай новую задачу."
        }?.also { lastDeniedReason = it }
    }

    /** Task 5: завершение задачи — только VALIDATION→DONE при SUCCESS-валидации. */
    fun completeTask(taskId: String, reason: String = ""): TaskStateMachine.TransitionResult {
        val cur = getTaskState(taskId)
            ?: return TaskStateMachine.TransitionResult.Denied("Нет состояния задачи")
        val r = TaskStateMachine.requestTransition(cur.stage, TaskStage.DONE, transitionContext(taskId))
        if (r is TaskStateMachine.TransitionResult.Denied) {
            lastDeniedReason = r.reason
            return r
        }
        lastDeniedReason = null
        val trans = StateTransition(cur.stage, TaskStage.DONE, reason = reason.take(200))
        updateTaskState(taskId) {
            it.copy(
                stage = TaskStage.DONE,
                status = TaskStatus.DONE,
                nextAction = defaultNextAction(TaskStage.DONE, 0, emptyList()),
                history = it.history + trans,
            )
        }
        return TaskStateMachine.TransitionResult.Allowed
    }

    // --- План в отдельный документ + авто-выполнение (Task 3, расширение) ---

    companion object {
        const val PLAN_DOC_TITLE = "план"
    }

    fun findTaskChat(taskId: String): Chat? =
        store.state.chats.firstOrNull { it.scope == Scope.TASK && it.parentId == taskId }

    fun lastAssistantText(chatId: String): String? =
        store.state.messages[chatId].orEmpty().lastOrNull { it.role == "assistant" }?.content

    /** Эвристика: вытащить шаги из текста плана (нумерованный / маркированный список). */
    fun parsePlanSteps(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val steps = lines.mapNotNull { line ->
            // "1. Сделать X", "1) Сделать X", "- Сделать X", "* Сделать X", "• Сделать X"
            val m = Regex("""^(?:\d+[.)]\s+|[-*•]\s+)(.+)$""").matchEntire(line)?.groupValues?.get(1)?.trim()
            // "Шаг 1: Сделать X" / "Шаг 1 - Сделать X"
            val m2 = m ?: Regex("""^(?:шаг\s*\d+\s*[:.\-–]\s*)(.+)$""", RegexOption.IGNORE_CASE)
                .matchEntire(line)?.groupValues?.get(1)?.trim()
            m2?.takeIf { it.length >= 3 }
        }.take(10)
        return steps
    }

    /** Импорт плана из текста чата в steps. Возвращает true если шаги обновлены. */
    fun importPlanStepsFromText(taskId: String, text: String): Boolean {
        val parsed = parsePlanSteps(text)
        if (parsed.size < 2) return false
        setTaskSteps(taskId, parsed)
        return true
    }

    /**
     * Дистилляция ВСЕГО разговора в чистый план через API.
     * Берёт все сообщения чата (не окно N=12, а полный транскрипт), шлёт в PLAN_DISTILL_SYSTEM,
     * возвращает 3-7 чистых шагов. Fallback — эвристика по всему разговору.
     */
    suspend fun distillPlanFromChat(chatId: String): List<String> {
        val msgs = store.state.messages[chatId].orEmpty()
        if (msgs.isEmpty()) return emptyList()
        // Полный транскрипт с ролями, обрезанный до ~8000 симв чтобы влезть в запрос
        val transcript = msgs.joinToString("\n") { "${it.role}: ${it.content}" }.take(8000)
        if (transcript.isBlank()) return emptyList()
        val apiKey = ApiKeyProvider.resolve()
        if (!apiKey.isNullOrBlank()) {
            val r = llm.complete(
                listOf(
                    ChatMessage("system", PLAN_DISTILL_SYSTEM),
                    ChatMessage("user", transcript),
                ),
                apiKey,
            )
            val ok = r as? LlmResult.Ok
            if (ok != null) {
                val parsed = parsePlanDistillJson(ok.text)
                if (parsed.size >= 2) return parsed
            }
        }
        // Fallback без API: эвристика по ВСЕМУ разговору, а не последней реплике
        val fallback = parsePlanSteps(transcript)
        return fallback
    }

    fun buildPlanMarkdown(taskId: String, sourceNote: String? = null): String {
        val taskName = store.state.tasks.firstOrNull { it.id == taskId }?.name ?: taskId.take(8)
        val ts = getTaskState(taskId)
        return buildString {
            append("# План: ").append(taskName).append("\n\n")
            if (ts != null) {
                append("Этап на момент фиксации: ").append(ts.stage.name).append("\n")
                if (ts.history.isNotEmpty()) {
                    append("История: ")
                        .append(ts.history.takeLast(5).joinToString(" → ") { "${it.from.name}→${it.to.name}" })
                        .append("\n")
                }
                append("\n## Шаги\n")
                if (ts.steps.isEmpty()) append("(шаги не заданы)\n")
                ts.steps.forEachIndexed { i, s ->
                    append(if (s.done) "- [x] " else "- [ ] ")
                        .append(s.title).append("\n")
                }
                append("\nОжидаемое действие: ").append(ts.nextAction).append("\n")
            }
            // Только короткая пометка об источнике, сырец НЕ кладём — документ = чистый план
            if (!sourceNote.isNullOrBlank()) {
                append("\n_").append(sourceNote.trim().take(200)).append("_\n")
            }
        }
    }

    /** Сохранить ЧИСТЫЙ план в отдельный .md (scope=TASK, title="план"). Перезаписывает при повторе. */
    fun savePlanDoc(taskId: String, sourceNote: String? = null): MemoryDoc {
        val doc = createMemoryDoc(PLAN_DOC_TITLE, Scope.TASK, taskId)
        store.writeDocContent(doc.id, buildPlanMarkdown(taskId, sourceNote))
        return doc
    }

    fun planDoc(taskId: String): MemoryDoc? =
        store.state.memoryDocs.firstOrNull {
            it.scope == Scope.TASK && it.parentId == taskId && it.title == PLAN_DOC_TITLE
        }

    /**
     * Финализация плана: дистиллировать ВЕСЬ разговор в чистый план через API,
     * выложить в отдельный документ «план», затем перейти planning → execution.
     * Для остальных переходов — обычный advanceTask.
     */
    suspend fun finalizePlanAndAdvance(taskId: String, chatId: String, reason: String = ""): Boolean {
        val cur = getTaskState(taskId) ?: return false
        if (!TaskStateMachine.canAdvance(cur.status, cur.stage)) return false
        if (cur.stage != TaskStage.PLANNING) return advanceTask(taskId, reason)
        // 1. Дистилляция всего разговора (API или fallback-эвристика по всему транскрипту)
        val msgCount = store.state.messages[chatId].orEmpty().size
        val distilled: List<String> = try {
            distillPlanFromChat(chatId)
        } catch (_: Exception) { emptyList() }
        if (distilled.size >= 2) {
            setTaskSteps(taskId, distilled)
        } else {
            // fallback уже внутри distillPlanFromChat; если всё равно пусто и шаги —
            // дефолтное planning-трио («Уточнить цель»…), заменить на дефолт execution,
            // чтобы в execution не выполнялся мусор planning
            val curSteps = getTaskState(taskId)?.steps.orEmpty().map { it.title }
            val defaultPlanning = defaultStepsFor(TaskStage.PLANNING).map { it.title }
            if (curSteps == defaultPlanning) {
                val execTitles = defaultStepsFor(TaskStage.EXECUTION).map { it.title }
                setTaskSteps(taskId, execTitles)
            }
        }
        // 2. Чистый план в отдельный документ (без сырца)
        try {
            val note = if (msgCount > 0) "Дистиллировано из $msgCount сообщений • ${cur.stage.name} → execution"
                else "Зафиксировано вручную"
            // перечитать шаги после setTaskSteps чтобы документ содержал чистый план
            savePlanDoc(taskId, sourceNote = note)
        } catch (_: Exception) { }
        // 3. Переход с сохранением шагов (не затирать дефолтом)
        return advanceTask(taskId, reason)
    }

    fun advanceTask(taskId: String, reason: String = "", rawPlan: String? = null): Boolean {
        val cur = getTaskState(taskId) ?: return false
        if (!TaskStateMachine.canAdvance(cur.status, cur.stage)) {
            lastDeniedReason = if (cur.status == TaskStatus.PAUSED) "Задача на паузе — сначала нажми «Продолжить»" else "Переход невозможен в статусе ${cur.status.name}"
            return false
        }
        val nextStage = TaskStateMachine.nextStage(cur.stage) ?: return false
        // Task 5: гарды перед топологией — отказ с причиной вместо молчаливого false.
        // Исключение: PLANNING→EXECUTION проверяется ПОСЛЕ сохранения плана ниже
        // (иначе hasApprovedPlan всегда false на первом проходе).
        if (cur.stage != TaskStage.PLANNING) {
            val gate = TaskStateMachine.requestTransition(cur.stage, nextStage, transitionContext(taskId))
            if (gate is TaskStateMachine.TransitionResult.Denied) {
                lastDeniedReason = gate.reason
                return false
            }
        }
        // Этап плана: шаги НЕ затираем дефолтом — они уже чистые
        // (дистилляция всего разговора делается в finalizePlanAndAdvance).
        // Здесь только страховка: сохранить текущий чистый план в документ.
        var planSteps: List<TaskStep> = cur.steps
        if (cur.stage == TaskStage.PLANNING) {
            // legacy-путь: если вдруг передали сырец одной реплики — попробовать эвристику,
            // но основной путь — весь разговор через finalizePlanAndAdvance
            if (planSteps.isEmpty() && !rawPlan.isNullOrBlank()) {
                val parsed = parsePlanSteps(rawPlan)
                if (parsed.size >= 2) {
                    planSteps = parsed.map { TaskStep(title = it) }
                    updateTaskState(taskId) {
                        it.copy(steps = planSteps, stepIndex = 0,
                            nextAction = defaultNextAction(it.stage, 0, planSteps))
                    }
                }
            }
            try {
                savePlanDoc(taskId, sourceNote = "planning → execution")
            } catch (_: Exception) { }
            if (planSteps.isEmpty()) planSteps = defaultStepsFor(nextStage)
            // сбросить done при входе в execution, т.к. это новый цикл выполнения
            planSteps = planSteps.map { it.copy(done = false) }
            // Task 5: гард PLANNING→EXECUTION после фиксации плана (шаги + док «план» уже на месте)
            if (planSteps.size < 2) {
                lastDeniedReason = "Нельзя в EXECUTION без утверждённого плана: нужно ≥2 шагов и док «план»"
                return false
            }
        }
        val newSteps = if (cur.stage == TaskStage.PLANNING) planSteps else defaultStepsFor(nextStage)
        val trans = StateTransition(cur.stage, nextStage, reason = reason.take(200))
        lastDeniedReason = null
        updateTaskState(taskId) {
            it.copy(
                stage = nextStage,
                status = if (nextStage == TaskStage.DONE) TaskStatus.DONE else TaskStatus.ACTIVE,
                stepIndex = 0,
                steps = newSteps,
                nextAction = defaultNextAction(nextStage, 0, newSteps),
                history = it.history + trans,
            )
        }
        return true
    }

    /**
     * Авто-выполнение текущего шага через API: постит [авто]-запрос в чат задачи и ждёт ответ LLM.
     * Промпт содержит явную просьбу «давай код / напиши код», иначе BASE запрещает код.
     */
    suspend fun autoExecuteCurrentStep(chat: Chat): AskResult {
        val taskId = chat.parentId ?: return AskResult.Failure("Чат не привязан к задаче.")
        val ts = getTaskState(taskId) ?: return AskResult.Failure("Нет состояния задачи.")
        if (ts.status != TaskStatus.ACTIVE) return AskResult.Failure("Задача на паузе — нажми Продолжить.")
        if (ts.stage != TaskStage.EXECUTION) return AskResult.Failure("Авто-выполнение только на этапе EXECUTION.")
        val step = ts.steps.getOrNull(ts.stepIndex) ?: return AskResult.Failure("Нет шагов для выполнения.")
        val taskName = store.state.tasks.firstOrNull { it.id == taskId }?.name ?: "задача"
        val plan = planDoc(taskId)?.let { store.readDocContent(it) }?.take(2000).orEmpty()
        val prompt = buildString {
            append("[авто] Давай код — выполни шаг ").append(ts.stepIndex + 1).append("/").append(ts.steps.size)
                .append(" задачи «").append(taskName).append("»: ").append(step.title).append("\n")
            if (plan.isNotBlank()) append("План:\n").append(plan.take(1500)).append("\n")
            append("Напиши код полностью по этому шагу, шаг за шагом. ")
            append("В конце кратко (2-4 строки) опиши что сделано.")
        }.trim()
        appendUserMessage(chat, prompt)
        return completeAsk(chat)
    }

    /** Все шаги выполнены? */
    fun isExecutionComplete(taskId: String): Boolean {
        val ts = getTaskState(taskId) ?: return false
        return ts.stage == TaskStage.EXECUTION && ts.steps.isNotEmpty() && ts.steps.all { it.done }
    }

    fun setTaskStage(taskId: String, stage: TaskStage): Boolean {
        val cur = getTaskState(taskId) ?: return false
        if (cur.stage == stage) return true
        // Task 5: фикс дырки «любой этап → DONE»: только через requestTransition с гардами.
        val gate = TaskStateMachine.requestTransition(cur.stage, stage, transitionContext(taskId))
        if (gate is TaskStateMachine.TransitionResult.Denied) {
            lastDeniedReason = gate.reason
            return false
        }
        lastDeniedReason = null
        val trans = StateTransition(cur.stage, stage)
        val newSteps = defaultStepsFor(stage)
        updateTaskState(taskId) {
            it.copy(
                stage = stage,
                status = if (stage == TaskStage.DONE) TaskStatus.DONE else TaskStatus.ACTIVE,
                stepIndex = 0,
                steps = newSteps,
                nextAction = defaultNextAction(stage, 0, newSteps),
                history = it.history + trans,
            )
        }
        return true
    }

    fun pauseTask(taskId: String): Boolean {
        val cur = getTaskState(taskId) ?: return false
        if (!TaskStateMachine.canPause(cur.status, cur.stage)) return false
        updateTaskState(taskId) { it.copy(status = TaskStatus.PAUSED) }
        return true
    }

    fun resumeTask(taskId: String): Boolean {
        val cur = getTaskState(taskId) ?: return false
        if (!TaskStateMachine.canResume(cur.status)) return false
        updateTaskState(taskId) { it.copy(status = TaskStatus.ACTIVE) }
        return true
    }

    fun nextStep(taskId: String): Boolean {
        val cur = getTaskState(taskId) ?: return false
        if (cur.status != TaskStatus.ACTIVE) return false
        if (cur.steps.isEmpty()) return false
        if (cur.stepIndex >= cur.steps.size - 1) return false
        // mark current done, advance index
        val updatedSteps = cur.steps.mapIndexed { idx, s -> if (idx == cur.stepIndex) s.copy(done = true) else s }
        val newIdx = cur.stepIndex + 1
        updateTaskState(taskId) {
            it.copy(
                steps = updatedSteps,
                stepIndex = newIdx,
                nextAction = defaultNextAction(it.stage, newIdx, updatedSteps),
            )
        }
        return true
    }

    fun prevStep(taskId: String): Boolean {
        val cur = getTaskState(taskId) ?: return false
        if (cur.steps.isEmpty() || cur.stepIndex == 0) return false
        val newIdx = cur.stepIndex - 1
        updateTaskState(taskId) {
            it.copy(stepIndex = newIdx, nextAction = defaultNextAction(it.stage, newIdx, it.steps))
        }
        return true
    }

    fun completeCurrentStep(taskId: String): Boolean {
        val cur = getTaskState(taskId) ?: return false
        if (cur.steps.isEmpty()) return false
        val updated = cur.steps.mapIndexed { idx, s -> if (idx == cur.stepIndex) s.copy(done = true) else s }
        updateTaskState(taskId) { it.copy(steps = updated, nextAction = defaultNextAction(it.stage, it.stepIndex, updated)) }
        return true
    }

    fun setTaskSteps(taskId: String, titles: List<String>) {
        val clean = titles.map { it.trim().take(80) }.filter { it.isNotBlank() }.take(10)
        if (clean.isEmpty()) return
        val steps = clean.map { TaskStep(title = it) }
        updateTaskState(taskId) { it.copy(steps = steps, stepIndex = 0, nextAction = defaultNextAction(it.stage, 0, steps)) }
    }

    fun updateNextAction(taskId: String, action: String) {
        val clean = action.trim().take(200)
        if (clean.isEmpty()) return
        updateTaskState(taskId) { it.copy(nextAction = clean) }
    }

    // --- Task 4: валидация EXECUTION по инвариантам (VALIDATION) ---

    /**
     * Проверка всех сообщений EXECUTION + документ инвариантов.
     * Отправляет в API промпт соответствия с констрейнтом success|failure+errors.
     * Без инвариантов или без ключа — success (нечего/нечем проверять).
     */
    suspend fun validateAgainstInvariants(taskId: String, chatId: String): ValidationResult {
        val chat = store.state.chats.firstOrNull { it.id == chatId }
            ?: return ValidationResult(ValidationVerdict.SUCCESS, note = "Нет чата — проверка пропущена")
        val invText = invariantsTextForChat(chat)
        if (invText.isBlank()) {
            val ok = ValidationResult(ValidationVerdict.SUCCESS, note = "Инварианты не заданы — проверка пропущена")
            updateTaskState(taskId) { it.copy(lastValidation = ok) }
            return ok
        }
        val msgs = store.state.messages[chatId].orEmpty()
        if (msgs.isEmpty()) {
            val ok = ValidationResult(ValidationVerdict.SUCCESS, note = "Нет сообщений EXECUTION — нечего проверять")
            updateTaskState(taskId) { it.copy(lastValidation = ok) }
            return ok
        }
        val transcript = msgs.joinToString("\n") { "${it.role}: ${it.content}" }.take(8000)
        val apiKey = ApiKeyProvider.resolve()
        if (apiKey.isNullOrBlank()) {
            val ok = ValidationResult(ValidationVerdict.SUCCESS, note = "Нет API-ключа — автопроверка пропущена, решает пользователь")
            updateTaskState(taskId) { it.copy(lastValidation = ok) }
            return ok
        }
        val payload = buildString {
            append("ИНВАРИАНТЫ:\n").append(invText.take(4000)).append("\n\n")
            append("СООБЩЕНИЯ EXECUTION:\n").append(transcript)
        }.trim()
        val r = try {
            llm.complete(
                listOf(
                    ChatMessage("system", VALIDATE_SYSTEM),
                    ChatMessage("user", payload),
                ),
                apiKey,
            )
        } catch (e: Exception) {
            val fail = ValidationResult(
                ValidationVerdict.FAILURE,
                listOf(InvariantViolation("Ошибка вызова валидации", e.message?.take(200).orEmpty(), "Повторить проверку")),
                note = "network",
            )
            updateTaskState(taskId) { it.copy(lastValidation = fail) }
            return fail
        }
        val ok = r as? LlmResult.Ok
        if (ok == null) {
            val fail = ValidationResult(
                ValidationVerdict.FAILURE,
                listOf(InvariantViolation("Валидатор не ответил", "Пустой/ошибочный ответ API", "Повторить проверку")),
                note = "llm-error",
            )
            updateTaskState(taskId) { it.copy(lastValidation = fail) }
            return fail
        }
        val (success, errors) = parseValidationJson(ok.text)
        val res = if (success) ValidationResult(ValidationVerdict.SUCCESS, note = "Инварианты соблюдены")
        else ValidationResult(
            ValidationVerdict.FAILURE,
            errors.map { (rule, quote, fix) -> InvariantViolation(rule, quote, fix) }
                .ifEmpty { listOf(InvariantViolation("Нарушение инвариантов", ok.text.take(200), "Исправить и повторить")) },
            note = "Найдены нарушения",
        )
        updateTaskState(taskId) { it.copy(lastValidation = res) }
        return res
    }

    /**
     * Переход EXECUTION -> VALIDATION + автопроверка инвариантов.
     * Возвращает результат валидации (success — ждём валидации пользователя,
     * failure — показываем ошибки, спрашиваем Retry EXECUTION).
     */
    suspend fun advanceToValidationWithCheck(taskId: String, chatId: String, reason: String = ""): ValidationResult? {
        val cur = getTaskState(taskId) ?: return null
        if (cur.stage != TaskStage.EXECUTION) {
            advanceTask(taskId, reason)
            return getTaskState(taskId)?.lastValidation
        }
        advanceTask(taskId, reason)
        return try {
            validateAgainstInvariants(taskId, chatId)
        } catch (_: Exception) { getTaskState(taskId)?.lastValidation }
    }

    /** Удалить ВСЕ реплики всех чатов задачи (для REPLAN). */
    fun clearTaskChats(taskId: String) {
        val chatIds = store.state.chats
            .filter { it.scope == Scope.TASK && it.parentId == taskId }
            .map { it.id }
        if (chatIds.isEmpty()) return
        store.update { s ->
            s.copy(messages = s.messages + chatIds.associateWith { emptyList<ChatMessage>() })
        }
    }

    /**
     * Удалить только реплики EXECUTION: user-сообщения "[авто]..." + следующий за каждым ответ ассистента.
     * Планировочный диалог сохраняется (для RETRY).
     */
    fun clearExecutionReplies(taskId: String) {
        val chatIds = store.state.chats
            .filter { it.scope == Scope.TASK && it.parentId == taskId }
            .map { it.id }.toSet()
        if (chatIds.isEmpty()) return
        store.update { s ->
            var msgs = s.messages
            for (cid in chatIds) {
                val cur = msgs[cid].orEmpty()
                if (cur.isEmpty()) continue
                val kept = mutableListOf<ChatMessage>()
                var skipNextAssistant = false
                for (m in cur) {
                    if (m.role == "user" && m.content.trimStart().startsWith("[авто]")) {
                        skipNextAssistant = true
                        continue
                    }
                    if (skipNextAssistant && m.role == "assistant") {
                        skipNextAssistant = false
                        continue
                    }
                    kept.add(m)
                }
                msgs = msgs + (cid to kept)
            }
            s.copy(messages = msgs)
        }
    }

    /**
     * REPLAN: сбросить задачу в PLANNING с дефолтными шагами, очистить результат валидации.
     * Реплики чистит вызывающий код (clearTaskChats) — здесь только состояние.
     */
    fun replanTask(taskId: String, reason: String = "replan"): Boolean {
        val cur = getTaskState(taskId) ?: return false
        if (cur.stage == TaskStage.DONE) return false
        if (cur.stage == TaskStage.PLANNING) return true
        val steps = defaultStepsFor(TaskStage.PLANNING)
        val trans = StateTransition(cur.stage, TaskStage.PLANNING, reason = reason.take(200))
        updateTaskState(taskId) {
            it.copy(
                stage = TaskStage.PLANNING,
                status = TaskStatus.ACTIVE,
                stepIndex = 0,
                steps = steps,
                nextAction = defaultNextAction(TaskStage.PLANNING, 0, steps),
                history = it.history + trans,
                lastValidation = null,
            )
        }
        return true
    }

    /** Retry EXECUTION после failure: VALIDATION -> EXECUTION, шаги сбрасываются в невыполненные. */
    fun retryExecution(taskId: String, reason: String = "retry after invariant failure"): Boolean {
        val cur = getTaskState(taskId) ?: return false
        if (!TaskStateMachine.canRetry(cur.stage, TaskStage.EXECUTION)) return false
        if (cur.status != TaskStatus.ACTIVE) return false
        val trans = StateTransition(cur.stage, TaskStage.EXECUTION, reason = reason.take(200))
        val reset = cur.steps.map { it.copy(done = false) }
        updateTaskState(taskId) {
            it.copy(
                stage = TaskStage.EXECUTION,
                status = TaskStatus.ACTIVE,
                stepIndex = 0,
                steps = reset,
                nextAction = defaultNextAction(TaskStage.EXECUTION, 0, reset),
                history = it.history + trans,
                // lastValidation оставляем — UI покажет что было failure до ретрая
            )
        }
        return true
    }
}
