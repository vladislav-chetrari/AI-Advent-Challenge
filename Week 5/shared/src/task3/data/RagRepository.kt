package task3.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import task2.data.ModelCatalog
import task2.data.downloadFile
import task2.data.nowMillis
import task2.data.platformCpuThreads
import task2.data.platformFileExists
import task2.data.PromptBuilder
import task2.domain.AiModel
import task2.domain.ChatMessage
import task2.domain.EngineStatus
import task2.llama.LlamaBridge
import task3.db.DocumentEntity
import task3.db.RagDao
import task3.db.RagMessageEntity
import task3.db.SettingEntity
import task3.domain.DocInfo
import task3.domain.LlmChoice
import task3.domain.LlmChoice.Companion.toStored
import task3.domain.ScoredChunk
import task3.llama.EmbedBridge

/** Статус embedding-движка для UI. */
sealed interface EmbedState {
    data object Unset : EmbedState
    data class Downloading(val progress: Int) : EmbedState
    data object Loading : EmbedState
    data object Ready : EmbedState
    data class Error(val message: String) : EmbedState
}

/** Честный прогресс закачки: totalBytes == null — длина неизвестна, проценты не рисуем. */
data class DownloadProgress(val doneBytes: Long, val totalBytes: Long?) {
    val percent: Int? get() = totalBytes?.let { ((doneBytes * 100) / it).toInt().coerceIn(0, 100) }
    val doneMb: Long get() = doneBytes / 1024 / 1024
}

private const val K_LLM = "llm"
private const val K_EMBED = "embed"
private const val K_DEEPSEEK = "deepseek_key"
private const val K_PRESET = "gen_preset"

/**
 * SSOT задачи 3: настройки моделей, документы, индекс, переписка.
 * Файлы: подкаталоги llm и embed внутри modelsDir плюс modelsDir/rag.db.
 * Никакого авто-скачивания: грузим в память только то, что уже на диске.
 */
