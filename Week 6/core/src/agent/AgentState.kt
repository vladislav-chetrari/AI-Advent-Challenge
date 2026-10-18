package core.agent

import java.io.File
import kotlinx.serialization.Serializable

// ===== Task 4: конечный автомат агента =====
//
// Тот же приём, что в opencode v2: НЕ if-else в цикле, а каталог событий +
// ЧИСТЫЙ редьюсер (state, event) -> {state, commands}. Политика завершения
// (лимит шагов, что считать продолжением, что обрывает прогон) живёт здесь и
// покрывается verify-agent без сети, без БД и без LLM.
//
// Три вещи, которые стоит скопировать из opencode дословно:
//
//  1. Состояние вызова тула — tagged union: Pending -> Running -> Done|Failed.
//  2. Гард в редьюсере: терминальное событие принимается ТОЛЬКО из
//     нетерминального состояния. Без этого повторный replay события
//     перетирал бы уже записанный результат.
//  3. Отказ по разрешению НЕ возвращается модели текстом и обрывает прогон.
//     Иначе модель по кругу повторяет тот же запрещённый вызов.

// Состояние одного вызова тула (аналог opencode ToolState).
@Serializable
sealed interface ToolState {
    @Serializable
    data object Pending : ToolState

    @Serializable
    data class Running(val tool: String = "", val args: String = "") : ToolState

    @Serializable
    data class Done(val tool: String = "", val summary: String = "", val diff: String = "") : ToolState

    @Serializable
    data class Failed(val tool: String = "", val error: String = "") : ToolState
}

val ToolState.isTerminal: Boolean get() = this is ToolState.Done || this is ToolState.Failed

// Состояние прогона.
@Serializable
sealed interface RunState {
    @Serializable
    data object Idle : RunState

    @Serializable
    data class Goal(val text: String = "") : RunState

    @Serializable
    data class Thinking(val step: Int = 0) : RunState

    @Serializable
    data class AwaitingApproval(val change: PendingChange) : RunState

    @Serializable
    data class Applying(val path: String = "") : RunState

    @Serializable
    data class Done(val summary: String = "") : RunState

    @Serializable
    data class Failed(val error: String = "") : RunState

    @Serializable
    data object Cancelled : RunState
}

// Один запрошенный агентом вызов тула — то, что рисуется в UI как строка
// со статусом и что попадает в agent_runs.json.
@Serializable
data class ToolInvocation(
    val id: String,
    val tool: String,
    val args: String = "",
    val state: ToolState = ToolState.Pending,
    val at: Long = 0,
)

// Прогон целиком. Это и есть «результат можно воспроизвести»: цель, шаги,
// все вызовы с аргументами, что именно изменилось и итоговый diff.
@Serializable
data class AgentRun(
    val id: String,
    val project: String,
    val goal: String = "",
    // Прогон принадлежит агентному чату: у каждого своя история прогонов,
    // дифов и откатов. Пусто = headless-прогон из CLI, без чата.
    val chatId: String = "",
    val state: RunState = RunState.Idle,
    val steps: Int = 0,
    val invocations: List<ToolInvocation> = emptyList(),
    val changedFiles: List<String> = emptyList(),
    val diff: String = "",
    val summary: String = "",
    val error: String = "",
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun pending(): ToolInvocation? = invocations.lastOrNull { it.state is ToolState.Pending }

    fun byId(id: String): ToolInvocation? = invocations.lastOrNull { it.id == id }

    // Прогон имеет смысл продолжать: он не довёл задачу до конца — упал,
    // отменён или встал на лимит (тогда state = Done, а error объясняет почему).
    // Успешный прогон продолжать незачем: задача уже закрыта.
    val resumable: Boolean
        get() = state is RunState.Failed || state is RunState.Cancelled || (state is RunState.Done && error.isNotBlank())

    fun label(): String = when (val s = state) {
        is RunState.Idle -> "ожидание"
        is RunState.Goal -> "цель принята"
        is RunState.Thinking -> "шаг ${s.step}"
        is RunState.AwaitingApproval -> "ждёт подтверждения: ${s.change.path}"
        is RunState.Applying -> "записывает ${s.path}"
        is RunState.Done -> "готово"
        is RunState.Failed -> "ошибка: ${s.error}"
        is RunState.Cancelled -> "отменено"
    }
}

