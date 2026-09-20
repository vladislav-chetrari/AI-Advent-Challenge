package core.domain

import kotlinx.serialization.Serializable

enum class TaskStage(val label: String) {
    PLANNING("planning"),
    EXECUTION("execution"),
    VALIDATION("validation"),
    DONE("done"),
}

enum class TaskStatus(val label: String) {
    ACTIVE("active"),
    PAUSED("paused"),
    DONE("done"),
}

@Serializable
data class TaskStep(
    val id: String = newId(),
    val title: String,
    val done: Boolean = false,
    val note: String = "",
)

@Serializable
data class StateTransition(
    val from: TaskStage,
    val to: TaskStage,
    val at: Long = System.currentTimeMillis(),
    val reason: String = "",
)

@Serializable
data class TaskState(
    val taskId: String,
    val stage: TaskStage = TaskStage.PLANNING,
    val status: TaskStatus = TaskStatus.ACTIVE,
    val stepIndex: Int = 0,
    val steps: List<TaskStep> = emptyList(),
    val nextAction: String = defaultNextAction(TaskStage.PLANNING, 0, emptyList()),
    val history: List<StateTransition> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Task 4: результат автопроверки инвариантов на этапе VALIDATION
    val lastValidation: ValidationResult? = null,
)

fun defaultNextAction(stage: TaskStage, stepIndex: Int, steps: List<TaskStep>): String = when (stage) {
    TaskStage.PLANNING -> "Сформулируй план и разбей на шаги"
    TaskStage.EXECUTION -> {
        val s = steps.getOrNull(stepIndex)
        if (s != null) "Выполни шаг ${stepIndex + 1}/${steps.size}: ${s.title}"
        else "Выполни следующий шаг"
    }
    TaskStage.VALIDATION -> "Проверь результат, прогони тесты/валидацию"
    TaskStage.DONE -> "Задача завершена"
}

fun defaultStepsFor(stage: TaskStage): List<TaskStep> = when (stage) {
    TaskStage.PLANNING -> listOf(
        TaskStep(title = "Уточнить цель"),
        TaskStep(title = "Сформулировать план"),
        TaskStep(title = "Согласовать план"),
    )
    TaskStage.EXECUTION -> listOf(
        TaskStep(title = "Шаг 1"),
        TaskStep(title = "Шаг 2"),
        TaskStep(title = "Шаг 3"),
    )
    TaskStage.VALIDATION -> listOf(
        TaskStep(title = "Проверить критерии"),
        TaskStep(title = "Исправить замечания"),
    )
    TaskStage.DONE -> emptyList()
}

object TaskStateMachine {
    // Task 5 (День 15): явный результат перехода — отказ всегда с объяснимой причиной.
    sealed interface TransitionResult {
        data object Allowed : TransitionResult
        data class Denied(val reason: String) : TransitionResult
    }

    // Task 5: контекст гардов для перехода. Собирается в ChatService из TaskState + дока «план».
    data class TransitionContext(
        val status: TaskStatus = TaskStatus.ACTIVE,
        val hasApprovedPlan: Boolean = false,
        val allStepsDone: Boolean = false,
        val validation: ValidationResult? = null,
    )

    fun canTransition(from: TaskStage, to: TaskStage): Boolean = when (from) {
        TaskStage.PLANNING -> to == TaskStage.EXECUTION
        TaskStage.EXECUTION -> to == TaskStage.VALIDATION
        TaskStage.VALIDATION -> to == TaskStage.DONE
        TaskStage.DONE -> false
    }

