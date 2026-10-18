package core.agent

import core.project.GitTools
import java.io.File
import kotlinx.serialization.Serializable

// ===== Task 5: агент, который держит документацию в соответствии с кодом =====
//
// Реальная задача, которую это автоматизирует: после merge документация
// расходится с кодом (забытый эндпоинт в схеме API, переименованная команда
// в README, новый флаг без строки в справке). Человек берётся за это в
// последнюю очередь, и через месяц доки уже врут.
//
// Решение — не «скрипт с регулярками», а агент Task 4 с ОДНИМ жёстким
// ограничением: писать разрешено ТОЛЬКО в документацию. Всё остальное
// (какие файлы читать, что именно дописать, как сформулировать) решает модель.
//
// Почему ограничение важнее промпта: просьба «обновляй документацию» модель
// читает как «можно и код поправить», и через пару шагов чинит баг в
// обработчике вместе с README. Здесь это невозможно структурно — гейт отдаёт
// Deny, и код остаётся нетронутым.

// Что считается документацией. Всё остальное для записи закрыто.
private val DOC_EXT = setOf("md", "mdx", "rst", "adoc")

// Расширения кода: по ним решаем, «изменился код — пора обновлять доки».
private val CODE_EXT = setOf(
    "kt", "kts", "java", "scala", "py", "js", "jsx", "ts", "tsx", "go", "rs", "rb", "php",
    "c", "h", "cpp", "hpp", "cs", "swift", "sql", "vue", "svelte", "sh", "bash",
)

// Конфиги, зависимости, ресурсы: ни код, ни документация.
private val NEUTRAL_EXT = setOf(
    "yml", "yaml", "json", "toml", "properties", "xml", "gradle", "lock", "txt", "csv",
    "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "zip", "gz", "ttf", "woff", "woff2",
    "mp4", "mov", "pdf", "exe", "jar", "class",
)

/** Куда попал файл из diff. Решает, запускать ли агента вообще. */
enum class DocFileKind(val label: String) {
    Code("код"),
    Docs("документация"),
    Test("тесты"),
    Neutral("конфиги и ресурсы"),
    Unknown("непонятно"),
}

/** Один файл из изменений с вердиктом классификации. */
@Serializable
data class ChangedFileInfo(
    val path: String,
    val kind: String,
    val lines: Int = 0,
)

/**
 * Что поменялось между двумя точками git.
 *
 * Пустой [codeFiles] — главный сигнал: если код не менялся, докам нечего
 * обновлять и агент не запускается. Иначе каждая ночная синхронизация
 * открывала бы PR «документация не требует изменений».
 */
@Serializable
data class DocsChangeSet(
    val base: String = "",
    val head: String = "",
    val codeFiles: List<ChangedFileInfo> = emptyList(),
    val docFiles: List<ChangedFileInfo> = emptyList(),
    val otherFiles: List<ChangedFileInfo> = emptyList(),
    val allFiles: Int = 0,
    val truncated: Boolean = false,
) {
    /** Нужно ли вообще запускать агента. */
    val worthRunning: Boolean get() = codeFiles.isNotEmpty()

    val note: String
        get() = when {
            allFiles == 0 -> "изменений нет"
            codeFiles.isEmpty() && docFiles.isEmpty() -> "изменились только конфиги и ресурсы (файлов: $allFiles)"
            codeFiles.isEmpty() -> "изменились только сами документы (файлов: $allFiles) — доки правят не кодом"
            else -> "кода: ${codeFiles.size}, доков: ${docFiles.size}, прочего: ${otherFiles.size}"
        }
}

/** Изменённый документ: путь + новое содержимое (чтобы Action открыл PR). */
@Serializable
data class DocFileUpdate(
    val path: String,
    val content: String,
)

/** Итог прогона docs-sync — то, что уходит в PR и в отчёт. */
@Serializable
data class DocsSyncResult(
    val project: String = "",
    val base: String = "",
    val head: String = "",
    // Изменения в коде, ради которых всё затевалось.
    val changedCodeFiles: List<String> = emptyList(),
    // Документы, к которым агент реально прикоснулся.
    val updatedFiles: List<String> = emptyList(),
    // Полные содержимое обновлённых файлов: VPS отдаёт их по HTTP, а Action
    // применяет у себя и открывает PR. Так рабочий клон на VPS остаётся
    // нетронутым — агент пишет в отдельную копию.
    val updates: List<DocFileUpdate> = emptyList(),
    val diff: String = "",
    val summary: String = "",
    val prTitle: String = "",
    val prBody: String = "",
    // Кандидаты-доки, найденные по изменённым файлам. Нужны в dry-run:
    // это весь полезный вывод без LLM.
    val candidateDocs: List<String> = emptyList(),
    val changeNote: String = "",
    val skipped: Boolean = false,
    val skipReason: String = "",
    val warning: String = "",
    val runId: String = "",
    val steps: Int = 0,
    val error: String = "",
) {
    val hasUpdates: Boolean get() = updates.isNotEmpty()
}

