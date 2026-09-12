package agent.data

import java.io.File

// Настройки сжатия контекста (Task 4) — по образцу ModelStore.
// N задаёт пользователь (сколько сообщений держать в памяти, >= 1).
// Лежит в текстовом файле рядом с chat.db, переживает рестарты.
data class CompressionSettings(
    val enabled: Boolean = true,
    val keepLastN: Int = DEFAULT_KEEP_LAST_N,
) {
    companion object {
        const val DEFAULT_KEEP_LAST_N: Int = 10
    }
}

object CompressionStore {
    const val FILE_NAME: String = "compression.txt"

    fun fileFor(dir: File?): File = File(dir, FILE_NAME)

    fun load(dir: File?): CompressionSettings {
        return try {
            val f = fileFor(dir)
            if (!f.isFile) return CompressionSettings()
            parse(f.readText())
        } catch (_: Exception) {
            CompressionSettings()
        }
    }

    fun save(dir: File?, settings: CompressionSettings) {
        try {
            val f = fileFor(dir)
            f.parentFile?.mkdirs()
            val n = settings.keepLastN.coerceAtLeast(1)
            f.writeText("${settings.enabled};$n")
        } catch (_: Exception) {
            // Настройки сжатия — не критично, молча игнорируем.
        }
    }

    internal fun parse(raw: String): CompressionSettings {
        val parts = raw.trim().split(";", ",", "|")
        if (parts.size < 2) {
            // Обратная совместимость: в файле только число N.
            val n = parts.firstOrNull()?.trim()?.toIntOrNull()?.coerceAtLeast(1)
                ?: return CompressionSettings()
            return CompressionSettings(enabled = true, keepLastN = n)
        }
        val enabled = when (parts[0].trim().lowercase()) {
            "0", "false", "off", "no" -> false
            else -> true
        }
        val n = parts[1].trim().toIntOrNull()?.coerceAtLeast(1)
            ?: CompressionSettings.DEFAULT_KEEP_LAST_N
        return CompressionSettings(enabled = enabled, keepLastN = n)
    }
}
