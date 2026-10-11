package core.rag

/**
 * Чистый retrieval поверх [RagStore]: dense-поиск + два гейта из Task 4.
 * Гейт 1: косинус ниже MIN_SCORE — нерелевантно, отдаём пусто.
 * Гейт 2 (hybrid-lite): ни один корень запроса не встретился в текстах
 * топ-хитов — тоже мимо (вороны vs дрозды).
 */
const val MIN_SCORE: Float = 0.55f

fun retrieve(
    query: String,
    queryVec: FloatArray,
    chunks: List<StoredChunk>,
    topK: Int = 5,
    minScore: Float = MIN_SCORE,
): List<ScoredChunk> {
    if (chunks.isEmpty()) return emptyList()
    val scored = cosineTopK(queryVec, chunks.mapIndexed { i, e -> i to e.embedding }, topK)
    val above = scored.filter { it.second >= minScore }
    if (above.isEmpty()) return emptyList()
    val hits = above.map { (i, s) ->
        val e = chunks[i]
        ScoredChunk(e.title, e.section, e.source, e.text, s)
    }
    if (!hasTokenOverlap(query, hits)) return emptyList()
    return hits
}

/** Дешёвая keyword-проверка поверх dense: стемы запроса длиной 5+ против текстов хитов. */
fun hasTokenOverlap(query: String, hits: List<ScoredChunk>): Boolean {
    val stems = query.lowercase()
        .split(Regex("[^а-яa-zё]+"))
        .filter { it.length >= 4 }
        .map { it.take(5) }
        .toSet()
    if (stems.isEmpty()) return true
    return hits.any { h ->
        val text = h.text.lowercase()
        stems.any { s -> s in text }
    }
}