    /**
     * Task 5: единая точка контроля переходов.
     * Проверяет и топологию (canTransition/canRetry), и гарды:
     * - PLANNING→EXECUTION только с утверждённым планом (≥2 шагов + док «план»);
     * - EXECUTION→VALIDATION только когда все шаги done;
     * - VALIDATION→DONE только при lastValidation==SUCCESS.
     */
    fun requestTransition(from: TaskStage, to: TaskStage, ctx: TransitionContext = TransitionContext()): TransitionResult {
        if (from == TaskStage.DONE) return TransitionResult.Denied("Задача завершена (DONE) — переходы запрещены, создай новую задачу")
        if (ctx.status != TaskStatus.ACTIVE && !(from == TaskStage.VALIDATION && to == TaskStage.EXECUTION)) {
            // Retry тоже требует ACTIVE — проверяем отдельно ниже; пауза блокирует всё остальное
            if (ctx.status == TaskStatus.PAUSED) return TransitionResult.Denied("Задача на паузе — сначала нажми «Продолжить»")
            return TransitionResult.Denied("Переход невозможен в статусе ${ctx.status.name}")
        }
        // Retry — единственный разрешённый откат
        if (canRetry(from, to)) {
            if (ctx.status != TaskStatus.ACTIVE) return TransitionResult.Denied("Retry возможен только из ACTIVE-статуса")
            return TransitionResult.Allowed
        }
        if (!canTransition(from, to)) {
            return TransitionResult.Denied("Пропуск этапа запрещён: ${from.name}→${to.name}. Иди по цепочке PLANNING→EXECUTION→VALIDATION→DONE")
        }
        return when (from to to) {
            TaskStage.PLANNING to TaskStage.EXECUTION ->
                if (ctx.hasApprovedPlan) TransitionResult.Allowed
                else TransitionResult.Denied("Нельзя в EXECUTION без утверждённого плана: нужно ≥2 шагов и док «план». Сформулируй план и нажми «→ Execution»")
            TaskStage.EXECUTION to TaskStage.VALIDATION ->
                if (ctx.allStepsDone) TransitionResult.Allowed
                else TransitionResult.Denied("Нельзя в VALIDATION: не все шаги EXECUTION выполнены. Доведи шаги до конца (авто-цикл)")
            TaskStage.VALIDATION to TaskStage.DONE ->
                if (ctx.validation?.verdict == ValidationVerdict.SUCCESS) TransitionResult.Allowed
                else TransitionResult.Denied("Нельзя в DONE без успешной валидации: дождись ✅ «Инварианты соблюдены» (кнопка «Проверить») или сделай Retry")
            else -> TransitionResult.Denied("Переход ${from.name}→${to.name} запрещён")
        }
    }

    fun canPause(status: TaskStatus, stage: TaskStage): Boolean =
        status == TaskStatus.ACTIVE && stage != TaskStage.DONE

    fun canResume(status: TaskStatus): Boolean = status == TaskStatus.PAUSED

    fun canAdvance(status: TaskStatus, stage: TaskStage): Boolean =
        status == TaskStatus.ACTIVE && stage != TaskStage.DONE

    fun nextStage(current: TaskStage): TaskStage? = when (current) {
        TaskStage.PLANNING -> TaskStage.EXECUTION
        TaskStage.EXECUTION -> TaskStage.VALIDATION
        TaskStage.VALIDATION -> TaskStage.DONE
        TaskStage.DONE -> null
    }

    // Task 4: Retry — откат VALIDATION -> EXECUTION при failure по инвариантам
    fun canRetry(from: TaskStage, to: TaskStage): Boolean =
        from == TaskStage.VALIDATION && to == TaskStage.EXECUTION

    // Task 5: запреты этапа для system prompt — ассистент не «перепрыгивает» этап даже по просьбе.
    fun stageRules(stage: TaskStage): String = when (stage) {
        TaskStage.PLANNING -> "Код НЕ писать. Только план и уточняющие вопросы. На просьбу «давай код/реализуй» — откажи и предложи финализировать план (кнопка «→ Execution»). Перепрыгивать в EXECUTION/VALIDATION/DONE запрещено."
        TaskStage.EXECUTION -> "Работай только над текущим шагом. Финал и валидацию не делать. На просьбу «заверши/финал» — откажи, доведи шаги до конца."
        TaskStage.VALIDATION -> "Новый код и фичи запрещены. Только проверка результата по инвариантам. На просьбу новой фичи — откажи, предложи Retry или Replan."
        TaskStage.DONE -> "Задача завершена. Новую работу не начинать. Предложи создать новую задачу."
    }

    fun nextAction(stage: TaskStage, stepIndex: Int, steps: List<TaskStep>): String =
        defaultNextAction(stage, stepIndex, steps)
}
