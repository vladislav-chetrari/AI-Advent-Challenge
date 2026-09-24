// Парсинг человеческого интервала в миллисекунды.
// Принимает: "30s", "10m", "1h", "1d", "500ms" или голое число (= минуты, для удобства LLM).
// Хранение в БД — всегда ms (см. Store).
object Interval {
    const val MIN_MS = 30_000L // 30s — чаще опрашивать Open-Meteo бессмысленно
    const val MAX_MS = 24L * 60 * 60 * 1000 // 24h

    private val RE = Regex("""^\s*(\d+)\s*(ms|s|m|h|d)?\s*$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Long {
        val m = RE.matchEntire(raw)
            ?: throw IllegalArgumentException("Bad interval '$raw'. Use e.g. 30s, 10m, 1h.")
        val n = m.groupValues[1].toLongOrNull()
            ?: throw IllegalArgumentException("Bad interval '$raw'.")
        val unit = m.groupValues[2].lowercase()
        val ms = when (unit) {
            "ms" -> n
            "s" -> n * 1_000
            "m", "" -> n * 60_000 // голое число = минуты
            "h" -> n * 3_600_000
            "d" -> n * 86_400_000
            else -> throw IllegalArgumentException("Bad interval '$raw'.")
        }
        if (ms < MIN_MS) throw IllegalArgumentException("Interval too small (min 30s): '$raw'.")
        if (ms > MAX_MS) throw IllegalArgumentException("Interval too large (max 24h): '$raw'.")
        return ms
    }

    fun format(ms: Long): String {
        if (ms % 86_400_000L == 0L) return "${ms / 86_400_000L}d"
        if (ms % 3_600_000L == 0L) return "${ms / 3_600_000L}h"
        if (ms % 60_000L == 0L) return "${ms / 60_000L}m"
        if (ms % 1_000L == 0L) return "${ms / 1_000L}s"
        return "${ms}ms"
    }
}
