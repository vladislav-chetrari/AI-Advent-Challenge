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
