package core.agent

import core.llm.ChatMsg
import core.llm.LlmResult
import core.llm.ToolCall
import core.llm.ToolSpec
import core.llm.missingRequired
import core.rag.RagService
import core.rag.buildAgentSystem
import core.rag.buildAgentUser

// ===== Task 4: цикл агента =====
//
// Тонкий координатор над AgentMachine: вся политика (лимиты, что обрывает
// прогон, когда продолжать) — в редьюсере, здесь только исполнение.
//
// Отличия от наивного «while(true) зови LLM»:
//   * переписка идёт явным списком сообщений (как в opencode: оркестрация не
//     в памяти, а в том, что можно показать и повторить);
//   * невалидный вызов (битые аргументы, неизвестный тул) получает ответ в
//     переписку и НЕ крутит цикл;
//   * отказ по разрешению обрывает прогон и не возвращается модели текстом —
//     иначе она по кругу повторяет тот же запрещённый вызов.

class AgentRunner(
    private val rag: RagService,
    private val workspace: Workspace,
    private val runStore: RunStore,
    private val gate: PermissionGate,
    private val config: AgentConfig = AgentConfig(),
    private val onUpdate: (AgentRun) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) {
    suspend fun run(
        goal: String,
        project: String,
        seedHistory: List<ChatMsg> = emptyList(),
        apiKey: String? = null,
    ): AgentRun {
        var run = AgentMachine.newRun(project, goal)
        // Снапшоты пишем в папку RunStore — по ней же работает откат.
        // Без этого откат искал бы файлы не там и (до фикса метки) удалял их.
        workspace.runIdForSnapshot = run.id
        workspace.snapshotDir = runStore.snapshotsDir()

        // Единственная точка, где состояние прогона меняется наружу.
        fun apply(t: Transition): Transition {
            run = t.run
            current = t.run
            runStore.save(run)
            onUpdate(run)
            return t
        }

        val tools = FileTools(
            workspace,
            approve = { change ->
                apply(AgentMachine.reduce(run, AgentEvent.ApprovalRequested(change), config))
                val allowed = gate.check(project, change).effect == PermissionEffect.Allow.name
                if (allowed) apply(AgentMachine.reduce(run, AgentEvent.ApprovalGranted(change.path), config))
                // Отказ здесь НЕ переводит прогон в Failed: это делает execTool
                // после ToolFailed, иначе вызов навсегда остался бы в Running.
                allowed
            },
        )
        val defs = tools.definitions()
        val specs = defs.map { it.spec }

        apply(AgentMachine.reduce(run, AgentEvent.GoalAccepted(goal), config))

        val transcript = ArrayList<ChatMsg>()
        transcript += ChatMsg("system", buildAgentSystem(project, specs.map { it.name }, workspace.rootFile.name))
        // Хвост переписки — чтобы «обнови доки по тому, о чём говорили» работало.
        seedHistory.takeLast(SEED_HISTORY).forEach { transcript += it }
        transcript += ChatMsg("user", buildAgentUser(goal, projectLine(project)))

        while (true) {
            val step = run.steps + 1
            apply(AgentMachine.reduce(run, AgentEvent.StepStarted(step), config))
            onLog("шаг $step: спрашиваю модель …")

            // Бюджет кончился — тулы выключаются (opencode ставит toolChoice=none),
            // и модель получает шанс подвести итог словами, а не молча встать.
            val exhausted = AgentMachine.budgetReason(run, config) != null
            val toolChoice = if (exhausted) "none" else null
            when (val r = rag.askAgent(transcript, specs, toolChoice, apiKey)) {
                // Модель без native function-calling (локальная qwen на Ollama,
                // часть прокси) возвращает вызов обычным текстом. Разбираем
                // сами — иначе агент «думает», что всё сделал, ничего не сделав.
                is LlmResult.Ok -> {
                    val parsed = parseToolCallsFromText(r.text)
                    if (parsed == null) {
                        return apply(
                            AgentMachine.reduce(
                                run,
                                if (r.text.isBlank()) AgentEvent.Failed("модель вернула пустой ответ")
                                else AgentEvent.Finished(r.text),
                                config,
                            ),
                        ).run
                    }
                    if (exhausted) {
                        // На лимите тулы выключены: текст с вызовом — это ответ,
                        // а не действие. Показываем его пользователю.
                        return apply(AgentMachine.reduce(run, AgentEvent.Finished(r.text), config)).run
                    }
                    onLog("вызовы из текста (без native tools): ${parsed.joinToString(", ") { it.name }}")
                    runCalls(parsed, textAnswer = r.text, native = false, defs = defs, tools = tools, transcript = transcript) { t -> apply(t) }?.let { early -> return early }
                    // Бюджет проверяется и здесь: иначе прогон без native-tools
                    // тихо крутил бы цикл, пока не вырвется по maxToolCalls.
                    if (!AgentMachine.needsContinuation(run, config)) {
                        val reason = AgentMachine.budgetReason(run, config) ?: "шаг $step ничего не изменил"
                        onLog("останавливаюсь: $reason")
                        return apply(AgentMachine.reduce(run, AgentEvent.BudgetExhausted(reason), config)).run
                    }
                }

                is LlmResult.HttpError -> return apply(
                    AgentMachine.reduce(
                        run,
                        AgentEvent.Failed("${rag.llmLabelOrProvider()} HTTP ${r.code}: ${r.detail.take(200)}"),
                        config,
                    ),
                ).run

                is LlmResult.NetworkError ->
                    return apply(AgentMachine.reduce(run, AgentEvent.Failed("сеть: ${r.detail.take(200)}"), config)).run

                LlmResult.Empty ->
                    return apply(AgentMachine.reduce(run, AgentEvent.Failed("пустой ответ модели"), config)).run

                is LlmResult.Tools -> {
                    // Рассуждение модели рядом с вызовом — в журнал, иначе оно
                    // просто теряется между шагами.
                    if (r.text.isNotBlank()) onLog(r.text.take(NARRATION_CHARS))
                    runCalls(r.calls, textAnswer = r.text, native = true, defs = defs, tools = tools, transcript = transcript) { t -> apply(t) }?.let { early -> return early }

                    // Продолжать ли — решает редьюсер (opencode: продолжение
                    // = был локально исполненный тул).
                    if (!AgentMachine.needsContinuation(run, config)) {
                        val reason = AgentMachine.budgetReason(run, config) ?: "шаг $step ничего не изменил"
                        onLog("останавливаюсь: $reason")
                        return apply(AgentMachine.reduce(run, AgentEvent.BudgetExhausted(reason), config)).run
                    }
                }
            }
        }
    }

    // Живой контекст проекта для промпта — тот же приём, что у RAG-ответа
    // (projectLine в RagService.ask): ветка/коммит/объём. Агент должен видеть,
    // что он правит, иначе «обнови доки» превращается в правку вслепую.
    private fun projectLine(project: String): String = try {
        val info = core.project.GitTools.info(workspace.rootFile)
        val files = workspace.listFiles(limit = 2000).size
        "Проект: $project. Ветка: ${info.branch}, коммит: ${info.commit}. Файлов: $files."
    } catch (_: Exception) {
        "Проект: $project."
    }


    // Исполняет пачку вызовов и возвращает результат, если пора завершаться
    // (finish / отказ / терминальная ошибка). null = цикл продолжается.
    private suspend fun runCalls(
        calls: List<ToolCall>,
        textAnswer: String,
        native: Boolean,
        defs: List<AgentTool>,
        tools: FileTools,
        transcript: ArrayList<ChatMsg>,
        apply: (Transition) -> Transition,
    ): AgentRun? {
        if (native) {
            // Ровно то, что требует протокол: assistant с tool_calls, потом
            // сообщения role="tool" с тем же tool_call_id.
            transcript += ChatMsg("assistant", textAnswer, toolCalls = calls)
        } else {
            // Вызова в tool_calls не было, эха тоже — сообщение role="tool"
            // провайдер сочтёт ошибкой. Поэтому ответы собираем в один блок.
            transcript += ChatMsg("assistant", textAnswer)
        }
        val collected = mutableListOf<Pair<String, String>>()
        var outcome: ToolOutcome? = null
        for (call in calls) {
            outcome = execTool(call, defs, tools, currentRun(), apply)
            if (native) {
                // Результат уходит модели ВСЕГДА, даже после отказа: без пары
                // tool_call -> tool следующий запрос считается некорректным.
                transcript += ChatMsg(
                    role = "tool",
                    content = outcome.text.take(TOOL_RESULT_CHARS),
                    toolCallId = call.id,
                    name = call.name,
                )
            } else {
                collected += call.name to outcome.text.take(TOOL_RESULT_CHARS)
            }
            if (outcome.changed != null) onLog("изменено: ${outcome.changed}")
            if (outcome.finish != null || outcome.denied || outcome.fatal != null) break
        }
        if (!native && collected.isNotEmpty()) {
            transcript += ChatMsg("user", fallbackToolMessage(collected))
        }
        val o = outcome ?: return null
        if (o.finish != null) {
            return apply(AgentMachine.reduce(currentRun(), AgentEvent.Finished(o.finish), config)).run
        }
        // Отказ: редьюсер уже сделал прогон терминальным.
        if (o.denied) return currentRun()
        if (o.fatal != null) {
            return apply(AgentMachine.reduce(currentRun(), AgentEvent.Failed(o.fatal), config)).run
        }
        return null
    }

    // Прогон лежит в локальной переменной run замыкания; методы класса его не
    // видят, поэтому прокидываем его через эту ссылку.
    private var current: AgentRun? = null

    private fun currentRun(): AgentRun = current
        ?: error("currentRun() вне прогона — это баг AgentRunner")

    // Итог одного вызова тула: текст для модели + что изменилось на диске.
    private class ToolOutcome(
        val text: String,
        val changed: String? = null,
        val finish: String? = null,
        val denied: Boolean = false,
        val fatal: String? = null,
    )

    private suspend fun execTool(
        call: ToolCall,
        defs: List<AgentTool>,
        tools: FileTools,
        current: AgentRun,
        apply: (Transition) -> Transition,
    ): ToolOutcome {
        val def = defs.firstOrNull { it.name == call.name }
        if (def == null) {
            return ToolOutcome("${FAILED_PREFIX}нет тула «${call.name}». Доступны: ${defs.joinToString(", ") { it.name }}")
        }
        // Битые аргументы и пропущенные обязательные: говорим модели ЧТО
        // не так, но цикл не зацикливаем — она увидит текст ошибки.
        if (call.rejected) {
            return ToolOutcome("${FAILED_PREFIX}аргументы тула «${call.name}» — не разобранный JSON: ${call.rawArgs.take(200)}")
        }
        val missing = call.arguments.missingRequired(def.spec)
        if (missing.isNotEmpty()) {
            return ToolOutcome("${FAILED_PREFIX}в туле «${call.name}» не хватает обязательных аргументов: ${missing.joinToString(", ")}")
        }

        val started = apply(AgentMachine.reduce(current, AgentEvent.ToolsProposed(call.name, call.id, call.rawArgs), config))
        // var latest, а не val: гейт разрешений переводит прогон в Failed прямо
        // в handler, и следующий reduce обязан идти от СВЕЖЕГО снимка. От
        // устаревшего — прогон воскресился бы и потерял сам факт отказа.
        var latest = apply(AgentMachine.reduce(started.run, AgentEvent.ToolStarted(call.id), config)).run

        return try {
            val text = def.handler(call.arguments)
            val change = tools.takeLastChange()
            if (change != null) {
                latest = apply(AgentMachine.reduce(latest, AgentEvent.FileChanged(change.path, change.diff), config)).run
            }
            val done = apply(
                AgentMachine.reduce(
                    latest,
                    AgentEvent.ToolSucceeded(call.id, text.take(200), change?.diff.orEmpty()),
                    config,
                ),
            )
            // Терминал мог наступить на FileChanged (лимит файлов/вызовов).
            val failed = done.run.state as? RunState.Failed
            ToolOutcome(
                text = text,
                changed = change?.path,
                finish = if (call.name == FINISH_TOOL) text.removePrefix(FINISH_PREFIX) else null,
                fatal = failed?.error,
            )
        } catch (e: PermissionDenied) {
            // Отказ — решение пользователя, а не ошибка тула. Порядок важен:
            // сначала закрываем вызов (иначе он навсегда «выполняется»), и лишь
            // потом переводим прогон в терминал. Оба события — на свежих
            // снимках, иначе reduce от устаревшего прогон воскресит.
            val failedCall = apply(
                AgentMachine.reduce(latest, AgentEvent.ToolFailed(call.id, e.message.orEmpty()), config),
            )
            apply(AgentMachine.reduce(failedCall.run, AgentEvent.ApprovalDenied(e.path), config))
            ToolOutcome("$DENIED_PREFIX${e.message}", denied = true)
        } catch (e: Exception) {
            // Ошибка тула — это текст для модели, а не падение прогона:
            // read_file не нашёлся -> модель попробует другой путь.
            apply(AgentMachine.reduce(latest, AgentEvent.ToolFailed(call.id, e.message.orEmpty()), config))
            ToolOutcome("$FAILED_PREFIX${e.message ?: e::class.simpleName}")
        }
    }

    companion object {
        // Сколько символов результата тула уходит обратно в переписку.
        const val TOOL_RESULT_CHARS = 4000

        // Сколько хвоста переписки подкладываем агенту как контекст.
        const val SEED_HISTORY = 6

        // Рассуждение модели между вызовами тулов — в журнал, не в финал.
        const val NARRATION_CHARS = 400

        // Префиксы служебных исходов. Это НЕ текст для модели: по ним цикл
        // решает, обрывать ли прогон (отказ) или просто сообщить об ошибке.
        const val DENIED_PREFIX = "__denied__: "
        const val FAILED_PREFIX = "__failed__: "
        const val FINISH_PREFIX = "Итог: "
        const val FINISH_TOOL = "finish"
    }
}
