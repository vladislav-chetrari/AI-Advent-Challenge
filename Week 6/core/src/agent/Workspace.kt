package core.agent

import java.io.File

// ===== Task 4: рабочая область агента =====
//
// Единственное место, где агент касается диска. Здесь — клетка (path jail),
// снапшоты до записи и генерация unified diff для подтверждения.
//
// Правило из opencode: путь проверяется по canonicalPath, а не по вхождению
// ".." — иначе симлинк или "a/../../etc" проскакивают. Снапшот делается ДО
// записи, поэтому любую правку можно откатить (opencode так не умеет, но
// демо с «я сломал документ» становится безобидным).

open class WorkspaceException(msg: String) : RuntimeException(msg)

// Отказ по безопасности. Отдельный тип, чтобы вызывающий мог отличить
// «нельзя» от «сломалось» и НЕ предлагать модели повторить тот же вызов.
class WorkspaceDenied(msg: String) : WorkspaceException(msg)

const val MAX_FILE_BYTES = 300_000L
const val MAX_FILE_CHARS = 60_000
const val MAX_GREP_RESULTS = 200

// Расширения, которые читаем как текст. Всё остальное (картинки, базы,
// бинарники) — мимо, иначе модель получит мусор вместо кода.
private val TEXT_EXT = setOf(
    "kt", "kts", "java", "scala", "py", "js", "jsx", "ts", "tsx", "go", "rs", "rb", "php",
    "c", "h", "cpp", "hpp", "cs", "swift", "kt", "sql", "sh", "bash", "zsh", "yml", "yaml",
    "json", "toml", "xml", "html", "css", "scss", "vue", "svelte", "gradle", "properties",
    "md", "markdown", "txt", "adoc", "rst", "ini", "cfg", "conf", "env", "dockerfile",
)

// Папки, куда агент не ходит ни читать, ни писать.
private val DENY_DIRS = setOf(
    ".git", "build", ".gradle", ".idea", "out", "dist", "node_modules",
    "venv", ".venv", "__pycache__", "target", ".amper", ".DS_Store",
)

class Workspace(val root: File) {
    val rootFile: File = root.canonicalFile

    init {
        require(rootFile.isDirectory) { "нет такой папки проекта: $root" }
    }

    // --- клетка ---

    // Резолв относительного пути проекта с проверкой границ.
    // Бросает WorkspaceDenied — вызывающий покажет это модели текстом,
    // а не уронит прогон.
    fun resolve(rel: String): File {
        val raw = rel.trim().replace('\\', '/')
        require(raw.isNotEmpty()) { "пустой путь" }
        require(!raw.startsWith("/")) { "абсолютный путь запрещён: $rel (работаем от корня проекта)" }
        require(!WINDOWS_ABS.containsMatchIn(raw)) { "абсолютный путь запрещён: $rel" }
        require(".." !in raw.split('/')) { "выход за корень проекта запрещён: $rel" }
        val f = File(rootFile, raw).canonicalFile
        require(f == rootFile || f.path.startsWith(rootFile.path + File.separator)) {
            "выход за корень проекта запрещён: $rel"
        }
        val rel2 = relative(f)
        if (rel2.split('/').any { it in DENY_DIRS }) {
            throw WorkspaceDenied("служебная папка «$rel» недоступна агенту (${DENY_DIRS.joinToString(", ")})")
        }
        return f
    }

    // Относительный путь с '/' — как его видит модель.
    fun relative(f: File): String =
        runCatching {
            rootFile.toPath().relativize(f.canonicalFile.toPath()).toString().replace('\\', '/')
        }.getOrElse { f.name }

    fun norm(rel: String): String = relative(resolve(rel))

    // --- чтение ---

    // Список файлов проекта. git ls-files там, где есть git (быстро и честно),
    // иначе обход с тем же deny-списком, что и у GitTools.listFiles.
    fun listFiles(prefix: String = "", limit: Int = 300): List<String> {
        val p = prefix.trim().trim('/')
        val fromGit = gitLsFiles()
        val base = if (fromGit.isNotEmpty()) fromGit else walkFiles()
        return base.filter { p.isEmpty() || it.startsWith(p) }.take(limit.coerceIn(1, 2000))
    }

