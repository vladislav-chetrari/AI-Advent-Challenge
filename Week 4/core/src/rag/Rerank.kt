package core.rag

// Task 3 (День 23): второй этап после векторного поиска — фильтрация.
//
// Идея: векторный поиск всегда возвращает top-K, даже если реально релевантны
// 2 чанка из 20. Остальное — шум, который раздувает промпт и провоцирует
// галлюцинации. Поэтому забираем широко (top-K до фильтрации, высокий recall),
// а модели отдаём только уверенное (порог по температуре + опциональный
// top-K после фильтрации, высокая precision).
//
// Режимы (задание 3):
// - off: фильтр выкл, первые retrieveK идут в контекст как есть (бейзлайн);
// - threshold: фильтр вкл — отсечение по температуре (minScore 0..1),
//   затем опциональная обрезка до finalK (1..retrieveK).

object RerankModes {
    const val OFF = "off"
    const val THRESHOLD = "threshold"
    // Оставлен для обратной совместимости CLI (маппится на threshold).
    @Deprecated("Упрощено до вкл/выкл по заданию 3")
    const val HEURISTIC = "heuristic"
    val ALL = listOf(OFF, THRESHOLD)
}

const val DEFAULT_RETRIEVE_K = 20
const val DEFAULT_FINAL_K = 5
const val DEFAULT_MIN_SCORE = 0.35f

// Задание 3: новая схема настроек.
// - topK до фильтрации (retrieveK): сколько чанков забрать из индекса;
// - фильтрация вкл/выкл (mode off/threshold);
// - температура (minScore 0..1): порог отсечения по косинусной близости;
// - topK после фильтрации (finalK, опционален): повторная обрезка уже
//   отфильтрованных по температуре чанков, от 1 до retrieveK.
//   null = без второй обрезки (в контекст идёт всё, что прошло порог).
data class RerankConfig(
    val mode: String = RerankModes.THRESHOLD,
    val retrieveK: Int = DEFAULT_RETRIEVE_K,
    val finalK: Int? = DEFAULT_FINAL_K,
    val minScore: Float = DEFAULT_MIN_SCORE,
) {
    // Удобные алиасы под UI задания 3: температура = порог точности 0..1.
    val temperature: Float get() = minScore
    val topKBefore: Int get() = retrieveK
    val topKAfter: Int? get() = finalK
    val filterEnabled: Boolean get() = normalized().mode != RerankModes.OFF

    fun normalized(): RerankConfig {
        val m = when (mode) {
            RerankModes.OFF -> RerankModes.OFF
            // "heuristic" — старый режим, теперь просто порог (строкой, без deprecated-константы).
            RerankModes.THRESHOLD, "heuristic" -> RerankModes.THRESHOLD
            else -> RerankModes.THRESHOLD
        }
        val r = retrieveK.coerceIn(1, 50)
        // top-K после — опционален и не может превышать top-K до.
        val f = finalK?.coerceIn(1, r)
        return copy(mode = m, retrieveK = r, finalK = f, minScore = minScore.coerceIn(0f, 1f))
    }
}

data class RerankResult(
    // Чанки для промпта (после фильтра + опционального top-K после, по убыванию).
    val kept: List<ScoredChunk>,
    // Всё остальное из выборки: ниже температуры + за пределами top-K после.
    val dropped: List<ScoredChunk>,
)

// Слияние выдач нескольких запросов (оригинал + rewrite-варианты):
// один и тот же чанк берём с максимальной оценкой, сортировка по убыванию.
fun mergeRetrievals(lists: List<List<ScoredChunk>>): List<ScoredChunk> {
    val best = LinkedHashMap<String, ScoredChunk>()
    for (hits in lists) {
        for (h in hits) {
            val key = "${h.chunk.id}#${h.chunk.strategy}"
            val prev = best[key]
            if (prev == null || h.score > prev.score) best[key] = h
        }
    }
    return best.values.sortedByDescending { it.score }
}

fun rerank(query: String, candidates: List<ScoredChunk>, config: RerankConfig): RerankResult {
    val cfg = config.normalized()
    if (candidates.isEmpty()) return RerankResult(emptyList(), emptyList())
    if (cfg.mode == RerankModes.OFF) {
        // Фильтр выкл: top-K до фильтрации идёт в контекст как есть.
        val kept = candidates.take(cfg.retrieveK)
        return RerankResult(kept = kept, dropped = candidates.drop(kept.size))
    }
    // Фильтр вкл: сначала порог по температуре, затем опциональная
    // повторная обрезка до top-K после фильтрации.
    val filtered = candidates.filter { it.score >= cfg.minScore }
    val kept = if (cfg.finalK != null) filtered.take(cfg.finalK) else filtered
    val keptSet = kept.toSet()
    return RerankResult(kept = kept, dropped = candidates.filter { it !in keptSet })
}
