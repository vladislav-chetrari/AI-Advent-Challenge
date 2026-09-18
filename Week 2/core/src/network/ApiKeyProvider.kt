package core.network

import java.io.File

// Ключ: override (для тестов) -> env -> .env рядом с проектом / в HOME.
object ApiKeyProvider {
    @Volatile
    var override: String? = null

    fun resolve(): String? {
        override?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
        // .env в Week2, в корне репо, в HOME
        val candidates = listOf(
            File("Week 2/.env"), File(".env"),
            File(System.getProperty("user.home"), ".ai-advent-week2/.env"),
        )
        for (f in candidates) {
            try {
                if (!f.exists()) continue
                f.readLines().forEach { line ->
                    val t = line.trim()
                    if (t.startsWith("DEEPSEEK_API_KEY=")) {
                        val v = t.substringAfter("=").trim().trim('"', '\'')
                        if (v.isNotBlank()) return v
                    }
                }
            } catch (_: Exception) { }
        }
        return null
    }
}
