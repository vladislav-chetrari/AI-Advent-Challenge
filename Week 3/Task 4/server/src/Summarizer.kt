import kotlinx.serialization.Serializable

@Serializable
data class QuakeSummary(
    val count: Int,
    val maxMag: Double? = null,
    val avgMag: Double? = null,
    val strongestPlace: String? = null,
    val strongestTime: String? = null,
    val top: List<Quake> = emptyList(),
)

// Чистая обработка: вход — выход search_quakes, выход — вход save_report_to_file.
// Никаких сетей и файлов здесь нет, поэтому цепочка детерминирована и тестируема.
object Summarizer {
    fun summarize(quakes: List<Quake>, topN: Int = 5): QuakeSummary {
        if (quakes.isEmpty()) return QuakeSummary(count = 0)
        val sorted = quakes.sortedByDescending { it.mag }
        val strongest = sorted.first()
        val avg = quakes.map { it.mag }.average()
        return QuakeSummary(
            count = quakes.size,
            maxMag = strongest.mag,
            avgMag = (avg * 100).toInt() / 100.0,
            strongestPlace = strongest.place,
            strongestTime = strongest.time,
            top = sorted.take(topN.coerceIn(1, 20)),
        )
    }

    fun renderMarkdown(query: String, summary: QuakeSummary): String {
        val sb = StringBuilder()
        sb.appendLine("# Quake digest: $query")
        sb.appendLine()
        sb.appendLine("- events: ${summary.count}")
        if (summary.count == 0) {
            sb.appendLine("- no earthquakes match the filter")
            return sb.toString()
        }
        sb.appendLine("- max magnitude: ${summary.maxMag}")
        sb.appendLine("- avg magnitude: ${summary.avgMag}")
        sb.appendLine("- strongest: ${summary.strongestPlace} (${summary.strongestTime})")
        sb.appendLine()
        sb.appendLine("## Top ${summary.top.size}")
        summary.top.forEachIndexed { i, q ->
            sb.appendLine("${i + 1}. M${q.mag} — ${q.place} (${q.time}) ${q.url}")
        }
        return sb.toString()
    }
}