    private fun gitLsFiles(): List<String> = try {
        val proc = ProcessBuilder(listOf("git", "-C", rootFile.absolutePath, "ls-files"))
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0) {
            out.lines().map { it.trim().replace('\\', '/') }.filter { it.isNotEmpty() }
        } else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun walkFiles(): List<String> = try {
        rootFile.walkTopDown()
            .onEnter { d -> d.name !in DENY_DIRS }
            .filter { it.isFile && it.name !in DENY_DIRS }
            .map { relative(it) }
            .take(2000)
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    fun exists(rel: String): Boolean = try {
        resolve(rel).isFile
    } catch (_: Exception) {
        false
    }

    fun sizeOf(rel: String): Long = resolve(rel).length()

    // Текст файла с необязательным диапазоном строк (1-based, включительно).
    // Диапазон нужен агенту, чтобы не тащить в контекст файл целиком.
    fun readText(rel: String, fromLine: Int = 0, toLine: Int = 0): String {
        val f = resolve(rel)
        if (!f.isFile) throw WorkspaceException("нет такого файла: $rel")
        if (f.length() > MAX_FILE_BYTES) throw WorkspaceException("файл слишком большой (${f.length()} Б): $rel")
        val text = try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw WorkspaceException("не читается как текст: $rel (${e.message})")
        }
        if (text.contains('\u0000')) throw WorkspaceException("похоже на бинарный файл: $rel")
        val lo = fromLine.coerceAtLeast(0)
        val hi = if (toLine > 0) toLine else Int.MAX_VALUE
        if (lo == 0 && hi == Int.MAX_VALUE) return text.take(MAX_FILE_CHARS)
        val lines = text.lines()
        if (lo > lines.size) return "" // пусто, а не ошибка: модель запросила хвост файла
        val body = lines.subList(lo - 1, minOf(hi, lines.size)).joinToString("\n")
        return body.take(MAX_FILE_CHARS)
    }

    // Размер файла в строках — чтобы модель знала, какие границы читать.
    fun lineCount(rel: String): Int = try {
        resolve(rel).readLines().size
    } catch (_: Exception) {
        0
    }

    // Поиск подстроки (или regex, если он компилируется) по нескольким файлам.
    // Основной инструмент сценария «найти все места, где используется компонент».
    fun grep(pattern: String, prefix: String = "", maxResults: Int = 40): List<GrepHit> {
        val needle = pattern.trim()
        if (needle.isEmpty()) return emptyList()
        // Модель присылает и подстроку ("McpToolRegistry"), и регулярку
        // ("fun\\s+\\w+"). Регулярку компилируем; если не собралась — ищем
        // подстрокой, чтобы кривой паттерн не ломал весь инструмент.
        val re = runCatching { Regex(needle, RegexOption.IGNORE_CASE) }.getOrNull()
        val lower = needle.lowercase()
        val matches: (String) -> Boolean = { line ->
            re?.containsMatchIn(line) ?: line.lowercase().contains(lower)
        }
        val limit = maxResults.coerceIn(1, MAX_GREP_RESULTS)
        val hits = mutableListOf<GrepHit>()
        for (rel in listFiles(prefix, limit = 2000)) {
            if (hits.size >= limit) break
            if (!isTextPath(rel)) continue
            val f = try { resolve(rel) } catch (_: Exception) { continue }
            if (!f.isFile || f.length() > MAX_FILE_BYTES) continue
            val lines = try { f.readLines() } catch (_: Exception) { continue }
            lines.forEachIndexed { i, line ->
                if (hits.size >= limit) return@forEachIndexed
                if (matches(line)) hits += GrepHit(rel, i + 1, line.trim().take(300))
            }
        }
        return hits
    }

    // Ссылки на символ: объявления плюс использования. Грубо, но для цели
    // «где это используется» хватает, а RAG-доки потом дают смысл.
    fun findReferences(symbol: String, prefix: String = "", maxResults: Int = 40): List<GrepHit> {
        val s = symbol.trim()
        if (s.isEmpty()) return emptyList()
        val limit = maxResults.coerceIn(1, MAX_GREP_RESULTS)
        val decl = Regex("""\b(class|interface|object|fun|val|var|def|type|enum|struct|const)\s+$s\b""")
        return listFiles(prefix, limit = 2000)
            .asSequence()
            .filter { isTextPath(it) }
            .flatMap { rel ->
                val f = try { resolve(rel) } catch (_: Exception) { null } ?: return@flatMap emptySequence<GrepHit>()
                if (!f.isFile || f.length() > MAX_FILE_BYTES) return@flatMap emptySequence<GrepHit>()
                val lines = try { f.readLines() } catch (_: Exception) { return@flatMap emptySequence<GrepHit>() }
                lines.mapIndexedNotNull { i, line ->
                    if (s !in line) null else GrepHit(rel, i + 1, line.trim().take(300), declaration = decl.containsMatchIn(line))
                }.asSequence()
            }
            .sortedWith(compareByDescending<GrepHit> { it.declaration }.thenBy { it.path })
            .take(limit)
            .toList()
    }

