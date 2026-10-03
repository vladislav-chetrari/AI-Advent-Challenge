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

// Task 4 (День 24): компактный референс под ответом.
// В тексте ответа — короткая метка [N], здесь — откуда факт:
// source + section/chunk_id + цитата-фрагмент из чанка.
@Serializable
data class RagRef(
    val index: Int, // 1-based, совпадает с [N] в тексте ответа
    val title: String,
    val section: String = "",
    val source: String = "",
    val chunkId: String = "",
    val strategy: String = "",
    val score: Float = 0f,
    val excerpt: String = "",
)

// RAG-ответ: текст LLM + чанки, на которые он опирался.
data class RagAnswer(
    val text: String,
    val sources: List<ScoredChunk>,
    // Task 3: диагностика второго этапа (сколько забрали / сколько отдали модели).
    val retrieval: RetrievalDebug = RetrievalDebug(),
    // Task 4: true = честный отказ без вызова LLM (ничего релевантного).
    val refused: Boolean = false,
    // Task 4: готовые референсы для раскрывашки под ответом.
    val refs: List<RagRef> = emptyList(),
)

// Task 4: референсы строим серверной стороной из kept-чанков,
// а не парсим из текста LLM — источники и цитаты есть всегда.
fun refsFromHits(hits: List<ScoredChunk>, excerptLen: Int = 280): List<RagRef> =
    hits.mapIndexed { i, h ->
        val flat = h.chunk.text.replace(Regex("\\s+"), " ").trim()
        RagRef(
            index = i + 1,
            title = h.chunk.title,
            section = h.chunk.section,
            source = h.chunk.source,
            chunkId = h.chunk.id,
            strategy = h.chunk.strategy,
            score = h.score,
            excerpt = flat.take(excerptLen),
        )
    }

// Task 3 (День 23): что произошло между векторным поиском и промптом.
data class RetrievalDebug(
    // Сколько кандидатов забрал векторный поиск (top-K до фильтрации).
    val retrieved: Int = 0,
    // Сколько ушло в промпт (после фильтра).
    val kept: Int = 0,
    // Сколько отсечено (ниже температуры + за пределами top-K после).
    val dropped: Int = 0,
    // Минимальный score среди kept (видна уверенность выдачи).
    val minKeptScore: Float? = null,
    // Rewrite-варианты запроса (пусто — rewrite выкл/не сработал).
    val rewritten: List<String> = emptyList(),
    // Фильтрация: off (выкл) | threshold (вкл).
    val rerankMode: String = "off",
) {
    // Однострочник для UI/CLI: "поиск: 20 → в контекст: 4 (отсечено: 16) · фильтр: темп. 0.35 · rewrite: +2".
    fun summary(temperature: Float? = null): String = buildString {
        append("поиск: $retrieved → в контекст: $kept (отсечено: $dropped)")
        if (rerankMode != RerankModes.OFF && temperature != null) append(" · фильтр: темп. ${"%.2f".format(temperature)}")
        if (rewritten.isNotEmpty()) append(" · rewrite: +${rewritten.size}")
    }
}

// Task 3: один режим в сравнении (off / threshold / threshold+rewrite).
@Serializable
data class ModeReport(
    val modeLabel: String,
    // probe -> строки "title / section (score)" по kept-чанкам
    val probes: Map<String, List<String>> = emptyMap(),
    val avgKept: Double = 0.0,
    val avgDropped: Double = 0.0,
)

@Serializable
data class ModesCompareResult(
    val reports: List<ModeReport> = emptyList(),
)

// Грубая оценка токенов без токенизатора: хватает для лимитов чанков и промпта.
fun estimateTokens(chars: Int): Int = (chars / 4).coerceAtLeast(1)

fun chunkId(doc: RawDoc, ord: Int): String = "${doc.source}#$ord"
