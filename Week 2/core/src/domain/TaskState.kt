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
    fun canTransition(from: TaskStage, to: TaskStage): Boolean = when (from) {
        TaskStage.PLANNING -> to == TaskStage.EXECUTION
        TaskStage.EXECUTION -> to == TaskStage.VALIDATION
        TaskStage.VALIDATION -> to == TaskStage.DONE
        TaskStage.DONE -> false
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

    fun nextAction(stage: TaskStage, stepIndex: Int, steps: List<TaskStep>): String =
        defaultNextAction(stage, stepIndex, steps)
}
