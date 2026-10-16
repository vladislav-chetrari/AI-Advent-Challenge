package core.project

import java.io.File
import java.util.concurrent.TimeUnit

// MCP-тулы проекта (Week 6 Task 1): живой контекст репозитория для ассистента.
// Только чтение: ветка/коммит/файлы/diff. Никакого checkout — read-only сознательно.
object GitTools {
    data class GitInfo(val branch: String, val commit: String, val isRepo: Boolean)

    fun info(dir: File): GitInfo {
        val branch = branch(dir)
        val commit = commit(dir)
        return GitInfo(branch, commit, branch != NOT_GIT)
    }

    // Минимум по заданию: текущая git-ветка.
    fun branch(dir: File): String {
        val (code, out) = run(dir, 10, "rev-parse", "--abbrev-ref", "HEAD")
        if (code != 0 || out.isBlank()) return NOT_GIT
        return out.trim().take(80)
    }

    fun commit(dir: File): String {
        val (code, out) = run(dir, 10, "rev-parse", "--short", "HEAD")
        if (code != 0 || out.isBlank()) return "—"
        return out.trim().take(20)
    }

    fun listFiles(dir: File, limit: Int = 200): List<String> {
        val (code, out) = run(dir, 10, "ls-files")
        if (code == 0 && out.isNotBlank()) return out.lines().filter { it.isNotBlank() }.take(limit)
        // Не git-репо: обход на месте без служебных папок.
        return try {
            dir.walkTopDown()
                .onEnter { d -> d.name !in setOf(".git", "build", ".gradle", ".idea", "node_modules", "out") }
                .filter { it.isFile }
                .map { dir.toPath().relativize(it.toPath()).toString().replace('\\', '/') }
                .take(limit)
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun diffStat(dir: File, maxChars: Int = 4000): String {
        val (_, stat) = run(dir, 15, "diff", "--stat", "HEAD")
        val (_, status) = run(dir, 10, "status", "--short", "--branch")
        val combined = buildString {
            if (status.isNotBlank()) appendLine(status.trim())
            if (stat.isNotBlank()) appendLine(stat.trim())
        }.trim()
        if (combined.isBlank()) return "рабочее дерево чистое"
        return combined.take(maxChars)
    }

    // ===== Task 2: PR-diff для ревью =====
    // base...head — трёхточечный diff (только изменения ветки PR).
    // base обычно origin/main или merge-base, head — HEAD PR.
    data class PrDiff(
        val base: String,
        val head: String,
        val files: List<String>,
        val diff: String,
        val truncated: Boolean,
    )

    fun mergeBase(dir: File, base: String, head: String): String {
        val resolved = resolveBase(dir, base)
        val (code, out) = run(dir, 10, "merge-base", resolved, head)
        if (code == 0 && out.isNotBlank()) return out.trim().take(40)
        return resolved
    }

    // base может отсутствовать (shallow-клон, ветка master вместо main):
    // проверяем ref, иначе пробуем типовые варианты, иначе честная ошибка с подсказкой.
    fun resolveBase(dir: File, base: String): String {
        if (refExists(dir, base)) return base
        for (cand in listOf("origin/main", "origin/master", "main", "master")) {
            if (cand != base && refExists(dir, cand)) return cand
        }
        throw IllegalStateException(
            "нет git-рефа '$base' (и нет origin/main|origin/master). " +
                "Укажи --base вручную или сделай git fetch."
        )
    }

    private fun refExists(dir: File, ref: String): Boolean {
        val (code, _) = run(dir, 10, "rev-parse", "--verify", "--quiet", ref)
        return code == 0
    }

    fun changedFiles(dir: File, base: String, head: String, limit: Int = 100): List<String> {
        val mb = mergeBase(dir, base, head)
        val (code, out) = run(dir, 15, "diff", "--name-only", "$mb...$head")
        if (code != 0 || out.isBlank()) return emptyList()
        return out.lines().map { it.trim() }.filter { it.isNotBlank() }.take(limit)
    }

    fun prDiff(dir: File, base: String, head: String, maxChars: Int = 120_000): PrDiff {
        val mb = mergeBase(dir, base, head)
        val range = "$mb...$head"
        val (namesCode, namesOut) = run(dir, 15, "diff", "--name-only", range)
        if (namesCode != 0) throw IllegalStateException("git diff --name-only: ${namesOut.take(200)}")
        val files = namesOut.lines().map { it.trim() }.filter { it.isNotBlank() }.take(100)
        val (diffCode, diffOut) = run(dir, 30, "diff", "--unified=3", "--no-color", "--no-ext-diff", range)
        if (diffCode != 0) throw IllegalStateException("git diff: ${diffOut.take(200)}")
        val truncated = diffOut.length > maxChars
        return PrDiff(base = mb, head = head, files = files, diff = diffOut.take(maxChars), truncated = truncated)
    }

    // Карта добавленных строк: path -> множество номеров строк в НОВОМ файле.
    // Только "+"-строки — GitHub Reviews API принимает inline-комменты
    // только на изменённых строках, иначе 422.
    fun parseAddedLines(diffText: String): Map<String, Set<Int>> {
        val res = mutableMapOf<String, MutableSet<Int>>()
        var path = ""
        var newLine = 0
        var inHunk = false
        for (raw in diffText.lines()) {
            when {
                raw.startsWith("+++ ") -> {
                    val p = raw.removePrefix("+++ ").trim()
                    path = when {
                        p == "/dev/null" -> ""
                        p.startsWith("b/") -> p.removePrefix("b/")
                        else -> p
                    }
                    if (path.isNotBlank() && path !in res) res[path] = mutableSetOf()
                    inHunk = false
                }
                raw.startsWith("@@ ") -> {
                    // @@ -a,b +c,d @@ — c = старт в новом файле
                    val m = Regex("""\+(\d+)(?:,(\d+))?""").find(raw)
                    newLine = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    inHunk = true
                }
                inHunk && path.isNotBlank() -> when {
                    raw.startsWith("+") && !raw.startsWith("+++") -> {
                        res.getOrPut(path) { mutableSetOf() }.add(newLine)
                        newLine++
                    }
                    raw.startsWith("-") && !raw.startsWith("---") -> { /* old only, newLine стоит */ }
                    raw.startsWith("\\") -> { /* "\ No newline at end of file" — не строка */ }
                    else -> newLine++ // контекст " "
                }
            }
        }
        return res
    }

    // Нарезка unified-diff по файлам для пофайлового ревью больших PR.
    fun splitDiffByFile(diffText: String): Map<String, String> {
        val out = LinkedHashMap<String, StringBuilder>()
        var path = ""
        for (line in diffText.lines()) {
            if (line.startsWith("diff --git ")) {
                val m = Regex("""b/(.+?)(?:\s|$)""").find(line)
                path = m?.groupValues?.getOrNull(1)?.trim().orEmpty()
                if (path.isNotBlank() && path !in out) out[path] = StringBuilder()
            }
            if (path.isNotBlank()) out.getOrPut(path) { StringBuilder() }.appendLine(line)
        }
        return out.mapValues { it.value.toString() }
    }

    fun readFile(dir: File, rel: String, maxChars: Int = 12_000): String {
        val safe = rel.trim().trimStart('/').replace('\\', '/')
        require(".." !in safe) { "выход за корень проекта запрещён" }
        val f = File(dir, safe)
        require(f.isFile && f.canonicalPath.startsWith(dir.canonicalPath)) { "нет такого файла: $rel" }
        require(f.length() <= 500_000) { "файл слишком большой" }
        return f.readText(Charsets.UTF_8).take(maxChars)
    }

    private fun run(dir: File, timeoutSec: Long, vararg args: String): Pair<Int, String> {
        return try {
            val proc = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return 1 to "timeout"
            }
            proc.exitValue() to proc.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            1 to (e.message ?: "git error")
        }
    }

    const val NOT_GIT = "not-a-git-repo"
}