class RagRepository(
    private val genBridge: LlamaBridge,
    private val embBridge: EmbedBridge,
    private val dao: RagDao,
    private val modelsDir: String,
    private val scope: CoroutineScope,
) {
    // --- настройки ---
    private val _llmChoice = MutableStateFlow<LlmChoice?>(null)
    val llmChoice: StateFlow<LlmChoice?> = _llmChoice.asStateFlow()

    private val _embedId = MutableStateFlow<String?>(null)
    val embedId: StateFlow<String?> = _embedId.asStateFlow()

    private val _deepSeekKey = MutableStateFlow("")
    val deepSeekKey: StateFlow<String> = _deepSeekKey.asStateFlow()

    // --- пресет генерации Task 4 (дефолт — Balanced) ---
    private val _preset = MutableStateFlow(task3.domain.GenPreset.DEFAULT)
    val preset: StateFlow<task3.domain.GenPreset> = _preset.asStateFlow()

    // --- статусы ---
    private val _llmStatus = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    val llmStatus: StateFlow<EngineStatus> = _llmStatus.asStateFlow()

    private val _embedStatus = MutableStateFlow<EmbedState>(EmbedState.Unset)
    val embedStatus: StateFlow<EmbedState> = _embedStatus.asStateFlow()

    /** Прогресс скачивания по id модели (llm и embed в одной карте). */
    private val _downloading = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloading: StateFlow<Map<String, DownloadProgress>> = _downloading.asStateFlow()

    /** Какие файлы уже на диске. */
    private val _downloadedLlm = MutableStateFlow<Set<String>>(emptySet())
    val downloadedLlm: StateFlow<Set<String>> = _downloadedLlm.asStateFlow()

    private val _downloadedEmbed = MutableStateFlow<Set<String>>(emptySet())
    val downloadedEmbed: StateFlow<Set<String>> = _downloadedEmbed.asStateFlow()

    // --- документы ---
    private val _docs = MutableStateFlow<List<DocInfo>>(emptyList())
    val docs: StateFlow<List<DocInfo>> = _docs.asStateFlow()

    private val _indexProgress = MutableStateFlow<Map<String, Float?>>(emptyMap())
    val indexProgress: StateFlow<Map<String, Float?>> = _indexProgress.asStateFlow()

    // --- чат ---
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var idSeq = 1L
    private var loadedGenPath: String? = null
    private var loadedEmbPath: String? = null

    val embedModel: EmbedModel? get() = EmbedCatalog.resolve(_embedId.value)

    /** OOM и отмену пробрасываем, всё остальное — в ошибку. */
    private fun rethrowIfFatal(e: Throwable) {
        if (e is OutOfMemoryError || e is kotlinx.coroutines.CancellationException) throw e
    }

    fun llmFile(model: AiModel): String = "$modelsDir/llm/${model.fileName}"
    fun embedFile(model: EmbedModel): String = "$modelsDir/embed/${model.fileName}"

    suspend fun init() {
        _llmChoice.value = LlmChoice.fromStored(dao.getSetting(K_LLM))
        _embedId.value = dao.getSetting(K_EMBED)?.ifBlank { null }
        _deepSeekKey.value = dao.getSetting(K_DEEPSEEK).orEmpty()
        _preset.value = task3.domain.GenPreset.fromId(dao.getSetting(K_PRESET))
        refreshDownloaded()
        runCatching {
            _messages.value = dao.getMessages().map { ChatMessage(idSeq++, mapRole(it.role), it.text) }
        }
        refreshDocs()
        // Фоновая загрузка уже скачанного (без докачки): чат быстрее готов.
        val choice = _llmChoice.value
        if (choice is LlmChoice.Local) {
            val m = ModelCatalog.resolve(choice.id)
            if (m.id == choice.id && platformFileExists(llmFile(m))) {
                scope.launch { runCatching { ensureLlmLoaded() } }
            }
        }
        val emb = embedModel
        if (emb != null && platformFileExists(embedFile(emb))) {
            scope.launch { runCatching { ensureEmbedLoaded() } }
        }
    }

    private fun refreshDownloaded() {
        _downloadedLlm.value = ModelCatalog.ALL.filter { platformFileExists(llmFile(it)) }.map { it.id }.toSet()
        _downloadedEmbed.value = EmbedCatalog.ALL.filter { platformFileExists(embedFile(it)) }.map { it.id }.toSet()
        // Нет выбора эмбеддинга, но файл один — подхватить молча не можем:
        // выбор осознанный, делается в Настройках.
        if (_embedId.value != null && embedModel == null) {
            scope.launch {
                dao.setSetting(SettingEntity(K_EMBED, ""))
                _embedId.value = null
            }
        }
    }

    // --- выбор LLM ---

    suspend fun selectLlm(choice: LlmChoice?) {
        dao.setSetting(SettingEntity(K_LLM, choice.toStored()))
        _llmChoice.value = choice
        if (choice is LlmChoice.Local) {
            val m = ModelCatalog.resolve(choice.id)
            if (platformFileExists(llmFile(m))) {
                scope.launch { runCatching { ensureLlmLoaded() } }
            } else {
                _llmStatus.value = EngineStatus.Idle
            }
        } else {
            _llmStatus.value = EngineStatus.Idle
        }
    }

    suspend fun downloadLlm(model: AiModel) {
        if (_downloading.value.containsKey(model.id)) return
        // Метка сразу: второй тап/radio до первого прогресса не должен
        // запускать параллельную докачку в тот же .part (мешанина байтов).
        _downloading.update { it + (model.id to DownloadProgress(0, null)) }
        // Глобальный статус — только для выбранной модели, иначе прогресс
        // чужой докачки светится на выбранной карточке. Прогресс каждой
        // модели и так виден персонально через карту downloading.
        val isSelected = { (_llmChoice.value as? LlmChoice.Local)?.id == model.id }
        if (isSelected()) _llmStatus.value = EngineStatus.Downloading(0)
        try {
            downloadFile(model.downloadUrl, llmFile(model)) { done, total ->
                _downloading.update { it + (model.id to DownloadProgress(done, total)) }
                if (isSelected()) {
                    _llmStatus.value = EngineStatus.Downloading(total?.let { ((done * 100) / it).toInt().coerceIn(0, 100) } ?: 0)
                }
            }
            // Проверка размера: оборванный .litertlm рантайм всё равно
            // не откроет — лучше сказать сразу и убрать мусор.
            validateModelFile(llmFile(model), model.sizeMb)?.let { problem ->
                runCatching { deleteFile(llmFile(model)) }
                if (isSelected()) _llmStatus.value = EngineStatus.Error(problem)
                return
            }
        } catch (e: Exception) {
            if (isSelected()) _llmStatus.value = EngineStatus.Error("Не скачалась модель: ${e.message}")
            return
        } finally {
            _downloading.update { it - model.id }
        }
        refreshDownloaded()
        if (isSelected()) ensureLlmLoaded(model)
    }

    suspend fun deleteLlm(model: AiModel) {
        runCatching { deleteFile(llmFile(model)) }
        if (loadedGenPath == llmFile(model)) {
            genBridge.close()
            loadedGenPath = null
        }
        refreshDownloaded()
        if ((_llmChoice.value as? LlmChoice.Local)?.id == model.id) {
            // Удаляемая была выбрана — снимаем выбор, иначе UI висит на отсутствующем файле.
            selectLlm(null)
        }
    }

    /** Только загрузка в память, без докачки (файла нет — ошибка наружу). */
    suspend fun ensureLlmLoaded(model: AiModel = ModelCatalog.resolve((_llmChoice.value as? LlmChoice.Local)?.id)) {
        val dest = llmFile(model)
        if (!platformFileExists(dest)) {
            _llmStatus.value = EngineStatus.Error("Модель не скачана — скачай её в Настройках")
            return
        }
        // Размер уже проверен при скачивании; битый файл уронит load ниже
        // с понятной ошибкой рантайма.
        if (loadedGenPath != dest || !genBridge.isReady) {
            _llmStatus.value = EngineStatus.LoadingModel
            try {
                genBridge.load(dest, model.defaultCtx, platformCpuThreads())
                loadedGenPath = dest
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                _llmStatus.value = EngineStatus.Error("Не загрузилась в LiteRT: ${e.message}")
                return
            }
        }
        _llmStatus.value = EngineStatus.Ready
    }

    // --- выбор эмбеддингов ---

    suspend fun selectEmbed(id: String?) {
        val prev = _embedId.value
        if (prev == id) return
        dao.setSetting(SettingEntity(K_EMBED, id.orEmpty()))
        _embedId.value = id
        if (prev != null && id != prev) {
            // Вектора разных размерностей несопоставимы — индекс чистим честно.
            dao.clearChunks()
            dao.allDocs().forEach {
                dao.upsertDoc(it.copy(status = "ready", error = ""))
            }
            refreshDocs()
        }
        val emb = embedModel
        if (emb != null && platformFileExists(embedFile(emb))) {
            scope.launch { runCatching { ensureEmbedLoaded() } }
        } else {
            _embedStatus.value = EmbedState.Unset
        }
    }

    suspend fun downloadEmbed(model: EmbedModel) {
        if (_downloading.value.containsKey(model.id)) return
        // Метка сразу: см. downloadLlm.
        _downloading.update { it + (model.id to DownloadProgress(0, null)) }
        // Глобальный статус — только для выбранной модели, иначе прогресс
        // чужой докачки светится на выбранной карточке (баг со скриншота).
        val isSelected = { _embedId.value == model.id }
        if (isSelected()) _embedStatus.value = EmbedState.Downloading(0)
        try {
            downloadFile(model.downloadUrl, embedFile(model)) { done, total ->
                _downloading.update { it + (model.id to DownloadProgress(done, total)) }
                if (isSelected()) {
                    _embedStatus.value = EmbedState.Downloading(total?.let { ((done * 100) / it).toInt().coerceIn(0, 100) } ?: 0)
                }
            }
            // Проверка размера: оборванный бандл рантайм всё равно
            // не откроет — лучше сказать сразу и убрать мусор.
            validateModelFile(embedFile(model), model.sizeMb)?.let { problem ->
                runCatching { deleteFile(embedFile(model)) }
                if (isSelected()) _embedStatus.value = EmbedState.Error(problem)
                return
            }
        } catch (e: Exception) {
            if (isSelected()) _embedStatus.value = EmbedState.Error("Не скачалась: ${e.message}")
            return
        } finally {
            _downloading.update { it - model.id }
        }
        refreshDownloaded()
        if (_embedId.value == model.id) ensureEmbedLoaded(model)
    }

    suspend fun deleteEmbed(model: EmbedModel) {
        runCatching { deleteFile(embedFile(model)) }
        if (loadedEmbPath == embedFile(model)) {
            embBridge.close()
            loadedEmbPath = null
        }
        refreshDownloaded()
        if (_embedId.value == model.id) {
            // Выбор снимаем, но индекс НЕ трогаем: после повторной скачки
            // той же модели чанки снова станут usable (переиндексация не нужна).
            dao.setSetting(SettingEntity(K_EMBED, ""))
            _embedId.value = null
            _embedStatus.value = EmbedState.Unset
        }
    }

    suspend fun ensureEmbedLoaded(model: EmbedModel = embedModel ?: EmbedCatalog.DEFAULT) {
        val dest = embedFile(model)
        if (!platformFileExists(dest)) {
            _embedStatus.value = EmbedState.Error("Модель не скачана — скачай её в Настройках")
            return
        }
        // Размер уже проверен при скачивании; битый файл уронит load ниже
        // с понятной ошибкой рантайма.
        if (loadedEmbPath != dest || !embBridge.isReady) {
            _embedStatus.value = EmbedState.Loading
            try {
                embBridge.load(dest, platformCpuThreads())
                loadedEmbPath = dest
                // dim каталога — для подписи в UI; истина — embBridge.dim
                // (чанк хранит свой dim, поиск фильтрует по факту).
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                _embedStatus.value = EmbedState.Error("Не загрузилась: ${e.message}")
                return
            }
        }
        _embedStatus.value = EmbedState.Ready
    }

    // --- DeepSeek ключ ---

    suspend fun saveDeepSeekKey(key: String) {
        dao.setSetting(SettingEntity(K_DEEPSEEK, key.trim()))
        _deepSeekKey.value = key.trim()
    }

    // --- пресет генерации ---

    suspend fun selectPreset(preset: task3.domain.GenPreset) {
        dao.setSetting(SettingEntity(K_PRESET, preset.id))
        _preset.value = preset
    }

    // --- документы ---

    suspend fun refreshDocs() {
        runCatching {
            val chunks = dao.allChunks().groupBy { it.source }
            val entries = dao.allDocs().associateBy { it.source }
            _docs.value = (chunks.keys + entries.keys).map { src ->
                val list = chunks[src].orEmpty()
                val e = entries[src]
                DocInfo(
                    source = src,
                    title = list.firstOrNull()?.title ?: e?.title ?: src.substringAfterLast("/"),
                    chunks = list.size,
                    chars = list.sumOf { it.text.length.toLong() },
                    active = e?.active ?: true,
                    indexing = e?.status == "indexing",
                    error = e?.takeIf { it.status == "error" }?.error?.takeIf { it.isNotBlank() },
                )
            }.sortedBy { it.title.lowercase() }
        }
    }

    suspend fun addWikiDoc(input: String) {
        val emb = embedModel
        if (emb == null || !platformFileExists(embedFile(emb))) {
            return
        }
        val doc = try {
            WikiLoader.fetch(input)
        } catch (e: Exception) {
            dao.upsertDoc(
                DocumentEntity(
                    source = "wiki:$input",
                    title = input,
                    status = "error",
                    error = e.message ?: "ошибка загрузки",
                    updatedAt = nowMillis(),
                ),
            )
            refreshDocs()
            return
        }
        if (_indexProgress.value.containsKey(doc.source)) return
        dao.upsertDoc(
            DocumentEntity(doc.source, doc.title, "indexing", "", true, nowMillis()),
        )
        refreshDocs()
        _indexProgress.update { it + (doc.source to null) }
        try {
            try {
                ensureEmbedLoaded(emb)
            } catch (e: Throwable) {
                rethrowIfFatal(e)
                throw IllegalStateException("нет связи с embedding-моделью: ${e.message}")
            }
            if (_embedStatus.value !is EmbedState.Ready) {
                throw IllegalStateException("embedding-модель не готова")
            }
            // Чанки короче контекста эмбеддинг-модели (n_ctx=512): русский текст
            // токенизируется густо (~2-3 символа на токен), 1000 символов
            // регулярно не влезают и натив режет хвост. 1000 символов ~ 400 токенов.
            // Нарезка: статья -> разделы -> параграфы с указанием раздела.
            val chunks = StructureChunker(maxChars = 1000).chunk(doc)
            if (chunks.isEmpty()) throw IllegalStateException("текст не нарезался на чанки")
            // Переиспользование векторов неизменившихся текстов.
            val vectors = chunks.map { c ->
                val stored = runCatching { dao.findVectorByText(c.text) }.getOrNull()
                if (stored != null && stored.dim == embBridge.dim) {
                    stored.embedding.toFloatArray()
                } else {
                    null
                }
            }
            val missing = vectors.mapIndexedNotNull { i, v -> if (v == null) i else null }
            val computed = mutableMapOf<Int, FloatArray>()
            var done = 0
            for (batch in missing.chunked(16)) {
                val texts = batch.map { emb.docPrefix + chunks[it].text }
                val embBatch = embBridge.embed(texts)
                batch.forEachIndexed { k, idx -> computed[idx] = embBatch[k] }
                done += batch.size
                val p = done.toFloat() / missing.size.coerceAtLeast(1)
                _indexProgress.update { it + (doc.source to p) }
            }
            val entities = chunks.mapIndexed { i, c ->
                val v = vectors[i] ?: computed[i]!!
                task3.db.ChunkEntity(
                    id = "${c.id}#${emb.id}",
                    source = c.source,
                    title = c.title,
                    section = c.section,
                    ord = c.ord,
                    text = c.text,
                    dim = v.size,
                    embedding = v.toBlob(),
                )
            }
            dao.deleteChunksOf(listOf(doc.source))
            dao.upsertChunks(entities)
            dao.upsertDoc(DocumentEntity(doc.source, doc.title, "ready", "", true, nowMillis()))
        } catch (e: Throwable) {
            rethrowIfFatal(e)
            dao.upsertDoc(
                DocumentEntity(doc.source, doc.title, "error", e.message ?: "ошибка", true, nowMillis()),
            )
        } finally {
            _indexProgress.update { it - doc.source }
            refreshDocs()
        }
    }

    suspend fun setDocActive(source: String, active: Boolean) {
        if (_indexProgress.value.containsKey(source)) return
        runCatching {
            val all = dao.allDocs()
            val prev = all.firstOrNull { it.source == source } ?: return
            dao.upsertDoc(prev.copy(active = active))
            refreshDocs()
        }
    }

    suspend fun deleteDoc(source: String) {
        if (_indexProgress.value.containsKey(source)) return
        dao.deleteChunksOf(listOf(source))
        dao.deleteDocs(listOf(source))
        refreshDocs()
    }

    suspend fun docPreview(source: String, maxChars: Int = 3000): String {
        val chunks = runCatching { dao.chunksOf(source) }.getOrDefault(emptyList())
        if (chunks.isEmpty()) return "(текст недоступен)"
        return chunks.sortedBy { it.ord }.joinToString("\n\n") { it.text }.take(maxChars)
    }

    // --- чат ---

    suspend fun send(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty() || _llmStatus.value is EngineStatus.Generating) return
        val choice = _llmChoice.value ?: return

        val userMsg = ChatMessage(idSeq++, ChatMessage.Role.USER, clean)
        val draftId = idSeq++
        _messages.update { it + userMsg + ChatMessage(draftId, ChatMessage.Role.ASSISTANT, "") }
        runCatching {
            dao.insertMessage(task3.db.RagMessageEntity(role = "USER", text = clean, createdAt = nowMillis()))
        }
        _llmStatus.value = EngineStatus.Generating
        try {
            val history = _messages.value.dropLast(2)
            // Фаза 2: small-talk — свободный ответ без retrieval
            // (не дёргаем EmbeddingGemma, не подсовываем мусорный контекст).
            val context = if (QueryRouter.isSmallTalk(clean)) emptyList() else retrieve(clean)
            val answer = when (choice) {
                is LlmChoice.DeepSeek -> answerCloud(history, clean, context)
                is LlmChoice.Local -> answerLocal(ModelCatalog.resolve(choice.id), history, clean, context)
            }
            _messages.update { list ->
                list.map { if (it.id == draftId) it.copy(text = answer) else it }
            }
            runCatching {
                dao.insertMessage(task3.db.RagMessageEntity(role = "ASSISTANT", text = answer, createdAt = nowMillis()))
            }
            _llmStatus.value = EngineStatus.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            genBridge.cancel()
            _llmStatus.value = EngineStatus.Ready
        } catch (e: Throwable) {
            rethrowIfFatal(e)
            _llmStatus.value = EngineStatus.Error("Генерация упала: ${e.message}")
            _messages.update { list ->
                list.map {
                    if (it.id == draftId) it.copy(text = "Ошибка: ${e.message}") else it
                }
            }
        }
    }

    fun cancel() {
        genBridge.cancel()
        _llmStatus.value = EngineStatus.Ready
    }

    suspend fun clearChat() {
        _messages.value = emptyList()
        runCatching { dao.clearMessages() }
    }

    /**
     * Фаза 1: score-gating. Без порога out-of-domain запрос ("дрозды" при базе
     * про ворон) получал 5 мусорных чанков и вынужденный фолбэк.
     * Старт 0.55 — подобрать на 3-5 запросах для видео Task 4.
     * _lastMaxScore — диагностика для видео/демо (видно, что пресет влияет,
     * а дрозды просто вне базы).
     */
    companion object {
        const val MIN_SCORE: Float = 0.55f
    }

    private val _lastMaxScore = MutableStateFlow<Float?>(null)
    val lastMaxScore: StateFlow<Float?> = _lastMaxScore.asStateFlow()

    private suspend fun retrieve(query: String, topK: Int = 5): List<ScoredChunk> {
        val emb = embedModel ?: return emptyList()
        if (_embedStatus.value !is EmbedState.Ready) {
            runCatching { ensureEmbedLoaded(emb) }
            if (_embedStatus.value !is EmbedState.Ready) return emptyList()
        }
        val activeDocs = dao.allDocs().filter { it.active }.map { it.source }.toSet()
        val all = dao.allChunks().filter { it.source in activeDocs && it.dim == embBridge.dim }
        if (all.isEmpty()) return emptyList()
        val q = embBridge.embed(listOf(emb.queryPrefix + query)).first()
        val scored = cosineTopK(
            q,
            all.mapIndexed { i, e -> i to e.embedding.toFloatArray() },
            topK,
        )
        _lastMaxScore.value = scored.maxOfOrNull { it.second }
        // Гейт 1: косинус ниже порога — нерелевантно, отдаём пусто.
        val above = scored.filter { it.second >= MIN_SCORE }
        if (above.isEmpty()) return emptyList()
        val hits = above.map { (i, s) ->
            val e = all[i]
            ScoredChunk(e.title, e.section, e.source, e.text, s)
        }
        // Гейт 2 (hybrid-lite без новой модели): ни один корень запроса
        // не встретился в текстах топ-хитов — тоже мимо (ворон vs дрозд).
        if (!hasTokenOverlap(query, hits)) return emptyList()
        return hits
    }

    /**
     * Дешёвая keyword-проверка поверх dense: стемы запроса длиной 5+
     * против нижних текстов хитов. Пересечений нет — запрос не про базу.
     */
    private fun hasTokenOverlap(query: String, hits: List<ScoredChunk>): Boolean {
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

    private suspend fun answerLocal(
        model: AiModel,
        history: List<ChatMessage>,
        query: String,
        context: List<ScoredChunk>,
    ): String {
        ensureLlmLoaded(model)
        if (_llmStatus.value !is EngineStatus.Ready && _llmStatus.value !is EngineStatus.Generating) {
            throw IllegalStateException("локальная модель не готова")
        }
        // Маленькой модели (KV 1280–4096) полный контекст не влезет —
        // режем жёстче, чем для облака. Шаблон применяет сам LiteRT.
        // Пустой контекст (нет документов / small-talk) — свободный ответ
        // без RAG-ограничений, иначе "привет" упирался в "в документах нет".
        val preset = _preset.value
        val system: String
        val user: String
        if (context.isEmpty()) {
            // Фаза 4: пусто, но документы есть (= out-of-domain, а не small-talk) —
            // подсказываем модели темы базы, чтобы фолбэк предлагал, а не сухо отказывал.
            val topics = _docs.value.take(5).map { it.title }.filter { it.isNotBlank() }
            val hint = if (topics.isEmpty()) "" else
                " В локальной базе есть документы: ${topics.joinToString("; ")}. " +
                "Если вопрос не по ним — ответь свободно по общим знаниям, " +
                "а в конце одним предложением предложи спросить про документы из списка."
            system = PromptBuilder.chatSystem() + " " + preset.styleHint + hint
            user = PromptBuilder.chatUser(history = history.takeLast(6), userPrompt = query)
        } else {
            system = PromptBuilder.ragSystem(context.size) + " " + preset.styleHint
            user = PromptBuilder.chatUser(
                history = history.takeLast(6),
                userPrompt = "Контекст:\n${contextBlock(context, perHit = 600)}\n\nВопрос: $query",
            )
        }
        var acc = ""
        _llmStatus.value = EngineStatus.Generating
        genBridge.generateChat(system, user, preset.toGenParams()).collect { delta -> acc += delta }
        val text = PromptBuilder.cleanReply(acc).ifEmpty { acc.trim() }.ifEmpty { "(пустой ответ)" }
        return text + refsBlock(context)
    }

    private suspend fun answerCloud(
        history: List<ChatMessage>,
        query: String,
        context: List<ScoredChunk>,
    ): String {
        val key = _deepSeekKey.value
        if (key.isBlank()) throw IllegalStateException("Нет API-ключа DeepSeek — задай его в Настройках")
        val system = if (context.isEmpty()) {
            "Ты полезный ассистент."
        } else {
            PromptBuilder.ragSystem(context.size)
        }
        val user = if (context.isEmpty()) query else "Контекст:\n${contextBlock(context)}\n\nВопрос: $query"
        val hist = history.takeLast(12).map {
            (if (it.role == ChatMessage.Role.USER) "user" else "assistant") to it.text
        }
        return when (val r = DeepSeekClient.complete(system, user, hist, key)) {
            is DeepSeekResult.Ok -> r.text + refsBlock(context)
            is DeepSeekResult.HttpError -> "DeepSeek HTTP ${r.code}: ${r.detail}"
            is DeepSeekResult.NetworkError -> "DeepSeek: ${r.detail}"
        }
    }

    private fun contextBlock(context: List<ScoredChunk>, perHit: Int = 1200): String =
        context.mapIndexed { i, h ->
            "[${i + 1}] ${h.title}" +
                (if (h.section.isNotBlank()) " / ${h.section}" else "") +
                ":\n${h.text.take(perHit)}"
        }.joinToString("\n\n")

    private fun refsBlock(context: List<ScoredChunk>): String {
        if (context.isEmpty()) return ""
        return "\n\nИсточники:\n" + context.mapIndexed { i, h ->
            "[${i + 1}] ${h.title}" + (if (h.section.isNotBlank()) " / ${h.section}" else "")
        }.joinToString("\n")
    }

    private fun mapRole(role: String): ChatMessage.Role =
        if (role == "ASSISTANT") ChatMessage.Role.ASSISTANT else ChatMessage.Role.USER
}