    // --- запись ---

    // Снапшот ДО изменения — основа отката. Всегда ставим метку:
    // файла не было = метка, а не «отсутствие снапшота». Иначе откат не может
    // отличить «прогон создал файл» от «снапшот потерялся» — и удалит живой файл.
    fun snapshot(rel: String, runId: String, snapshotDir: File) {
        val f = resolve(rel)
        val dest = File(snapshotDir, runId).resolve(relative(f))
        if (dest.exists()) return // первый снапшот и есть оригинал
        dest.parentFile?.mkdirs()
        if (f.isFile) {
            f.copyTo(dest, overwrite = false)
        } else {
            dest.writeText(NEW_FILE_MARKER, Charsets.UTF_8)
        }
    }

    // Отличаем «файл создан прогоном» от «файл существовал».
    fun wasNewInRun(rel: String, runId: String, snapshotDir: File): Boolean =
        try {
            val snap = File(snapshotDir, runId).resolve(rel)
            snap.isFile && snap.readText(Charsets.UTF_8) == NEW_FILE_MARKER
        } catch (_: Exception) {
            false
        }

    fun hadOriginal(rel: String, runId: String, snapshotDir: File): Boolean =
        File(snapshotDir, runId).resolve(rel).isFile

    fun originalText(rel: String, runId: String, snapshotDir: File): String? =
        try { File(snapshotDir, runId).resolve(rel).let { if (it.isFile) it.readText() else null } } catch (_: Exception) { null }

    // --- запись: plan -> (подтверждение) -> apply ---
    //
    // Порядок как в opencode: сначала считаем, что именно изменится, и только
    // потом спрашиваем разрешение и пишем. Пользователь видит настоящий diff,
    // а не обещание «я сейчас обновлю документацию».

    // Запланированное изменение: before/after + готовый unified diff.
    data class PlannedEdit(
        val path: String,
        val action: String, // create | rewrite | edit
        val before: String,
        val after: String,
        val diff: String,
        val replacements: Int = 1,
    )

    // Полная перезапись/создание — без записи на диск.
    fun planWrite(rel: String, content: String): PlannedEdit {
        val f = resolve(rel)
        val existed = f.isFile
        if (existed && f.length() > MAX_FILE_BYTES) throw WorkspaceException("файл слишком большой: $rel")
        require(content.length <= MAX_FILE_CHARS) {
            "слишком большое содержимое (${content.length} символов, лимит $MAX_FILE_CHARS)"
        }
        val before = if (existed) f.readText(Charsets.UTF_8) else ""
        return PlannedEdit(
            path = relative(f),
            action = if (existed) "rewrite" else "create",
            before = before,
            after = content,
            diff = unifiedDiff(relative(f), before, content),
        )
    }

    // Точечная замена old_string -> new_string. Безопаснее полной перезаписи:
    // модель обязана показать точный фрагмент, который видела в read_file.
    fun planEdit(rel: String, oldString: String, newString: String, replaceAll: Boolean = false): PlannedEdit {
        val f = resolve(rel)
        require(f.isFile) { "нет такого файла для правки: $rel (для нового файла используй write_file)" }
        require(oldString.isNotEmpty()) { "old_string пустой — нечего заменять" }
        val before = f.readText(Charsets.UTF_8)
        val occurrences = before.split(oldString).size - 1
        require(occurrences > 0) {
            "old_string не найден в $rel — прочитай файл ещё раз и повтори с точным фрагментом из него"
        }
        require(replaceAll || occurrences == 1) {
            "old_string встречается в $rel $occurrences раз — уточни фрагмент (соседние строки) или поставь replace_all=true"
        }
        val after = if (replaceAll) before.replace(oldString, newString) else before.replaceFirst(oldString, newString)
        return PlannedEdit(
            path = relative(f),
            action = "edit",
            before = before,
            after = after,
            diff = unifiedDiff(relative(f), before, after),
            replacements = if (replaceAll) occurrences else 1,
        )
    }

    // Запись запланированного изменения. Снапшот оригинала — первым делом.
    fun apply(plan: PlannedEdit): String {
        val f = resolve(plan.path)
        snapshot(plan.path, runIdForSnapshot, snapshotDir)
        f.parentFile?.mkdirs()
        f.writeText(plan.after, Charsets.UTF_8)
        return plan.diff
    }

