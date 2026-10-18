package core.agent

// ===== Task 4: возобновление задачи =====
//
// «Продолжить» без памяти — это начать заново. Новый прогон начинается с чистого
// контекста: агент не знает, какие файлы уже прочитаны, что уже записано и на чём
// прошлый прогон остановился. Поэтому продолжение собирает бриф по предыдущим
// прогонам: цель, что сделано, где встали. Модель после этого продолжает с места
// остановки, а не переделывает работу.

// Список прошлых прогонов в компактном виде. Порядок — хронологический.
private fun runLine(r: AgentRun): String = buildString {
    append("- прогон ").append(r.id).append(": ").append(r.label())
    append(" · шагов ").append(r.steps).append(" · вызовов ").append(r.invocations.size)
    if (r.changedFiles.isNotEmpty()) append(" · изменены: ").append(r.changedFiles.joinToString(", "))
    val tail = when (val s = r.state) {
        is RunState.Done -> s.summary.ifBlank { r.summary }
        is RunState.Failed -> s.error
        is RunState.Cancelled -> "отменён пользователем"
        else -> r.summary
    }.trim()
    if (tail.isNotBlank()) append("\n  итог: ").append(tail.take(400))
}

// Бриф для продолжения. Тот же buildAgentUser, но с явным «что уже сделано».
fun buildResumePrompt(goal: String, contextLine: String, previous: List<AgentRun>): String = buildString {
    if (contextLine.isNotBlank()) append("Контекст проекта:\n").append(contextLine).append("\n\n")
    append("ПРОДОЛЖЕНИЕ ЗАДАЧИ. ЦЕЛЬ:\n").append(goal).append("\n\n")
    append("Что уже было сделано (предыдущие прогоны, иди дальше, не сначала):\n")
    previous.forEach { append(runLine(it)).append('\n') }
    append("\nПравила продолжения:\n")
    append("1. Сначала оцени состояние: git_diff и read_file файлов, которые уже менялись. Доверяй файлам, а не памяти о прошлом прогоне.\n")
    append("2. Не повторяй сделанное: если нужная правка уже лежит в файле, не трогай его второй раз.\n")
    append("3. Лимит шагов у этого прогона свой — работай по оставшейся части задачи, а не заново по всей.\n")
    append("4. Доведи цель до конца и вызови finish. Если что-то осталось невыполнимым — честно напиши это в finish, а не «готово».\n")
}