object DocsSync {

    /**
     * Лимиты прогона docs-sync.
     *
     * У агента Task 4 дефолтных лимитов нет — человек рядом и остановит сам.
     * Здесь наоборот: запуск ночной, без человека, поэтому границы обязательны,
     * иначе прогон не ограничен ничем. Значения те же, что были дефолтными у
     * агента до отмены лимитов.
     */
    const val MAX_STEPS = 16
    const val MAX_FILES_CHANGED = 6

    /** Классификация одного пути. Чистая функция — покрывается verify-docs. */
    fun classify(path: String): DocFileKind {
        val p = path.trim().replace('\\', '/').trimStart('/')
        if (p.isEmpty()) return DocFileKind.Unknown
        val file = p.substringAfterLast('/')
        if (file.isEmpty()) return DocFileKind.Unknown
        val lower = file.lowercase()
        val stem = lower.substringBeforeLast('.', lower)
        val ext = lower.substringAfterLast('.', "")
        // Тесты ловим раньше кода: src/test/Foo.kt — это тест, и его правка
        // не двигает пользовательскую документацию.
        if (ext in CODE_EXT && (stem.contains("test") || stem.contains("spec") || p.contains("/test/"))) {
            return DocFileKind.Test
        }
        if (stem == "readme") return DocFileKind.Docs
        if (stem.startsWith("readme.") || stem.startsWith("changelog")) return DocFileKind.Docs
        if (ext.isEmpty()) return DocFileKind.Neutral // Dockerfile, Makefile, LICENSE
        if (ext in DOC_EXT) return DocFileKind.Docs
        if (ext in CODE_EXT) return DocFileKind.Code
        if (ext in NEUTRAL_EXT) return DocFileKind.Neutral
        return DocFileKind.Unknown
    }

    /** Правда ли это путь документации (для гейта записи). */
    fun isDocPath(path: String): Boolean = classify(path) == DocFileKind.Docs

    /**
     * Раскладывает файлы по трём ведрам. limit ограничивает каждое ведро:
     * большие мержи (500 файлов) агент всё равно не осилит за лимит шагов.
     */
    fun classifyAll(files: List<String>, limit: Int = 20): DocsChangeSet {
        val code = mutableListOf<ChangedFileInfo>()
        val docs = mutableListOf<ChangedFileInfo>()
        val other = mutableListOf<ChangedFileInfo>()
        files.forEach { f ->
            val kind = classify(f)
            val info = ChangedFileInfo(f, kind.name)
            when (kind) {
                DocFileKind.Code -> if (code.size < limit) code += info
                DocFileKind.Docs -> if (docs.size < limit) docs += info
                else -> if (other.size < limit) other += info
            }
        }
        return DocsChangeSet(
            codeFiles = code,
            docFiles = docs,
            otherFiles = other,
            allFiles = files.size,
            truncated = files.size > limit * 3,
        )
    }

    /**
     * Собирает изменения из git. base == null — рабочее дерево
     * (`git diff --name-only HEAD`), иначе трёхточечный diff base...head.
     * Любая ошибка git не роняет задачу: откатываемся на `git status`.
     */
    fun changedFiles(dir: File, base: String?, head: String, limit: Int = 20): DocsChangeSet {
        val names: List<String> = try {
            if (!base.isNullOrBlank()) {
                GitTools.changedFiles(dir, base, head, limit = 300)
            } else {
                gitLines(dir, "diff", "--name-only", head)
            }
        } catch (_: Exception) {
            gitLines(dir, "status", "--porcelain").map { it.substringAfter(' ').trim() }
        }.filter { it.isNotBlank() }
        val stats = if (!base.isNullOrBlank()) numstat(dir, base, head) else emptyMap()
        val set = classifyAll(names, limit)
        // Строки из --numstat приклеиваем после классификации: classifyAll
        // принимает пути, чтобы им можно было пользоваться и без git.
        fun withLines(list: List<ChangedFileInfo>) = list.map { it.copy(lines = stats[it.path] ?: 0) }
        return set.copy(
            base = base.orEmpty(),
            head = head,
            allFiles = names.size,
            codeFiles = withLines(set.codeFiles),
            docFiles = withLines(set.docFiles),
            otherFiles = withLines(set.otherFiles),
        )
    }

