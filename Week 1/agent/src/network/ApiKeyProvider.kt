package agent.network

import java.nio.file.Paths

// SRP: только поиск ключа. Agent.resolveApiKey() делегирует сюда для совместимости.
object ApiKeyProvider {
    @Volatile
    var override: String? = null

    fun resolve(): String? {
        if (override != null) return override!!.trim().ifBlank { null }
        System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return findDotEnvKey()
    }

    private fun findDotEnvKey(): String? {
        return try {
            var dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            repeat(5) {
                val dotEnv = dir.resolve(".env").toFile()
                if (dotEnv.isFile) {
                    dotEnv.readLines().forEach { line ->
                        val t = line.trim()
                        if (t.startsWith("DEEPSEEK_API_KEY")) {
                            val value = t.substringAfter("=", "").trim().trim('"', '\'')
                            if (value.isNotBlank()) return value
                        }
                    }
                }
                dir = dir.parent ?: return null
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
