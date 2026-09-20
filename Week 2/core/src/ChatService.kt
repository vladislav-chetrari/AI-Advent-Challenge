package core

import core.data.Store
import core.domain.AppState
import core.domain.Chat
import core.domain.ChatMessage
import core.domain.DISTILL_SYSTEM
import core.domain.MemoryDoc
import core.domain.Project
import core.domain.PromptBuilder
import core.domain.Scope
import core.domain.Task
import core.domain.UserProfile
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
        return PromptBuilder.buildSystemPrompt(docs, activeProfile(), tState, tName)
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
        if (!TaskStateMachine.canAdvance(cur.status, cur.stage)) return false
        val nextStage = TaskStateMachine.nextStage(cur.stage) ?: return false
        if (!TaskStateMachine.canTransition(cur.stage, nextStage)) return false
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
        }
        val newSteps = if (cur.stage == TaskStage.PLANNING) planSteps else defaultStepsFor(nextStage)
        val trans = StateTransition(cur.stage, nextStage, reason = reason.take(200))
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
        if (cur.stage == TaskStage.DONE) return false
        // allow direct jump only via valid transition
        if (!TaskStateMachine.canTransition(cur.stage, stage) && stage != TaskStage.DONE) return false
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
}