    /**
     * Кандидаты-доки: какие markdown-файлы проекта вообще могут понадобиться.
     *
     * Порядок по убыванию релевантности:
     *   1) доки, где встречается имя изменённого файла — самые точные;
     *   2) всё внутри docs/ — общее правило проекта;
     *   3) корневой README — почти всегда описывает точки входа.
     *
     * Агент с лимитом в 16 шагов обязан начать с самого вероятного кандидата,
     * а не с docs/00-overview.md.
     */
    fun candidateDocs(ws: Workspace, changeSet: DocsChangeSet, limit: Int = 8): List<String> {
        val docFiles = ws.listFiles(limit = 2000).filter { isDocPath(it) }
        if (docFiles.isEmpty()) return emptyList()
        val needles = changeSet.codeFiles.map { symbolHint(it.path) }.filter { it.isNotBlank() }.distinct()
        val pattern = if (needles.isEmpty()) null else needles.joinToString("|") { Regex.escape(it) }
        val scored = docFiles.map { path ->
            val name = path.substringAfterLast('/').lowercase()
            var score = 0
            if (name.startsWith("readme")) score += 3
            if (path.startsWith("docs/") || path.startsWith("doc/")) score += 2
            val mentions = pattern != null && ws.grep(pattern, path, maxResults = 200).isNotEmpty()
            if (mentions) score += MENTION_WEIGHT
            Triple(score, mentions, path)
        }
        val byPath = scored.sortedWith(
            compareByDescending<Triple<Int, Boolean, String>> { it.first }.thenBy { it.third },
        )
        // Два уровня: сначала документы, где изменённый символ РЕАЛЬНО
        // упоминается, и только если их мало — остальные доки проекта.
        // Иначе агент на лимите в 16 шагов перелистывает docs/ в поисках
        // упоминания, которого там нет.
        val mentioned = byPath.filter { it.second }.take(limit).map { it.third }
        if (mentioned.size >= limit) return mentioned
        val filler = byPath.filterNot { it.second }.filter { it.first > 0 }
            .take(limit - mentioned.size).map { it.third }
        return mentioned + filler
    }

    // Вес упоминания символа. Заведомо больше бонуса README (3) и папки
    // docs/ (2): документ с упоминанием всегда важнее «просто документации».
    private const val MENTION_WEIGHT = 5

    /**
     * Имя-подсказка из пути файла кода: AgentRunner.kt -> "AgentRunner",
     * src/api/routes.ts -> "routes". По нему grep ищет упоминания в доках.
     * Слишком общие имена отбрасываются: по ним совпадёт всё подряд.
     */
    fun symbolHint(path: String): String {
        val stem = path.substringAfterLast('/').substringBeforeLast('.', "")
        if (stem.isEmpty()) return ""
        if (stem.lowercase() in GENERIC_STEMS) return ""
        return stem
    }

    private val GENERIC_STEMS = setOf("index", "main", "utils", "util", "app", "common", "config", "build")

