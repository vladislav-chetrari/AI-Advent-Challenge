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

    data class IndexOutcome(val stats: IndexStats, val docs: Int, val skipped: Int)

    // Индексация папки (совместимость с Task 1 / CLI).
    suspend fun reindex(
        corpusDir: File,
        strategy: String,
        onLog: (String) -> Unit = {},
    ): IndexOutcome = reindexRoots(listOf(corpusDir), strategy, onLog)

    // Главный вход: явно выбранные пользователем файлы/папки (книга, статьи).
    suspend fun reindexRoots(
        roots: List<File>,
        strategy: String,
        onLog: (String) -> Unit = {},
    ): IndexOutcome = withContext(Dispatchers.IO) {
        val (docs, skipped) = DocumentLoader.loadRoots(roots)
        onLog("Документов: ${docs.size}, пропущено: $skipped")
        if (docs.isEmpty()) {
            return@withContext IndexOutcome(
                IndexStats(strategy, 0, 0, 0, 0, embedder().dim, embedder().name, 0), 0, skipped
            )
        }
        val stats = indexDocs(docs, strategy, onLog)
        IndexOutcome(stats, docs.size, skipped)
    }

    // Ядро индексации: готовые RawDoc -> чанки -> эмбеддинги -> upsert.
    // Сюда же придут документы из WikiLoader и будущих источников.
    // Полная перезапись стратегии (для CLI index/compare): чужие документы не трогаем
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

    // Статья Википедии -> файл локального корпуса (appDir/corpus).
    // Дальше статья — обычный файл: попадает в выбор источников и индекс.
    suspend fun fetchWikiAndSave(input: String): File = withContext(Dispatchers.IO) {
        val doc = WikiLoader.fetch(input)
        val dir = File(ApiKeyProvider.appDir(), "corpus")
        dir.mkdirs()
        val f = File(dir, WikiLoader.fileNameFor(doc))
        f.writeText("# ${doc.title}\n\nИсточник: ${doc.source}\n\n${doc.text}", Charsets.UTF_8)
        f
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

    // Task 1 (усиление): индексируем корпус обеими стратегиями и гоняем
    // одни и те же пробные запросы — видно разницу в релевантности top-1.
    suspend fun compare(
        corpusDir: File,
        probes: List<String> = DEFAULT_PROBES,
        onLog: (String) -> Unit = {},
    ): CompareResult = compareRoots(listOf(corpusDir), probes, onLog)

    suspend fun compareRoots(
        roots: List<File>,
        probes: List<String> = DEFAULT_PROBES,
        onLog: (String) -> Unit = {},
    ): CompareResult {
        suspend fun run(strategy: String): StrategyReport {
            onLog("== Стратегия: $strategy ==")
            val outcome = reindexRoots(roots, strategy, onLog)
            val probeHits = LinkedHashMap<String, List<String>>()
            for (q in probes) {
                val hits = search(q, strategy, topK = 3)
                probeHits[q] = hits.map { h ->
                    val sec = h.chunk.section.ifBlank { "—" }
                    "${h.chunk.title} / $sec (${"%.3f".format(h.score)})"
                }
            }
            return StrategyReport(outcome.stats, probeHits)
        }
        val fixed = run("fixed")
        val structure = run("structure")
        return CompareResult(fixed, structure)
    }

    // RAG-ответ: retrieve topK -> промпт с контекстом [S1..Sn] -> DeepSeek.
    // onlySources ограничивает контекст активными документами.
    // Дефолт "both": документы с разными стратегиями ищутся одним запросом.
    // Задел на Task 2-5: rerank/фильтр порога встраиваются между search и buildPrompt.
    suspend fun ask(
        query: String,
        strategy: String = "both",
        topK: Int = 8,
        apiKey: String? = null,
        onlySources: Set<String>? = null,
    ): RagAnswer {
        val hits = search(query, strategy, topK, onlySources)
        if (hits.isEmpty()) {
            return RagAnswer("База знаний пуста — добавь документы через «+» в разделе «База знаний».", emptyList())
        }
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
            is LlmResult.Ok -> RagAnswer(r.text, hits)
            is LlmResult.HttpError -> RagAnswer("DeepSeek HTTP ${r.code}: ${r.detail.take(200)}", hits)
            is LlmResult.NetworkError -> RagAnswer("Сеть: ${r.detail.take(200)}", hits)
            LlmResult.Empty -> RagAnswer("Пустой ответ модели.", hits)
        }
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

    // Убранные из списка источники: чистим их чанки из обеих стратегий.
    fun removeSources(sources: Collection<String>) {
        store.deleteSources(sources)
        val cur = sourcesStore.loadState()
        sourcesStore.save(cur.roots.filter { it !in sources }, cur.inactive - sources.toSet())
    }

    // Текст документа для просмотра: абсолютный путь, файл корпуса или поиск по корням.
    fun docText(source: String, maxChars: Int = 30_000): String? {
        val direct = File(source)
        if (direct.isFile) return direct.readTextSafe()?.take(maxChars)
        val roots = sourcesStore.load().map { File(it) }
        // сначала файлы корпуса (туда сохраняются wiki-статьи)
        val corpus = File(ApiKeyProvider.appDir(), "corpus")
        corpus.walkTopDown().filter { it.isFile && (it.name == source.substringAfterLast("/") || it.absolutePath.endsWith(source)) }
            .firstOrNull()?.readTextSafe()?.let { return it.take(maxChars) }
        for (r in roots) {
            if (!r.exists()) continue
            if (r.isFile && (r.absolutePath.endsWith(source) || r.name == source.substringAfterLast("/"))) {
                r.readTextSafe()?.let { return it.take(maxChars) }
            }
            if (r.isDirectory) {
                r.walkTopDown().filter { it.isFile }.firstOrNull { f ->
                    try {
                        r.toURI().relativize(f.toURI()).path.trimEnd('/') == source
                    } catch (_: Exception) {
                        false
                    }
                }?.readTextSafe()?.let { return it.take(maxChars) }
            }
        }
        return null
    }

    private fun File.readTextSafe(): String? = try {
        readText(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