// Запрошенное изменение — то, что показывается в диалоге подтверждения
// (аналог opencode Permission.Request + metadata.files с готовым diff).
@Serializable
data class PendingChange(
    val path: String,
    val action: String,
    val diff: String,
    val replacements: Int = 1,
    val summary: String = "",
)

// Каталог событий. sealed interface — исчерпывающий when в редьюсере
// компилятором: новое событие без перехода = ошибка сборки (в opencode ту же
// роль играет Match.discriminatorsExhaustive).
@Serializable
sealed interface AgentEvent {
    @Serializable
    data class GoalAccepted(val text: String) : AgentEvent

    @Serializable
    data class StepStarted(val step: Int) : AgentEvent

    @Serializable
    data class ToolsProposed(val tool: String, val callId: String, val args: String) : AgentEvent

    @Serializable
    data class ApprovalRequested(val change: PendingChange) : AgentEvent

    @Serializable
    data class ApprovalGranted(val path: String) : AgentEvent

    @Serializable
    data class ApprovalDenied(val path: String) : AgentEvent

    @Serializable
    data class ToolStarted(val callId: String) : AgentEvent

    @Serializable
    data class ToolSucceeded(val callId: String, val summary: String, val diff: String = "") : AgentEvent

    @Serializable
    data class ToolFailed(val callId: String, val error: String) : AgentEvent

    @Serializable
    data class FileChanged(val path: String, val diff: String) : AgentEvent

    @Serializable
    data class Finished(val summary: String) : AgentEvent

    @Serializable
    data class BudgetExhausted(val reason: String) : AgentEvent

    @Serializable
    data object Cancelled : AgentEvent

    @Serializable
    data class Failed(val error: String) : AgentEvent
}

// Что редьюсер просит у цикла сделать. Пустой список = событие только
// меняет состояние (для UI/журнала).
@Serializable
sealed interface AgentCommand {
    @Serializable
    data object CallLlm : AgentCommand

    @Serializable
    data object AbortStep : AgentCommand

    @Serializable
    data object Finish : AgentCommand
}

@Serializable
data class Transition(
    val run: AgentRun,
    val commands: List<AgentCommand> = emptyList(),
    val terminal: Boolean = false,
)

// Лимитов по умолчанию нет. Агент работает, пока не вызовет finish (или его
// остановят: сеть, отказ по разрешению, ⏹). Раньше стояли 16 шагов / 40 вызовов
// / 10 файлов, и задача, которая чуть длиннее, заканчивалась «Остановлен: лимит
// шагов», хотя агент был готов продолжать. Предел задаёт тот, кто запускает:
// AgentConfig ниже (--max-steps в CLI) — а в приложении его нет вовсе.
data class AgentConfig(
    // null = без ограничения.
    val maxSteps: Int? = null,
    val maxToolCalls: Int? = null,
    val maxFilesChanged: Int? = null,
)

// Редьюсер. Чистый: на входе прогон и событие, на выходе новый прогон и
// список команд. Ни сети, ни диска, ни LLM — поэтому verify-agent быстрый
// и детерминированный.
object AgentMachine {

