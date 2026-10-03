package core.rag

import core.llm.ApiKeyProvider
import core.llm.ChatMsg
import core.llm.LlmClient
import core.llm.LlmResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val DEFAULT_PROBES = listOf(
    "Как устроена модель памяти агента?",
    "Где хранятся инварианты и как они проверяются?",
    "Как работает конечный автомат состояния задачи?",
    "Как подключается DeepSeek API и где лежит ключ?",
    "Как сохраняется состояние приложения на диск?",
)

// Фасад всего RAG-пайплайна: загрузка -> чанкинг -> эмбеддинги -> индекс ->
// поиск -> ответ. Desktop и CLI говорят только с ним.
class RagService(
    private val appDir: File = ApiKeyProvider.appDir(),
    embedder: EmbeddingProvider? = null,
    private val llm: LlmClient = LlmClient(),
) {
    private val dbFile = File(appDir, "rag.db")
    val store = SqliteVectorStore(dbFile)
    private val embedSettings = EmbedSettingsStore(appDir)
    private val explicitEmbedder: EmbeddingProvider? = embedder

    // Эмбеддинги — ТОЛЬКО локальная модель через Ollama.
    // Никаких молчаливых фолбэков: нет модели на порту — ошибка наружу,
    // UI её показывает. Смена настроек — через resetEmbedder().
    private val embedLock = Any()
    private var cachedHolder: CachedEmbedder? = null

    private fun embedder(): CachedEmbedder = synchronized(embedLock) {
        cachedHolder ?: buildEmbedder().also { cachedHolder = it }
    }

    private fun buildEmbedder(): CachedEmbedder {
        val base = explicitEmbedder ?: run {
            val s = embedSettings.effective()
            OllamaEmbedder(model = s.model, baseUrl = s.baseUrl)
        }
        return CachedEmbedder(base)
    }

    fun resetEmbedder() {
        synchronized(embedLock) { cachedHolder = null }
    }

    // Короткий label для UI: "ollama:bge-m3 · localhost:11434".
    val embedLabel: String get() {
        val s = embedSettings.effective()
        val model = explicitEmbedder?.name ?: "ollama:${s.model}"
        val host = s.baseUrl.substringAfter("://")
        return "$model · $host"
    }

    // Проба связи с моделью: бросает понятную ошибку, если Ollama недоступна.
    // Используется при включении RAG, кнопке "Проверить" и перед индексацией.
    suspend fun checkEmbeddings(): String = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val v = embedder().embed(listOf("проверка связи"))
        val ms = System.currentTimeMillis() - t0
        val name = embedder().name
        "OK · $name · dim=${v.first().size} · ${ms}мс"
    }

    fun strategies(): List<String> = listOf("fixed", "structure")

    fun chunkerFor(name: String): Chunker = when (name) {
        "structure" -> StructureChunker()
        else -> FixedSizeChunker()
    }

    // Ядро индексации: готовые RawDoc -> чанки -> эмбеддинги -> upsert.
    // Единственный источник документов — статьи Википедии (см. fetchWikiDoc).
    // Полная перезапись стратегии (для CLI wiki): чужие документы не трогаем
    // только в indexNewDocs; здесь исторически wipe стратегии целиком.
    suspend fun indexDocs(
        docs: List<RawDoc>,
        strategy: String,
        onLog: (String) -> Unit = {},
    ): IndexStats = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val chunker = chunkerFor(strategy)
        val chunks = docs.flatMap { chunker.chunk(it) }
        onLog("Чанков ($strategy): ${chunks.size}")
        // Инкрементальность: вектора неизменившихся текстов забираем из БД,
        // модель считает только новые. После clear+upsert индекс консистентен.
        val stored = store.loadTextVectors(strategy)
        val vectors = embedWithReuse(chunks.map { it.text }, stored) { done, total ->
            if (done % 256 == 0 || done == total) onLog("Эмбеддинги: $done/$total (новых)")
        }
        store.clearStrategy(strategy)
        store.upsert(chunks, vectors)
        touchDocsReady(docs, strategy)
        val ms = System.currentTimeMillis() - t0
        val chars = chunks.sumOf { it.text.length.toLong() }
        val stats = IndexStats(
            strategy = strategy,
            docs = docs.size,
            chunks = chunks.size,
            avgChars = if (chunks.isNotEmpty()) (chars / chunks.size).toInt() else 0,
            totalChars = chars,
            dim = embedder().dim,
            embedModel = embedder().name,
            indexMs = ms,
        )
        onLog("Готово за ${ms / 1000}с. ${embedder().stats()}")
        stats
    }

    // Точечная индексация только что добавленных документов — выбранной
    // при добавлении стратегией, без wipe чужих документов.
    // onDocProgress(source, done, total): done эмбеддингов из total новых
    // для документа (total=0 — нет новых, док уже в кэше/БД).
    suspend fun indexNewDocs(
        docs: List<RawDoc>,
        strategy: String,
        onDocProgress: (source: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
        onLog: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val wanted = if (strategy == "both") listOf("fixed", "structure") else listOf(strategy)
        val storedByStrategy = wanted.associateWith { store.loadTextVectors(it) }
        val failed = mutableListOf<String>()
        for (doc in docs) {
            try {
                for (s in wanted) {
                    val chunks = chunkerFor(s).chunk(doc)
                    val stored = storedByStrategy[s].orEmpty()
                    val vectors = embedWithReuse(chunks.map { it.text }, stored) { done, total ->
                        onDocProgress(doc.source, done, total)
                    }
                    store.deleteSourcesForStrategy(listOf(doc.source), s)
                    store.upsert(chunks, vectors)
                    onLog("«${doc.title}» [$s]: ${chunks.size} чанков")
                }
                store.upsertDoc(
                    DocumentEntry(doc.source, doc.title, strategy, DocStatus.READY, "", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                store.upsertDoc(
                    DocumentEntry(doc.source, doc.title, strategy, DocStatus.ERROR, e.message ?: "ошибка", System.currentTimeMillis())
                )
                failed += "«${doc.title}»: ${e.message}"
            }
        }
        if (failed.isNotEmpty()) throw IllegalStateException(failed.joinToString("; "))
    }

    // Общий эмбеддинг с переиспользованием векторов из БД + LRU-кэша модели.
    // Возвращает вектора 1-в-1 к texts.
    private suspend fun embedWithReuse(
        texts: List<String>,
        stored: Map<String, FloatArray>,
        onBatch: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<FloatArray> {
        val dim = embedder().dim
        val vectors = arrayOfNulls<FloatArray>(texts.size)
        val missing = mutableListOf<String>()
        val missingIdx = mutableListOf<Int>()
        var reused = 0
        texts.forEachIndexed { i, t ->
            val v = stored[t]
            if (v != null && v.size == dim) {
                vectors[i] = v
                reused++
            } else {
                missing += t
                missingIdx += i
            }
        }
        val total = missing.size
        var done = 0
        for (batch in missing.chunked(32)) {
            val emb = embedder().embed(batch)
            emb.forEachIndexed { k, v -> vectors[missingIdx[done + k]] = v }
            done += batch.size
            onBatch(done, total)
        }
        if (total == 0) onBatch(0, 0)
        return vectors.map { it!! }
    }

    // Новодобавленные документы сразу видны в сайдбаре как индексирующиеся.
    fun markIndexing(docs: List<RawDoc>, strategy: String) {
        val now = System.currentTimeMillis()
        docs.forEach { store.upsertDoc(DocumentEntry(it.source, it.title, strategy, DocStatus.INDEXING, "", now)) }
    }

    fun markError(sources: List<String>, msg: String) {
        val now = System.currentTimeMillis()
        val known = store.allDocs().associateBy { it.source }
        sources.forEach { s ->
            val prev = known[s]
            store.upsertDoc(
                DocumentEntry(
                    source = s,
                    title = prev?.title ?: s.substringAfterLast("/"),
                    strategy = prev?.strategy ?: "structure",
                    status = DocStatus.ERROR,
                    error = msg,
                    updatedAt = now,
                )
            )
        }
    }

    // После полной перезаписи стратегии (CLI) документы помечаем готовыми,
    // выбранную ранее стратегию не затираем — сайдбар показывает факт индекса.
    private fun touchDocsReady(docs: List<RawDoc>, strategy: String) {
        val now = System.currentTimeMillis()
        val known = store.allDocs().associateBy { it.source }
        docs.forEach { d ->
            val prev = known[d.source]
            store.upsertDoc(
                DocumentEntry(
                    d.source, d.title,
                    prev?.strategy?.takeIf { it.isNotBlank() } ?: strategy,
                    DocStatus.READY, "", now,
                )
            )
        }
    }

    // Статья Википедии -> RawDoc + копия в локальном корпусе (appDir/corpus) для просмотра.
    // Единственный способ пополнить базу знаний.
    suspend fun fetchWikiDoc(input: String): RawDoc = withContext(Dispatchers.IO) {
        val doc = WikiLoader.fetch(input)
        try {
            val dir = File(appDir, "corpus")
            dir.mkdirs()
            File(dir, WikiLoader.fileNameFor(doc))
                .writeText("# ${doc.title}\n\nИсточник: ${doc.source}\n\n${doc.text}", Charsets.UTF_8)
        } catch (_: Exception) {
        }
        doc
    }

    suspend fun search(
        query: String,
        strategy: String,
        topK: Int = 5,
        onlySources: Set<String>? = null,
    ): List<ScoredChunk> {
        if (query.isBlank()) return emptyList()
        val q = embedder().embed(listOf(query)).first()
        return store.search(strategy, q, topK.coerceIn(1, 20), onlySources)
    }

    // Task 3: поиск со вторым этапом. По оригиналу + rewrite-вариантам
    // забираем широко (top-K до фильтрации на запрос), выдачи сливаем max-score,
    // дальше rerank(): порог по температуре + опциональный top-K после.
    // Возвращаем и kept, и debug по dropped.
    suspend fun searchWithRerank(
        query: String,
        strategy: String,
        config: RerankConfig,
        onlySources: Set<String>? = null,
        rewrites: List<String> = emptyList(),
    ): Pair<List<String>, RerankResult> {
        val cfg = config.normalized()
        if (query.isBlank()) return emptyList<String>() to RerankResult(emptyList(), emptyList())
        val queries = (listOf(query) + rewrites.filter { it.isNotBlank() }).distinct()
        val lists = queries.map { q ->
            val v = embedder().embed(listOf(q)).first()
            store.search(strategy, v, cfg.retrieveK, onlySources)
        }
        val merged = mergeRetrievals(lists)
        return queries to rerank(query, merged, cfg)
    }

    fun stats(strategy: String): IndexStats {
        val chunks = store.loadChunks(strategy)
        val chars = chunks.sumOf { it.text.length.toLong() }
        val docs = chunks.map { it.source }.toSet().size
        return IndexStats(
            strategy = strategy,
            docs = docs,
            chunks = chunks.size,
            avgChars = if (chunks.isNotEmpty()) (chars / chunks.size).toInt() else 0,
            totalChars = chars,
            dim = embedder().dim,
            embedModel = embedder().name,
        )
    }

    // RAG-ответ: retrieve (широко) -> rewrite-слияние -> фильтр ->
    // промпт с контекстом [S1..Sn] -> DeepSeek.
    // onlySources ограничивает контекст активными документами.
    // Дефолт "both": документы с разными стратегиями ищутся одним запросом.
    // Задание 3: topK — до фильтрации; filterEnabled вкл/выкл;
    // temperature 0..1 — порог точности; postFilterK (null = без ограничения,
    // иначе 1..topK) — повторная обрезка после фильтра по температуре.
    // Фильтр выкл = поведение Task 2 без изменений.
    suspend fun ask(
        query: String,
        strategy: String = "both",
        topK: Int = DEFAULT_RETRIEVE_K,
        apiKey: String? = null,
        onlySources: Set<String>? = null,
        filterEnabled: Boolean = true,
        temperature: Float = DEFAULT_MIN_SCORE,
        postFilterK: Int? = DEFAULT_FINAL_K,
        rewrite: Boolean = false,
    ): RagAnswer {
        val mode = if (filterEnabled) RerankModes.THRESHOLD else RerankModes.OFF
        val cfg = RerankConfig(mode, topK, postFilterK, temperature).normalized()
        val key = apiKey ?: ApiKeyProvider.resolve()
        val rewrites = if (rewrite) rewriteQueries(query, llm, key) else emptyList()
        val (queries, rr) = searchWithRerank(query, strategy, cfg, onlySources, rewrites)
        val debug = RetrievalDebug(
            retrieved = rr.kept.size + rr.dropped.size,
            kept = rr.kept.size,
            dropped = rr.dropped.size,
            minKeptScore = rr.kept.minOfOrNull { it.score },
            rewritten = queries.drop(1),
            rerankMode = cfg.mode,
        )
        if (rr.kept.isEmpty()) {
            val hint = if (cfg.mode == RerankModes.OFF) {
                "База знаний пуста — добавь статью через «+» в разделе «База знаний»."
            } else {
                "Ничего релевантного не нашлось (температура ${"%.2f".format(cfg.minScore)}, " +
                    "кандидатов: ${debug.retrieved}). " +
                    "Попробуй переформулировать вопрос или снизить температуру в настройках RAG."
            }
            return RagAnswer(hint, emptyList(), debug)
        }
        val hits = rr.kept
        val context = hits.mapIndexed { i, h ->
            "[S${i + 1}] ${h.chunk.title}" +
                (if (h.chunk.section.isNotBlank()) " / ${h.chunk.section}" else "") +
                ":\n${h.chunk.text.take(1500)}"
        }.joinToString("\n\n")
        val system = "Ты отвечаешь на вопросы по локальной базе знаний. " +
            "Используй ТОЛЬКО приведённые фрагменты. После каждого факта ставь ссылку вида [S1]. " +
            "Если ответа нет во фрагментах — так и скажи."
        val user = "Контекст:\n$context\n\nВопрос: $query"
        return when (val r = llm.complete(listOf(ChatMsg("system", system), ChatMsg("user", user)), apiKey ?: ApiKeyProvider.resolve())) {
            is LlmResult.Ok -> RagAnswer(r.text, hits, debug)
            is LlmResult.HttpError -> RagAnswer("DeepSeek HTTP ${r.code}: ${r.detail.take(200)}", hits, debug)
            is LlmResult.NetworkError -> RagAnswer("Сеть: ${r.detail.take(200)}", hits, debug)
            LlmResult.Empty -> RagAnswer("Пустой ответ модели.", hits, debug)
        }
    }

    // Task 3: сравнение режимов на одних пробах БЕЗ переиндексации.
    // Режимы: "без фильтра" / "с фильтром" / "фильтр + rewrite".
    // Задание 3: те же top-K до, температура и опциональный top-K после
    // для честного сравнения качества без фильтра / с фильтром.
    suspend fun compareModes(
        probes: List<String>,
        strategy: String = "both",
        topK: Int = DEFAULT_RETRIEVE_K,
        temperature: Float = DEFAULT_MIN_SCORE,
        postFilterK: Int? = DEFAULT_FINAL_K,
        apiKey: String? = null,
        onLog: (String) -> Unit = {},
    ): ModesCompareResult {
        val key = apiKey ?: ApiKeyProvider.resolve()
        val modes = listOf(
            "без фильтра" to Pair(RerankModes.OFF, false),
            "с фильтром (темп. ${"%.2f".format(temperature)})" to Pair(RerankModes.THRESHOLD, false),
            "фильтр + rewrite" to Pair(RerankModes.THRESHOLD, true),
        )
        val reports = modes.map { (label, mode) ->
            onLog("== Режим: $label ==")
            val (rerankMode, useRewrite) = mode
            val cfg = RerankConfig(rerankMode, topK, postFilterK, temperature).normalized()
            val probeHits = LinkedHashMap<String, List<String>>()
            var keptSum = 0
            var droppedSum = 0
            for (q in probes) {
                val rewrites = if (useRewrite) rewriteQueries(q, llm, key) else emptyList()
                val (_, rr) = searchWithRerank(q, strategy, cfg, activeSourcesOrNull(), rewrites)
                keptSum += rr.kept.size
                droppedSum += rr.dropped.size
                probeHits[q] = rr.kept.map { h ->
                    val sec = h.chunk.section.ifBlank { "—" }
                    "${h.chunk.title} / $sec (${"%.3f".format(h.score)})"
                }.ifEmpty { listOf("<отсечено всё: кандидатов ${rr.kept.size + rr.dropped.size}>") }
            }
            val n = probes.size.coerceAtLeast(1)
            ModeReport(label, probeHits, keptSum.toDouble() / n, droppedSum.toDouble() / n)
        }
        return ModesCompareResult(reports)
    }

    // Обычный ответ без поиска: чат с выключенным RAG.
    suspend fun askPlain(query: String, history: List<RagMessage>, apiKey: String? = null): String {
        val msgs = (history.takeLast(12).map { ChatMsg(it.role, it.content) } + ChatMsg("user", query))
        return when (val r = llm.complete(listOf(ChatMsg("system", "Ты полезный ассистент.")) + msgs, apiKey ?: ApiKeyProvider.resolve())) {
            is LlmResult.Ok -> r.text
            is LlmResult.HttpError -> "DeepSeek HTTP ${r.code}: ${r.detail.take(200)}"
            is LlmResult.NetworkError -> "Сеть: ${r.detail.take(200)}"
            LlmResult.Empty -> "Пустой ответ модели."
        }
    }

    // --- база знаний для UI ---

    private val sourcesStore = SourcesStore(ApiKeyProvider.appDir())

    // Активные документы: все проиндексированные минус выключенные тумблером.
    // null = фильтр не нужен (всё активно или индекс пуст).
    fun activeSourcesOrNull(): Set<String>? {
        val inactive = sourcesStore.loadState().inactive
        if (inactive.isEmpty()) return null
        val all = store.loadAllChunks().map { it.source }.toSet()
        return (all - inactive).ifEmpty { emptySet() }
    }

    // Документы для сайдбара: объединение чанков всех стратегий
    // и таблицы document (выбранная стратегия + статус индексации).
    // Подпись метода чанкинга — по факту лежащего в индексе.
    fun indexedDocs(): List<DocInfo> {
        val inactive = sourcesStore.loadState().inactive
        val grouped = store.loadAllChunks().groupBy { it.source }
        val entries = store.allDocs().associateBy { it.source }
        return (grouped.keys + entries.keys)
            .map { src ->
                val chunks = grouped[src].orEmpty()
                val e = entries[src]
                val actual = chunks.map { it.strategy }.toSet()
                val requested = e?.strategy?.takeIf { it.isNotBlank() }
                    ?: if (actual.size > 1) "both" else actual.firstOrNull() ?: "structure"
                val err = e?.takeIf { it.status == DocStatus.ERROR }?.error?.takeIf { it.isNotBlank() }
                DocInfo(
                    source = src,
                    title = chunks.firstOrNull()?.title ?: e?.title ?: src.substringAfterLast("/"),
                    chunks = chunks.size,
                    chars = chunks.sumOf { it.text.length.toLong() },
                    active = src !in inactive,
                    strategy = requested,
                    strategyLabel = displayStrategy(requested, actual),
                    indexing = e?.status == DocStatus.INDEXING,
                    error = err,
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    fun setDocActive(source: String, active: Boolean) {
        sourcesStore.setInactive(source, !active)
    }

    // Убранные из списка источники: чистим их чанки из обеих стратегий
    // + сохранённую копию статьи в корпусе.
    fun removeSources(sources: Collection<String>) {
        store.deleteSources(sources)
        sourcesStore.save(sourcesStore.loadState().inactive - sources.toSet())
        try {
            File(appDir, "corpus").walkTopDown().filter { it.isFile }.forEach { f ->
                try {
                    if (sources.any { s -> f.readText(Charsets.UTF_8).contains("Источник: $s") }) f.delete()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    // Текст документа для просмотра: сначала копия статьи в корпусе,
    // fallback — склейка чанков из индекса.
    fun docText(source: String, maxChars: Int = 30_000): String? {
        try {
            File(appDir, "corpus").walkTopDown().filter { it.isFile }.forEach { f ->
                try {
                    val t = f.readText(Charsets.UTF_8)
                    if (t.contains("Источник: $source")) return t.take(maxChars)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        val chunks = store.loadAllChunks().filter { it.source == source }
        if (chunks.isEmpty()) return null
        val strat = if (chunks.any { it.strategy == "structure" }) "structure" else chunks.first().strategy
        return chunks.filter { it.strategy == strat }.sortedBy { it.ord }
            .joinToString("\n\n") { it.text }.take(maxChars).ifBlank { null }
    }
}
