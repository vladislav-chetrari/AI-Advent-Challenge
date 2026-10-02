package core.llm

import java.io.File
import java.nio.file.Paths

// Ключ: override (тесты) -> env -> .env вверх от места запуска (6 уровней,
// чтобы находился корневой .env при старте из Week 4) -> ~/.ai-advent-week4/.env
object ApiKeyProvider {
    @Volatile
    var override: String? = null

    fun appDir(): File = File(System.getProperty("user.home"), ".ai-advent-week4")

    fun resolve(): String? {
        override?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("DEEPSEEK_API_KEY")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        findDotEnvUpwards()?.let { return it }
        readKeyFrom(File(appDir(), ".env"))?.let { return it }
        return null
    }

    private fun findDotEnvUpwards(): String? {
        return try {
            var dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            repeat(6) {
                readKeyFrom(dir.resolve(".env").toFile())?.let { return it }
                dir = dir.parent ?: return null
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readKeyFrom(f: File): String? {
        return try {
            if (!f.isFile) return null
            f.readLines().forEach { line ->
                val t = line.trim()
                if (t.startsWith("DEEPSEEK_API_KEY")) {
                    val v = t.substringAfter("=", "").trim().trim('"', '\'')
                    if (v.isNotBlank()) return v
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