    // Идентификатор прогона для снапшотов. Задаётся снаружи (AgentRunner),
    // чтобы Workspace не знал про прогоны.
    var runIdForSnapshot: String = "manual"
    var snapshotDir: File = File(System.getProperty("java.io.tmpdir"), "ai-advent-snapshots")

    // Откат прогона: всё, что он менял, возвращается к состоянию на момент
    // первого снапшота. Файл удаляется ТОЛЬКО если снапшот явно помечен как
    // «создан прогоном» — потерянный снапшот не имеет права удалять файл.
    fun revert(rel: String, runId: String, snapshotDir: File): Boolean {
        val snap = File(snapshotDir, runId).resolve(rel)
        if (!snap.isFile) return false
        val f = try { resolve(rel) } catch (_: Exception) { return false }
        return if (wasNewInRun(rel, runId, snapshotDir)) {
            f.delete()
        } else {
            snap.parentFile?.mkdirs()
            snap.copyTo(f, overwrite = true)
            true
        }
    }

    companion object {
        // Метка снапшота для файла, которого до прогона не было.
        const val NEW_FILE_MARKER = "\u0000week6:new-file"

        private val WINDOWS_ABS = Regex("^[A-Za-z]:/")
        // Расширение текста; без расширения смотрим на имя (Dockerfile, Makefile).
        fun isTextPath(rel: String): Boolean {
            val file = rel.substringAfterLast('/').lowercase()
            if (file in setOf("dockerfile", "makefile", "readme", "license")) return true
            val ext = file.substringAfterLast('.', "")
            return ext.isEmpty() || ext in TEXT_EXT
        }
    }
}

data class GrepHit(
    val path: String,
    val line: Int,
    val text: String,
    val declaration: Boolean = false,
)

// ===== unified diff =====
//
// Нужен ДО записи (показать пользователю, что именно агент собирается сделать)
// и ПО (отчёт о прогоне). Работает без git — проекты часто не под git.
// LCS на строках: файлы в пределах MAX_FILE_CHARS, поэтому квадратовка не нужна.

fun unifiedDiff(path: String, before: String, after: String, context: Int = 3): String {
    if (before == after) return ""
    val a = before.split("\n")
    val b = after.split("\n")
    val ops = lcsOps(a, b)
    val header = "--- a/$path\n+++ b/$path\n"
    val body = StringBuilder()
    var i = 0
    while (i < ops.size) {
        if (ops[i].first != ' ') {
            // Нашли изменение — склеиваем в один хунк с отступом контекста.
            var start = i
            var ctx = 0
            while (start > 0 && ops[start - 1].first == ' ' && ctx < context) { start--; ctx++ }
            var end = i
            var back = 0
            while (end < ops.size && (ops[end].first != ' ' || back < context)) {
                if (ops[end].first == ' ') back++ else back = 0
                end++
            }
            while (end > i && ops[end - 1].first == ' ' && back > context) { end--; back-- }
            val aStart = start + 1
            val bStart = start + 1
            var del = 0
            var add = 0
            for (k in start until end) {
                when (ops[k].first) {
                    '-' -> del++
                    '+' -> add++
                }
            }
            body.append("@@ -$aStart,${del} +$bStart,${add} @@\n")
            for (k in start until end) {
                val (tag, line) = ops[k]
                body.append(tag).append(if (tag == ' ') line else line).append('\n')
            }
            i = end
        } else i++
    }
    return if (body.isEmpty()) "" else header + body.toString().trimEnd('\n')
}

// Классический LCS. ' '-строка совпала, '-' удалена, '+' добавлена.
private fun lcsOps(a: List<String>, b: List<String>): List<Pair<Char, String>> {
    val n = a.size
    val m = b.size
    // Большие файлы режем: точность diff на 20k+ строк модели не нужна,
    // а память O(n*m) уже впечатляет.
    if (n > 20_000 || m > 20_000 || n.toLong() * m > 40_000_000L) {
        val ops = ArrayList<Pair<Char, String>>(n + m)
        a.forEach { ops += '-' to it }
        b.forEach { ops += '+' to it }
        return ops
    }
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val ops = ArrayList<Pair<Char, String>>(n + m)
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { ops += ' ' to a[i]; i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { ops += '-' to a[i]; i++ }
            else -> { ops += '+' to b[j]; j++ }
        }
    }
    while (i < n) { ops += '-' to a[i]; i++ }
    while (j < m) { ops += '+' to b[j]; j++ }
    return ops
}
