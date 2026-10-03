package core.rag

import kotlinx.serialization.Serializable

// Сырой документ после загрузки с диска: один файл = один RawDoc.
data class RawDoc(
    val source: String, // относительный путь, напр. "Week 2/docs/MEMORY.md"
    val title: String, // имя файла без пути
    val text: String,
)

// Чанк — единица индекса. Метаданные обязательны по Task 1 (День 21):
// source + title/file + section + chunk_id.
@Serializable
data class Chunk(
    val id: String, // chunk_id: "<title>#<ord>"
    val strategy: String, // каким чанкером получен: "fixed" | "structure"
    val source: String,
    val title: String,
    val section: String,
    val ord: Int,
    val text: String,
    val tokens: Int, // грубая оценка: chars / 4
)

// Результат поиска: чанк + косинусная близость к запросу.
@Serializable
data class ScoredChunk(
    val chunk: Chunk,
    val score: Float,
)

// Статистика одного индекса (одной стратегии).
@Serializable
data class IndexStats(
    val strategy: String,
    val docs: Int,
    val chunks: Int,
    val avgChars: Int,
    val totalChars: Long,
    val dim: Int,
    val embedModel: String,
    val indexMs: Long = 0,
)

// Сравнение двух стратегий на одном корпусе + пробных запросах.
@Serializable
data class StrategyReport(
    val stats: IndexStats,
    // query -> top-1 заголовок+секция (чтобы глазами сравнить релевантность)
    val probes: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class CompareResult(
    val fixed: StrategyReport,
    val structure: StrategyReport,
)

// Документ базы знаний для UI: один source = одна строка в дереве.
@Serializable
data class DocInfo(
    val source: String,
    val title: String,
    val chunks: Int,
    val chars: Long,
    val active: Boolean = true,
    // Стратегия, выбранная при добавлении: "fixed" | "structure" | "both".
    val strategy: String = "structure",
    // Подпись в сайдбаре — по факту проиндексированного ("фиксированный",
    // "структурный", "обе стратегии"); пока чанков нет — по выбранной.
    val strategyLabel: String = "",
    // Документ прямо сейчас индексируется: строка некликабельна + прогрессбар.
    val indexing: Boolean = false,
    val error: String? = null,
)

// Статусы индексации документа (таблица document, поле status).
object DocStatus {
    const val INDEXING = "indexing"
    const val READY = "ready"
    const val ERROR = "error"
}

// Строка таблицы document: выбранная при добавлении стратегия + статус.
@Serializable
data class DocumentEntry(
    val source: String,
    val title: String,
    val strategy: String = "structure",
    val status: String = DocStatus.READY,
    val error: String = "",
    val updatedAt: Long = 0,
)

// Человекочитаемое имя стратегии для сайдбара.
fun strategyName(s: String): String = when (s) {
    "fixed" -> "фиксированный"
    "structure" -> "структурный"
    "both" -> "обе стратегии"
    else -> s
}

// Подпись метода чанкинга: по фактически лежащим в индексе стратегиям,
// для пустого (ещё индексируется) — по выбранной при добавлении.
fun displayStrategy(requested: String, actual: Set<String>): String = when {
    actual.size > 1 -> "обе стратегии"
    actual.size == 1 -> strategyName(actual.first())
    else -> strategyName(requested)
}

// RAG-ответ: текст LLM + чанки, на которые он опирался.
data class RagAnswer(
    val text: String,
    val sources: List<ScoredChunk>,
)

// Грубая оценка токенов без токенизатора: хватает для лимитов чанков и промпта.
fun estimateTokens(chars: Int): Int = (chars / 4).coerceAtLeast(1)

fun chunkId(doc: RawDoc, ord: Int): String = "${doc.source}#$ord"