    private fun gitLines(dir: File, vararg args: String): List<String> = try {
        val proc = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0) {
            out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun numstat(dir: File, base: String, head: String): Map<String, Int> = try {
        val proc = ProcessBuilder(
            listOf("git", "-C", dir.absolutePath, "diff", "--numstat", "$base...$head"),
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0) {
            out.lines().mapNotNull { parseNumstatLine(it)?.let { s -> s.path to (s.added + s.removed) } }.toMap()
        } else {
            emptyMap()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Строка `--numstat`: "12\t3\tpath/to/File.kt". Переименования дают "-\t-\told => new". */
    fun parseNumstatLine(line: String): Numstat? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        var path = parts[2].trim()
        if (" => " in path) path = path.substringAfter(" => ")
        if (path.isEmpty()) return null
        return Numstat(path, parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
    }

    /** Строка `--numstat` в разобранном виде: путь + сколько строк затронуто. */
    data class Numstat(val path: String, val added: Int, val removed: Int)

    /**
     * Готовит клон к сравнению `base...head`.
     *
     * Клон проекта делается с `--depth 1` (экономно для индексации), но
     * трёхточечный diff без истории не собрать: нужен коммит, на который
     * указывает base. Поэтому при недостающей истории доком, а затем
     * `fetch --depth=200`.
     *
     * Ничего не роняет: возвращает строку-предупреждение (пусто = всё в
     * порядке). Иначе ночной workflow падал бы из-за клона, а не из-за
     * самой задачи.
     */
    fun prepareRepo(dir: File, base: String?, authToken: String? = null): String {
        if (!base.isNullOrBlank() && !refExists(dir, base)) {
            git(dir, authToken, "fetch", "--unshallow", "origin")
            if (!refExists(dir, base)) git(dir, authToken, "fetch", "--depth=200", "origin")
            if (!refExists(dir, base)) {
                // Коммита нет и после fetch: либо base ошибочен, либо он старше
                // окна истории. Diff в этом случае посчитается по рабочему
                // дереву — говорим об этом прямо, а не молча.
                return "⚠ в клоне нет коммита $base — diff считается по рабочему дереву " +
                    "(проверьте base: он должен существовать в репозитории и быть не старше ~200 коммитов)"
            }
        }
        val (code, out) = git(dir, authToken, "pull", "--ff-only")
        return if (code != 0) "⚠ git pull не удался: ${out.take(120)}" else ""
    }

    private fun refExists(dir: File, ref: String): Boolean =
        git(dir, null, "cat-file", "-e", "$ref^{commit}").first == 0

    /** origin удалённого репозитория — чтобы CLI мог передать repoUrl на VPS. */
    fun remoteUrl(dir: File): String? =
        git(dir, null, "remote", "get-url", "origin").takeIf { it.first == 0 }?.second?.takeIf { it.isNotBlank() }

    private fun git(dir: File, authToken: String?, vararg args: String): Pair<Int, String> = try {
        val head = if (authToken.isNullOrBlank()) emptyList() else listOf("-c", "http.extraHeader=AUTHORIZATION: bearer $authToken")
        val proc = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + head + args)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (!proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            1 to "timeout"
        } else {
            proc.exitValue() to out
        }
    } catch (e: Exception) {
        1 to (e.message ?: "git error")
    }
}

/**
 * Гейт записи «только документация».
 *
 * Отдельный класс, а не набор правил в PermissionStore: правила живут в
 * общем файле настроек проекта и переживают прогон, а ограничение
 * «только доки» — свойство самой задачи docs-sync. Держать его в общем
 * списке значило бы либо навсегда запретить агенту Task 4 править код,
 * либо тащить временное правило в постоянные настройки.
 *
 * ask = null: спрашивать в CI некому, поэтому решение считается автоматом —
 * но автомат здесь строгий, а не «разрешено всё».
 */
class DocsOnlyGate(private val masks: List<String> = DOC_MASKS) :
    PermissionGate(PermissionStore(), ask = null) {

    override suspend fun check(project: String, change: PendingChange): PermissionVerdict {
        val allowed = masks.any { globMatch(it, change.path) || globMatch(it, change.path.substringAfterLast('/')) }
        return if (allowed) {
            PermissionVerdict(change.path, PermissionEffect.Allow.name)
        } else {
            PermissionVerdict(change.path, PermissionEffect.Deny.name)
        }
    }

    companion object {
        /** Маски, которые гейт считает документацией. */
        val DOC_MASKS = listOf("*.md", "*.mdx", "docs/*", "doc/*")

        /** Человеческое объяснение отказа — попадает в ошибку прогона и в PR. */
        fun denyReason(path: String): String =
            "docs-sync правит только документацию (*.md, docs/**). " +
                "Файл «$path» — не документация, он не тронут."
    }
}

/** Сборка заголовка и тела PR из результата. Чистые функции — проверяемы офлайн. */
object DocsSyncPr {

    fun title(changed: Int, project: String): String =
        "docs: синхронизировать документацию с кодом ($changed ${plural(changed)})"

    private fun plural(n: Int): String {
        val mod100 = n % 100
        if (mod100 in 11..14) return "изменений"
        return when (n % 10) {
            1 -> "изменение"
            2, 3, 4 -> "изменения"
            else -> "изменений"
        }
    }

    fun body(result: DocsSyncResult): String = buildString {
        appendLine("Автоправка документации по результатам изменения кода.")
        appendLine()
        appendLine("Изменённые файлы кода (${result.changedCodeFiles.size}):")
        result.changedCodeFiles.take(20).forEach { appendLine("- `$it`") }
        if (result.changedCodeFiles.size > 20) appendLine("- … и ещё ${result.changedCodeFiles.size - 20}")
        appendLine()
        if (result.summary.isNotBlank()) {
            appendLine("Что сделал агент:")
            appendLine(result.summary)
            appendLine()
        }
        appendLine("Обновлённые документы:")
        result.updatedFiles.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("<details><summary>diff</summary>")
        appendLine()
        appendLine("```diff")
        append(result.diff.take(PR_DIFF_CHARS).trimEnd())
        appendLine()
        appendLine("```")
        appendLine("</details>")
        if (result.warning.isNotBlank()) {
            appendLine()
            appendLine("> ⚠️ ${result.warning}")
        }
        appendLine()
        appendLine("---")
        appendLine("Сгенерировано docs-sync агентом (Week 6, Task 5). Изменены только документы.")
    }

    private const val PR_DIFF_CHARS = 20_000
}