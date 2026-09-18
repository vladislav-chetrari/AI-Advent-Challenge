package core.network

import java.io.File
import java.nio.file.Paths

// Ключ: override (для тестов) -> env -> .env вверх от места запуска -> ~/.ai-advent-week2/.env
// (обход вверх — как в Week 1, чтобы корневой .env находился при запуске из Week 2/).
object ApiKeyProvider {
    @Volatile
    var override: String? = null

    fun resolve(): String? {
        override?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("DEEPSEEK_API_KEY")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        findDotEnvUpwards()?.let { return it }
        readKeyFrom(File(System.getProperty("user.home"), ".ai-advent-week2/.env"))?.let { return it }
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