    fun reduce(run: AgentRun, event: AgentEvent, cfg: AgentConfig = AgentConfig()): Transition {
        val now = System.currentTimeMillis()
        // Терминальный прогон больше не меняется: события arriving после Done
        // (гонка UI, повторный replay) не должны перетирать итог.
        if (run.state is RunState.Done || run.state is RunState.Cancelled ||
            (run.state is RunState.Failed && event !is AgentEvent.Failed)
        ) {
            return Transition(run, emptyList(), terminal = true)
        }
        // Терминальное событие по вызову — только из нетерминального
        // (гарг из opencode: иначе повторный replay ломает историю).
        fun settle(callId: String, next: ToolState): AgentRun {
            val inv = run.byId(callId) ?: return run
            if (inv.state.isTerminal) return run
            return run.copy(
                invocations = run.invocations.map { if (it.id == callId) it.copy(state = next) else it },
                updatedAt = now,
            )
        }

        val (next, commands, terminal) = when (event) {
            is AgentEvent.GoalAccepted -> Triple(
                run.copy(state = RunState.Goal(event.text), goal = event.text, updatedAt = now),
                listOf(AgentCommand.CallLlm),
                false,
            )

            is AgentEvent.StepStarted -> Triple(
                run.copy(state = RunState.Thinking(event.step), steps = event.step, updatedAt = now),
                emptyList(),
                false,
            )

            is AgentEvent.ToolsProposed -> Triple(
                run.copy(
                    invocations = run.invocations + ToolInvocation(
                        id = event.callId,
                        tool = event.tool,
                        args = event.args.take(600),
                        state = ToolState.Pending,
                        at = now,
                    ),
                    updatedAt = now,
                ),
                emptyList(),
                false,
            )

            is AgentEvent.ApprovalRequested -> Triple(
                run.copy(state = RunState.AwaitingApproval(event.change), updatedAt = now),
                emptyList(),
                false,
            )

            is AgentEvent.ApprovalGranted -> Triple(
                run.copy(state = RunState.Applying(event.path), updatedAt = now),
                emptyList(),
                false,
            )

            // Отказ обрывает прогон. Это НЕ уходит модели текстом (см.
            // PermissionDenied): иначе она повторит тот же вызов по кругу.
            is AgentEvent.ApprovalDenied -> Triple(
                run.copy(
                    state = RunState.Failed("изменение ${event.path} отклонено"),
                    error = "изменение ${event.path} отклонено",
                    updatedAt = now,
                ),
                listOf(AgentCommand.AbortStep),
                true,
            )

            is AgentEvent.ToolStarted -> Triple(
                settle(event.callId, ToolState.Running(run.byId(event.callId)?.tool.orEmpty(), run.byId(event.callId)?.args.orEmpty())),
                emptyList(),
                false,
            )

            is AgentEvent.ToolSucceeded -> Triple(
                settle(event.callId, ToolState.Done(run.byId(event.callId)?.tool.orEmpty(), event.summary, event.diff)),
                emptyList(),
                false,
            )

            is AgentEvent.ToolFailed -> Triple(
                settle(event.callId, ToolState.Failed(run.byId(event.callId)?.tool.orEmpty(), event.error)),
                emptyList(),
                false,
            )

            is AgentEvent.FileChanged -> Triple(
                run.copy(
                    state = RunState.Thinking(run.steps),
                    changedFiles = (run.changedFiles + event.path).distinct(),
                    diff = if (event.diff.isBlank()) run.diff else (run.diff + "\n" + event.diff).trimStart('\n'),
                    updatedAt = now,
                ),
                emptyList(),
                false,
            )

            is AgentEvent.Finished -> Triple(
                run.copy(state = RunState.Done(event.summary), summary = event.summary, updatedAt = now),
                listOf(AgentCommand.Finish),
                true,
            )

            // Бюджет кончился: тулы отключаются (opencode ставит toolChoice=none
            // на лимите шагов) и прогон закрывается честным текстом.
            is AgentEvent.BudgetExhausted -> Triple(
                run.copy(
                    state = RunState.Done(run.summary.ifBlank { "Остановлен: ${event.reason}" }),
                    summary = run.summary.ifBlank { "Остановлен: ${event.reason}" },
                    error = event.reason,
                    updatedAt = now,
                ),
                listOf(AgentCommand.Finish),
                true,
            )

            is AgentEvent.Cancelled -> Triple(
                run.copy(state = RunState.Cancelled, updatedAt = now),
                listOf(AgentCommand.AbortStep),
                true,
            )

            is AgentEvent.Failed -> Triple(
                run.copy(state = RunState.Failed(event.error), error = event.error, updatedAt = now),
                listOf(AgentCommand.AbortStep),
                true,
            )
        }

        // Бюджет проверяем после шага, а не в каждом событии: иначе прогон,
        // уложившийся в лимит файлов, останавливался бы на первом же чтении.
        val post = if (!terminal) {
            val reason = budgetReason(next, cfg)
            if (reason != null) reduce(next, AgentEvent.BudgetExhausted(reason), cfg) else Transition(next, commands, false)
        } else {
            Transition(next, commands, terminal)
        }
        return post
    }

