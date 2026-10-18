package core.agent

import core.llm.ToolSpec
import core.llm.boolOf
import core.llm.intOf
import core.llm.strOf
import core.llm.toolSchema
import core.project.GitTools
import core.support.McpToolDef
import core.support.McpToolRegistry
import kotlinx.serialization.json.JsonObject

// ===== Task 4: тулы работы с файлами проекта =====
//
// Один реестр на два транспорта: те же описания уходят и модели в tools
// (function-calling), и в MCP-сервер (tools/list + tools/call). Приём из
// Task 3: слой не имитируется — `cli agent-tools` печатает ровно то, что
// видит модель.
//
// Чтение — всегда разрешено. Запись — всегда спрашивает. Правило opencode:
// дефолт effect = ask, а «всегда» запоминает правило на проект.

// spec — единственный источник имени, описания и схемы: они уходят и модели
// (tools), и в MCP (tools/list). Флаг mutating решает, нужен ли гейт.
class AgentTool(
    val spec: ToolSpec,
    val mutating: Boolean,
    val handler: suspend (JsonObject) -> String,
) {
    val name: String get() = spec.name
    val description: String get() = spec.description
    val parameters: JsonObject get() = spec.parameters
}

class FileTools(
    val workspace: Workspace,
    // Гейт разрешений. null = спрашивать некому (headless/CI, --auto-approve).
    private val approve: (suspend (PendingChange) -> Boolean)? = null,
) {
    private val touched = linkedSetOf<String>()
    private val diffs = linkedMapOf<String, String>()

    // Последнее применённое изменение. Обработчики тулов обязаны вернуть
    // строку (так же, как в MCP), поэтому факт записи передаётся наружу
    // отдельным каналом — иначе в переписку уйдёт текст, а в отчёт пусто.
    @Volatile
    private var lastChange: PendingChange? = null

    fun takeLastChange(): PendingChange? {
        val c = lastChange
        lastChange = null
        return c
    }

    fun touchedFiles(): List<String> = touched.toList()

    // Склеенный diff прогона: результат работы и содержимое agent_runs.json.
    fun combinedDiff(): String = diffs.values.filter { it.isNotBlank() }.joinToString("\n")

    fun diffOf(path: String): String = diffs[path].orEmpty()

    // --- сборка реестра ---

    fun definitions(): List<AgentTool> = listOf(
        listFiles(), readFile(), grep(), findRefs(), gitDiff(),
        writeFile(), editFile(), finishTool(),
    )

    fun specs(): List<ToolSpec> = definitions().map { it.spec }

    fun byName(name: String): AgentTool? = definitions().firstOrNull { it.name == name }

    // Тот же реестр как MCP-сервер: файловые тулы встают рядом с CRM-тулами
    // Task 3 и видны в tools/list без всякой отдельной обвязки.
    fun mcpRegistry(defaultProject: String): McpToolRegistry =
        McpToolRegistry(defaultProject).apply {
            definitions().forEach { d ->
                register(McpToolDef(d.name, d.description, d.parameters, d.handler))
            }
        }

    // Только чтение — для HTTP-MCP на VPS. Там спрашивать разрешение некого,
    // а approve = null означал бы «записывать молча». Поэтому наружу (в сеть)
    // уходят только тулы без записи; писать может десктоп или CLI, где есть
    // человек и диалог подтверждения.
    fun readOnlyMcpRegistry(defaultProject: String): McpToolRegistry =
        McpToolRegistry(defaultProject).apply {
            definitions().filter { !it.mutating }.forEach { d ->
                register(McpToolDef(d.name, d.description, d.parameters, d.handler))
            }
        }

    // --- чтение ---

    private fun listFiles() = AgentTool(
        ToolSpec(
            "list_files",
            "Список файлов проекта. Начинай с него: без него не знаешь, что вообще есть. " +
                "prefix сужает до папки (например core/src).",
            toolSchema(
                listOf(
                    "prefix" to ("string" to "папка-фильтр; пусто = весь проект"),
                    "limit" to ("integer" to "сколько вернуть, по умолчанию 200, максимум 2000"),
                ),
            ),
        ),
        mutating = false,
    ) { args ->
        val prefix = args.strOf("prefix")
        val all = workspace.listFiles(prefix, args.intOf("limit", 200))
        if (all.isEmpty()) {
            "По фильтру «${prefix.ifBlank { "*" }}» файлов нет."
        } else {
            buildString {
                append("Файлов: ${all.size}")
                if (prefix.isNotBlank()) append(" (фильтр «$prefix»)")
                append('\n')
                all.forEach { append("- ").append(it).append('\n') }
            }
        }
    }

    private fun readFile() = AgentTool(
        ToolSpec(
            "read_file",
            "Прочитать файл проекта. from_line/to_line — диапазон строк с 1, обе границы " +
                "включительно; если не заданы, берётся весь файл. Перед правкой прочитай нужный кусок.",
            toolSchema(
                listOf(
                    "path" to ("string" to "путь относительно корня проекта"),
                    "from_line" to ("integer" to "первая строка, с 1; 0 = с начала"),
                    "to_line" to ("integer" to "последняя строка включительно; 0 = до конца"),
                ),
                listOf("path"),
            ),
        ),
        mutating = false,
    ) { args ->
        val path = args.strOf("path")
        if (!workspace.exists(path)) throw WorkspaceException("нет такого файла: $path")
        val text = workspace.readText(path, args.intOf("from_line", 0), args.intOf("to_line", 0))
        val lines = workspace.lineCount(path)
        val shown = text.lines().size
        buildString {
            append("$path — строк в файле: $lines")
            if (shown < lines) append(" (показано $shown)")
            append('\n')
            append(text)
        }
    }

    private fun grep() = AgentTool(
        ToolSpec(
            "grep",
            "Найти текст или регулярное выражение по ВСЕМ файлам проекта разом. " +
                "Ответ — список путь:строка: текст. Основной способ найти все места " +
                "использования API или компонента.",
            toolSchema(
                listOf(
                    "pattern" to ("string" to "что ищем: подстрока (McpToolRegistry) или регулярка"),
                    "prefix" to ("string" to "ограничить папкой; пусто = весь проект"),
                    "max_results" to ("integer" to "сколько совпадений вернуть, по умолчанию 40"),
                ),
                listOf("pattern"),
            ),
        ),
        mutating = false,
    ) { args ->
        val pattern = args.strOf("pattern")
        val hits = workspace.grep(pattern, args.strOf("prefix"), args.intOf("max_results", 40))
        if (hits.isEmpty()) {
            "Совпадений на «$pattern» нет. Проверь написание или расширь prefix."
        } else {
            buildString {
                append("Совпадений: ${hits.size}\n")
                hits.forEach { append(it.path).append(':').append(it.line).append(": ").append(it.text).append('\n') }
            }
        }
    }

    private fun findRefs() = AgentTool(
        ToolSpec(
            "find_references",
            "Где определён и где используется символ (класс, функция, переменная). " +
                "Сначала объявления, потом использования.",
            toolSchema(
                listOf(
                    "symbol" to ("string" to "имя символа, например CrmToolRegistry"),
                    "prefix" to ("string" to "ограничить папкой; пусто = весь проект"),
                    "max_results" to ("integer" to "сколько вернуть, по умолчанию 40"),
                ),
                listOf("symbol"),
            ),
        ),
        mutating = false,
    ) { args ->
        val symbol = args.strOf("symbol")
        val hits = workspace.findReferences(symbol, args.strOf("prefix"), args.intOf("max_results", 40))
        if (hits.isEmpty()) {
            "Символ «$symbol» не найден."
        } else {
            buildString {
                append("«$symbol»: мест ${hits.size} (объявлений ${hits.count { it.declaration }})\n")
                hits.forEach { hit ->
                    append(if (hit.declaration) "◆ " else "  ")
                    append(hit.path).append(':').append(hit.line).append(": ").append(hit.text).append('\n')
                }
            }
        }
    }

    private fun gitDiff() = AgentTool(
        ToolSpec(
            "git_diff",
            "Состояние рабочего дерева git: изменённые файлы и статистика diff. " +
                "Первым шагом, если задача про изменения в коде.",
            toolSchema(emptyList()),
        ),
        mutating = false,
    ) {
        GitTools.diffStat(workspace.rootFile)
    }

    // --- запись: всегда через подтверждение ---

    private fun writeFile() = AgentTool(
        ToolSpec(
            "write_file",
            "СОЗДАТЬ новый файл или ПОЛНОСТЬЮ перезаписать существующий. content — " +
                "итоговый текст целиком. Перед перезаписью обязан прочитать файл через read_file.",
            toolSchema(
                listOf(
                    "path" to ("string" to "путь файла относительно корня проекта"),
                    "content" to ("string" to "итоговое содержимое файла целиком"),
                ),
                listOf("path", "content"),
            ),
        ),
        mutating = true,
    ) { args ->
        gateAndApply(workspace.planWrite(args.strOf("path"), args.strOf("content")))
    }

    private fun editFile() = AgentTool(
        ToolSpec(
            "edit_file",
            "ТОЧЕЧНАЯ правка: заменить old_string на new_string. Предпочтительнее write_file — " +
                "не затрагивает остальной файл. old_string обязан дословно совпадать с прочитанным " +
                "(включая отступы) и встречаться ровно один раз, если не указан replace_all.",
            toolSchema(
                listOf(
                    "path" to ("string" to "путь файла относительно корня проекта"),
                    "old_string" to ("string" to "фрагмент, который заменяем — ровно как в файле"),
                    "new_string" to ("string" to "новый фрагмент вместо него"),
                    "replace_all" to ("boolean" to "заменить все вхождения; по умолчанию false"),
                ),
                listOf("path", "old_string", "new_string"),
            ),
        ),
        mutating = true,
    ) { args ->
        gateAndApply(
            workspace.planEdit(
                args.strOf("path"),
                args.strOf("old_string"),
                args.strOf("new_string"),
                args.boolOf("replace_all"),
            ),
        )
    }

    private suspend fun gateAndApply(plan: Workspace.PlannedEdit): String {
        val change = PendingChange(
            path = plan.path,
            action = plan.action,
            diff = plan.diff,
            replacements = plan.replacements,
            summary = if (plan.action == "create") {
                "создать ${plan.path} (${plan.after.lines().size} строк)"
            } else {
                "${plan.action} ${plan.path}: ${plan.replacements} замен"
            },
        )
        // null approve = headless-режим: спрашивать некому (--auto-approve, CI).
        if (!(approve?.invoke(change) ?: true)) throw PermissionDenied(change.path)
        workspace.apply(plan)
        touched += plan.path
        diffs[plan.path] = plan.diff
        lastChange = change
        return buildString {
            append("Записано: ${plan.path} (${plan.action}, замен: ${plan.replacements})\n")
            append(plan.diff.take(DIFF_IN_TOOL_CHARS))
            if (plan.diff.length > DIFF_IN_TOOL_CHARS) {
                append("\n… диф обрезан в ответе, полный — в отчёте прогона")
            }
        }
    }

    private fun finishTool() = AgentTool(
        ToolSpec(
            "finish",
            "Цель достигнута — закончить работу. Вызывай ПОСЛЕДНИМ, когда задача реально " +
                "сделана. В summary — что сделано для пользователя.",
            toolSchema(
                listOf(
                    "summary" to ("string" to "итог для пользователя: что нашёл или сделал"),
                    "changes" to ("array" to "пути, которые изменил или создал"),
                ),
                listOf("summary"),
            ),
        ),
        mutating = false,
    ) { args ->
        FINISH_PREFIX + args.strOf("summary")
    }

    private companion object {
        // Сколько дифа отдавать обратно модели: хватает, чтобы она увидела
        // результат, но не раздувает следующий запрос.
        const val DIFF_IN_TOOL_CHARS = 1200
        const val FINISH_PREFIX = "Итог: "
    }
}

// Отказ по разрешению. Особый тип: по нему цикл останавливает прогон, а не
// возвращает модели текст (opencode: decline обрывает step) — иначе модель
// повторит тот же запрещённый вызов по кругу.
class PermissionDenied(val path: String) : RuntimeException("изменение $path отклонено")