    // Человеческое объяснение, ПОЧЕМУ остановились. Возвращается в отчёт:
    // «молча остановился на шаге 5» — худший вариант для воспроизводимости.
    // null = лимиты не заданы (обычный случай), остановиться нечему.
    fun budgetReason(run: AgentRun, cfg: AgentConfig): String? {
        cfg.maxSteps?.let { if (run.steps >= it) return "лимит шагов ($it)" }
        cfg.maxToolCalls?.let { if (run.invocations.size >= it) return "лимит вызовов тулов ($it)" }
        cfg.maxFilesChanged?.let { if (run.changedFiles.size >= it) return "лимит изменённых файлов ($it)" }
        return null
    }

    // Нужен ли ещё вызов LLM: opencode считает продолжением сам факт
    // локально исполненного тула. Здесь то же + проверка бюджета.
    fun needsContinuation(run: AgentRun, cfg: AgentConfig = AgentConfig()): Boolean =
        !run.state.let { it is RunState.Done || it is RunState.Failed || it is RunState.Cancelled } &&
            budgetReason(run, cfg) == null

    fun newRun(project: String, goal: String, chatId: String = ""): AgentRun {
        val now = System.currentTimeMillis()
        return AgentRun(
            id = "run_" + now.toString(36) + "_" + java.util.UUID.randomUUID().toString().take(4),
            project = project,
            goal = goal,
            chatId = chatId,
            startedAt = now,
            updatedAt = now,
        )
    }
}

// Каталог прогонов на диске: ~/.ai-advent-week6/agent_runs.json.
// По образцу ChatStore — синхронизация, запись целиком, отказ молча при
// нечитаемом файле. Это долговременная история: без неё прогон нельзя
// ни показать, ни откатить, ни воспроизвести.
class RunStore(appDir: File = core.llm.ApiKeyProvider.appDir()) {
    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val file = File(appDir, "agent_runs.json")
    private val snapshotDir = File(appDir, "agent_snapshots")
    private val lock = Any()

    @Volatile
    var runs: List<AgentRun> = emptyList()
        private set

    init {
        file.parentFile?.mkdirs()
        load()
    }

    fun snapshotsDir(): File = snapshotDir

    fun load() {
        synchronized(lock) {
            runs = try {
                if (file.isFile) json.decodeFromString<List<AgentRun>>(file.readText()) else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun persistLocked() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AgentRun.serializer()), runs))
        } catch (_: Exception) {
        }
    }

    fun save(run: AgentRun) {
        synchronized(lock) {
            runs = (runs.filterNot { it.id == run.id } + run).sortedBy { it.startedAt }
            persistLocked()
        }
    }

    fun get(id: String): AgentRun? = runs.firstOrNull { it.id == id }

    fun latest(): AgentRun? = runs.lastOrNull()

    // История прогонов одного агентного чата. Без неё в панели агента нельзя
    // показать «что этот чат уже делал» — все чаты делили один список.
    fun forChat(chatId: String): List<AgentRun> = runs.filter { it.chatId == chatId }

    fun latestForChat(chatId: String): AgentRun? = forChat(chatId).lastOrNull()

    // Отвязать прогоны от удалённого чата: история не пропадает (её видно в
    // CLI), но в панели чата больше не всплывает.
    fun detachChat(chatId: String) {
        synchronized(lock) {
            runs = runs.map { if (it.chatId == chatId) it.copy(chatId = "") else it }
            persistLocked()
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            runs = runs.filterNot { it.id == id }
            persistLocked()
        }
    }

    // Откат прогона: файлы возвращаются к состоянию до первого изменения.
    fun revert(run: AgentRun, workspace: Workspace): Int {
        var n = 0
        run.changedFiles.forEach { rel ->
            // revert возвращает false, если снапшота нет: такой файл не трогаем.
            if (workspace.revert(rel, run.id, snapshotDir)) n++
        }
        save(run.copy(state = RunState.Done("откачено"), summary = "откачено"))
        return n
    }
}
