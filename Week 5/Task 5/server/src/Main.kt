package server

import core.api.ChatChoice
import core.api.ChatChoiceMessage
import core.api.ChatRequest
import core.api.ChatResponse
import core.api.DocListItem
import core.api.DocsRequest
import core.api.DocsResponse
import core.api.EmbedData
import core.api.EmbedRequest
import core.api.EmbedResponse
import core.api.ErrorBody
import core.api.ErrorEnvelope
import core.api.HealthResponse
import core.api.ModelItem
import core.api.ModelsResponse
import core.api.Usage
import core.llm.ChatMessage
import core.llm.DEFAULT_NUM_CTX
import core.llm.EmbedClient
import core.llm.EmbedDefaults
import core.llm.LlmResult
import core.llm.QwenClient
import core.rag.ChatMsg
import core.rag.Prompts
import core.rag.QueryRouter
import core.rag.RagStore
import core.rag.RawDoc
import core.rag.StoredChunk
import core.rag.StructureChunker
import core.rag.WikiLoader
import core.rag.l2normalize
import core.rag.retrieve
import core.util.estMessagesTokens
import core.util.estTokens
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// Конфиг через env. Дефолт LLM — gemma3:12b (~8 ГБ Q4, контекст модели 128k).
// На RTX 3060 12GB связка gemma3:12b + num_ctx=4096 встаёт впритык (~10 ГБ),
// при OOM QwenClient сам ретраит с вдвое меньшим ctx (до MIN_NUM_CTX=2048).
private data class Config(
    val port: Int = System.getenv("TASK5_PORT")?.toIntOrNull() ?: 8080,
    val ollamaUrl: String = System.getenv("OLLAMA_URL") ?: "http://localhost:11434",
    val apiKey: String = System.getenv("TASK5_API_KEY").orEmpty(),
    val llmModel: String = System.getenv("TASK5_LLM") ?: "gemma3:12b",
    val embedModel: String = System.getenv("TASK5_EMBED") ?: EmbedDefaults.MODEL,
    val numCtx: Int = System.getenv("TASK5_NUM_CTX")?.toIntOrNull() ?: DEFAULT_NUM_CTX,
    val maxTokensCap: Int = System.getenv("TASK5_MAX_TOKENS")?.toIntOrNull() ?: 1024,
    val modelsDir: String = System.getenv("TASK5_MODELS_DIR") ?: "models",
)

// HTTP-ошибка в OpenAI-обёртке.
private class AppError(val status: HttpStatusCode, val code: String, message: String) : Exception(message)

// Token bucket (in-memory, per IP): capacity запросов на windowMs.
private class RateLimiter(val capacity: Int, val windowMs: Long) {
    private data class Bucket(var tokens: Double, var updatedMs: Long)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    // true — пропустили, false — лимит исчерпан (ответить 429).
    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val b = buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), now) }
        synchronized(b) {
            val elapsed = (now - b.updatedMs).coerceAtLeast(0)
            b.tokens = (b.tokens + elapsed.toDouble() / windowMs * capacity).coerceAtMost(capacity.toDouble())
            b.updatedMs = now
            return if (b.tokens >= 1.0) {
                b.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }
}

fun main() {
    ensureUtf8Console()
    val cfg = Config()
    File(cfg.modelsDir).mkdirs()
    val store = RagStore(File(cfg.modelsDir, "rag.db").absolutePath)
    val qwen = QwenClient(model = cfg.llmModel, baseUrl = cfg.ollamaUrl, numCtx = cfg.numCtx)
    val embed = EmbedClient(model = cfg.embedModel, baseUrl = cfg.ollamaUrl)
    // Размерность эмбеддингов — зондом один раз на старте (истина — модель, а не каталог).
    var embedDim: Int? = null
    try {
        val rows = kotlinx.coroutines.runBlocking { embed.embed(listOf("probe")) }
        embedDim = rows.first().size
        println("[task5] embed dim = $embedDim (${cfg.embedModel})")
    } catch (e: Exception) {
        println("[task5] WARN: embed-зонд не прошёл (${e.message}) — повторим лениво на первом запросе")
    }

    // Стартовый чек Ollama (не фатально: сервер должен подниматься и без неё).
    try {
        val installed = kotlinx.coroutines.runBlocking { qwen.listModels() }
        if (installed.isEmpty()) {
            println("[task5] WARN: Ollama недоступна на ${cfg.ollamaUrl} — подними: `ollama serve`")
        } else {
            println("[task5] Ollama: ${installed.size} моделей: ${installed.take(8).joinToString()}")
            if (installed.none { it.startsWith(cfg.llmModel) }) {
                println("[task5] WARN: модели ${cfg.llmModel} нет — скачай: `ollama pull ${cfg.llmModel}`")
            }
        }
    } catch (e: Exception) {
        println("[task5] WARN: Ollama-чек упал: ${e.message}")
    }

    if (cfg.apiKey.isBlank()) {
        println("[task5] WARN: TASK5_API_KEY пуст — auth отключён (только для локальной отладки!)")
    }
    println("[task5] listen 0.0.0.0:${cfg.port} | llm=${cfg.llmModel} ctx=${cfg.numCtx} | embed=${cfg.embedModel}")
    println("[task5] health: curl http://localhost:${cfg.port}/health")

    embeddedServer(CIO, port = cfg.port, host = "0.0.0.0") {
        task5Module(cfg, store, qwen, embed, embedDimHolder = { embedDim })
    }.start(wait = true)
}

// Ленивый embedDim: holder читает стартовое значение, при null — зонд прямо в запросе.
private fun Application.task5Module(
    cfg: Config,
    store: RagStore,
    qwen: QwenClient,
    embed: EmbedClient,
    embedDimHolder: () -> Int?,
) {
    val apiJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    install(ContentNegotiation) { json(apiJson) }
    install(CallLogging)
    install(StatusPages) {
        exception<AppError> { call, cause ->
            call.respond(cause.status, ErrorEnvelope(ErrorBody(cause.code, cause.message ?: cause.code)))
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorEnvelope(ErrorBody("internal", cause.message ?: "internal error")),
            )
        }
    }

    val chatLimiter = RateLimiter(capacity = 30, windowMs = 60_000) // 30 req/min с IP
    val docsLimiter = RateLimiter(capacity = 5, windowMs = 60_000) // индексация тяжёлая
    val llmSlots = Semaphore(2) // RTX 3060: больше 2 параллельных 7B — деградация/OOM
    var lazyDim: Int? = embedDimHolder()

    suspend fun requireDim(): Int {
        lazyDim?.let { return it }
        try {
            val d = embed.probeDim()
            lazyDim = d
            return d
        } catch (e: Exception) {
            throw AppError(HttpStatusCode.BadGateway, "embed_unavailable", "embedding-модель недоступна: ${e.message}")
        }
    }

    fun ApplicationCall.clientIp(): String = request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
        ?: request.local.remoteHost

    suspend fun ApplicationCall.requireAuth() {
        if (cfg.apiKey.isBlank()) return
        val got = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim().orEmpty()
        if (got != cfg.apiKey) throw AppError(HttpStatusCode.Unauthorized, "unauthorized", "Нужен Authorization: Bearer <TASK5_API_KEY>")
    }

    suspend fun ApplicationCall.checkChatLimit() {
        if (!chatLimiter.allow(clientIp())) {
            response.headers.append("Retry-After", "60")
            throw AppError(HttpStatusCode.TooManyRequests, "rate_limited", "Превышен лимит 30 req/min, повтори через минуту")
        }
    }

    routing {
        get("/health") {
            val ollamaOk = try {
                qwen.listModels().isNotEmpty()
            } catch (_: Exception) {
                false
            }
            val docs = try {
                withContext(Dispatchers.IO) { store.allDocs() }
            } catch (_: Exception) {
                emptyList()
            }
            var chunks = 0
            try {
                withContext(Dispatchers.IO) {
                    for (d in docs) chunks += store.docStats(d.source).first
                }
            } catch (_: Exception) { }
            call.respond(
                HealthResponse(
                    llm = cfg.llmModel, embed = cfg.embedModel,
                    ollama_ok = ollamaOk, rag_docs = docs.size, rag_chunks = chunks,
                )
            )
        }

        get("/v1/models") {
            call.requireAuth()
            call.respond(
                ModelsResponse(
                    data = listOf(
                        ModelItem(id = cfg.llmModel),
                        ModelItem(id = cfg.embedModel),
                    )
                )
            )
        }

        post("/v1/chat/completions") {
            call.requireAuth()
            call.checkChatLimit()
            val req = call.receive<ChatRequest>()
            if (req.stream) {
                throw AppError(
                    HttpStatusCode.BadRequest, "stream_not_supported",
                    "stream:true пока не поддерживается, используйте stream:false",
                )
            }
            if (req.messages.isEmpty()) throw AppError(HttpStatusCode.BadRequest, "empty_messages", "messages пуст")
            val lastUser = req.messages.lastOrNull { it.role == "user" }
                ?: throw AppError(HttpStatusCode.BadRequest, "no_user_message", "Нет сообщения с role=user")
            val query = lastUser.content.trim()
            if (query.isEmpty()) throw AppError(HttpStatusCode.BadRequest, "empty_query", "Пустой запрос")
            if (query.length > 32_000) {
                throw AppError(HttpStatusCode.BadRequest, "query_too_large", "Запрос > 32000 символов")
            }
            val temp = req.temperature.coerceIn(0.0, 2.0)
            val maxTokens = req.max_tokens.coerceIn(1, cfg.maxTokensCap)

            // История (всё кроме последнего user) -> ChatMsg для Prompts.
            val history: List<ChatMsg> = req.messages.dropLast(1).takeLast(Prompts.MAX_MESSAGES + 2).map {
                val role = when (it.role) {
                    "user", "assistant", "system" -> it.role
                    else -> "user"
                }
                ChatMsg(role, it.content)
            }

            // Retrieval: small-talk — мимо (не дёргаем embed-модель зря).
            val context = if (QueryRouter.isSmallTalk(query)) {
                emptyList()
            } else {
                try {
                    val dim = requireDim()
                    val q = withContext(Dispatchers.IO) {
                        l2normalize(embed.embed(listOf(query)).first())
                    }
                    val chunks = withContext(Dispatchers.IO) { store.activeChunks(dim) }
                    if (chunks.isEmpty()) emptyList() else retrieve(query, q, chunks)
                } catch (e: AppError) {
                    throw e
                } catch (e: Exception) {
                    // Эмбеддинги чихнули — чат не должен умирать: свободный ответ без контекста.
                    println("[task5] WARN: retrieval упал (${e.message}), отвечаем без контекста")
                    emptyList()
                }
            }

            val system: String
            val user: String
            if (context.isEmpty()) {
                val topics = try {
                    withContext(Dispatchers.IO) { store.allDocs() }.take(5).map { it.title }.filter { it.isNotBlank() }
                } catch (_: Exception) {
                    emptyList()
                }
                val hint = if (topics.isEmpty()) "" else
                    " В локальной базе есть документы: ${topics.joinToString("; ")}. " +
                    "Если вопрос не по ним — ответь свободно по общим знаниям, " +
                    "а в конце одним предложением предложи спросить про документы из списка."
                system = Prompts.chatSystem() + hint
                user = Prompts.chatUser(history = history, userPrompt = query)
            } else {
                system = Prompts.ragSystem(context.size)
                user = Prompts.chatUser(
                    history = history,
                    userPrompt = "Контекст:\n${Prompts.contextBlock(context)}\n\nВопрос: $query",
                )
            }

            // Pre-flight max context: грубая оценка, точное usage отдаёт Ollama.
            val promptEst = estTokens(system) + estTokens(user)
            if (promptEst + maxTokens > cfg.numCtx) {
                throw AppError(
                    HttpStatusCode.BadRequest, "context_too_large",
                    "Контекст ~$promptEst + max_tokens $maxTokens > num_ctx ${cfg.numCtx}: " +
                        "укороти историю/контекст или уменьши max_tokens",
                )
            }

            if (!llmSlots.tryAcquire()) {
                throw AppError(HttpStatusCode.TooManyRequests, "overloaded", "Модель занята (2 параллельных запроса), повтори через секунды")
            }
            try {
                val result = withTimeout(180_000) {
                    qwen.complete(
                        listOf(ChatMessage("system", system), ChatMessage("user", user)),
                        temperatureOverride = temp,
                    )
                }
                when (result) {
                    is LlmResult.Ok -> {
                        val text = Prompts.cleanReply(result.text).ifEmpty { result.text.trim() }.ifEmpty { "(пустой ответ)" }
                        val answer = text + Prompts.refsBlock(context)
                        val u = result.usage
                        val pt = if (u.promptTokens > 0) u.promptTokens else promptEst
                        val ct = if (u.completionTokens > 0) u.completionTokens else estTokens(answer)
                        call.respond(
                            ChatResponse(
                                id = "chatcmpl-${System.nanoTime()}",
                                created = System.currentTimeMillis() / 1000,
                                model = cfg.llmModel,
                                choices = listOf(ChatChoice(message = ChatChoiceMessage(content = answer))),
                                usage = Usage(pt, ct, pt + ct),
                            )
                        )
                    }
                    is LlmResult.HttpError -> throw AppError(
                        HttpStatusCode.BadGateway, "ollama_http_${result.code}",
                        "Ollama HTTP ${result.code}: ${result.detail}",
                    )
                    is LlmResult.NetworkError -> throw AppError(
                        HttpStatusCode.BadGateway, "ollama_unreachable",
                        "Нет связи с Ollama (${cfg.ollamaUrl}): ${result.detail}. Проверь `ollama serve`.",
                    )
                    LlmResult.Empty -> throw AppError(HttpStatusCode.BadGateway, "empty_reply", "Модель вернула пустой ответ")
                }
            } finally {
                llmSlots.release()
            }
        }

        post("/v1/embeddings") {
            call.requireAuth()
            call.checkChatLimit()
            val req = call.receive<EmbedRequest>()
            val texts: List<String> = when (val inp = req.input) {
                null -> throw AppError(HttpStatusCode.BadRequest, "empty_input", "Поле input обязательно (string | string[])")
                else -> if (inp is kotlinx.serialization.json.JsonPrimitive && inp.isString) {
                    listOf(inp.content)
                } else {
                    try {
                        inp.jsonArray.map { it.jsonPrimitive.content }
                    } catch (_: Exception) {
                        throw AppError(HttpStatusCode.BadRequest, "bad_input", "input: string или массив строк")
                    }
                }
            }
            if (texts.isEmpty()) throw AppError(HttpStatusCode.BadRequest, "empty_input", "Пустой input")
            if (texts.size > EmbedClient.MAX_INPUTS) {
                throw AppError(HttpStatusCode.BadRequest, "input_too_large", "Текстов ${texts.size} > ${EmbedClient.MAX_INPUTS}")
            }
            for (t in texts) {
                if (t.length > EmbedClient.MAX_CHARS) {
                    throw AppError(HttpStatusCode.BadRequest, "input_too_large", "Текст > ${EmbedClient.MAX_CHARS} символов")
                }
            }
            val vecs = try {
                withContext(Dispatchers.IO) { embed.embed(texts) }
            } catch (e: Exception) {
                throw AppError(HttpStatusCode.BadGateway, "embed_failed", "Embedding-модель недоступна: ${e.message}")
            }
            val promptEst = texts.sumOf { estTokens(it) }
            call.respond(
                EmbedResponse(
                    data = vecs.mapIndexed { i, v -> EmbedData(index = i, embedding = v.toList()) },
                    model = cfg.embedModel,
                    usage = Usage(prompt_tokens = promptEst, total_tokens = promptEst),
                )
            )
        }

        post("/v1/docs") {
            call.requireAuth()
            if (!docsLimiter.allow(call.clientIp())) {
                call.response.headers.append("Retry-After", "60")
                throw AppError(HttpStatusCode.TooManyRequests, "rate_limited", "Индексация: не чаще 5/мин")
            }
            val req = call.receive<DocsRequest>()
            val doc = when (req.type) {
                "text" -> {
                    val text = req.input.trim()
                    if (text.length < 50) throw AppError(HttpStatusCode.BadRequest, "text_too_short", "Текст < 50 символов")
                    if (text.length > 200_000) throw AppError(HttpStatusCode.BadRequest, "text_too_large", "Текст > 200000 символов")
                    val title = req.title.trim().ifEmpty { text.take(60).substringBefore("\n") }
                    val slug = title.lowercase().replace(Regex("[^a-zа-яё0-9]+"), "-").trim('-').take(60).ifEmpty { "doc" }
                    RawDoc(source = "user/$slug", title = title, text = text)
                }
                "wiki" -> {
                    if (req.input.isBlank()) throw AppError(HttpStatusCode.BadRequest, "empty_input", "Нужен input: название или URL статьи")
                    try {
                        withContext(Dispatchers.IO) { WikiLoader.fetch(req.input.trim()) }
                    } catch (e: IllegalStateException) {
                        throw AppError(HttpStatusCode.BadRequest, "wiki_failed", e.message ?: "wiki failed")
                    }
                }
                else -> throw AppError(HttpStatusCode.BadRequest, "bad_type", "type: text | wiki")
            }
            val chunks = StructureChunker(maxChars = 1000).chunk(doc)
            if (chunks.isEmpty()) throw AppError(HttpStatusCode.BadRequest, "no_chunks", "Текст не нарезался на чанки")
            if (chunks.size > 500) throw AppError(HttpStatusCode.BadRequest, "doc_too_large", "Чанков ${chunks.size} > 500")
            val dim = requireDim()
            try {
                withContext(Dispatchers.IO) {
                    val cached = mutableMapOf<Int, FloatArray>()
                    val missing = mutableListOf<Int>()
                    for ((i, c) in chunks.withIndex()) {
                        val v = store.findVectorByText(c.text, dim)
                        if (v != null) cached[i] = v else missing += i
                    }
                    val computed = mutableMapOf<Int, FloatArray>()
                    if (missing.isNotEmpty()) {
                        // Большие статьи (напр. «Эстония» — 86 чанков) не влезают в
                        // EmbedClient.MAX_INPUTS за один вызов — режем на батчи.
                        // Внутри embed() и так батчи по 16, здесь — только защита лимита.
                        val vecs = missing.map { chunks[it].text }
                            .chunked(EmbedClient.MAX_INPUTS)
                            .flatMap { embed.embed(it) }
                        for ((k, idx) in missing.withIndex()) computed[idx] = l2normalize(vecs[k])
                    }
                    val stored = chunks.mapIndexed { i, c ->
                        StoredChunk(
                            id = "${c.id}#${embedModelShort(cfg.embedModel)}", source = c.source,
                            title = c.title, section = c.section, ord = c.ord, text = c.text,
                            dim = (cached[i] ?: computed[i]!!).size,
                            embedding = cached[i] ?: computed[i]!!,
                        )
                    }
                    store.replaceChunks(doc.source, stored)
                    store.upsertDoc(doc.source, doc.title)
                }
            } catch (e: AppError) {
                throw e
            } catch (e: Exception) {
                throw AppError(HttpStatusCode.BadGateway, "index_failed", "Индексация упала: ${e.message}")
            }
            call.respond(
                HttpStatusCode.Accepted,
                DocsResponse(id = doc.source, title = doc.title, chunks = chunks.size),
            )
        }

        get("/v1/docs") {
            call.requireAuth()
            val docs = try {
                withContext(Dispatchers.IO) { store.allDocs() }
            } catch (e: Exception) {
                throw AppError(HttpStatusCode.InternalServerError, "db_failed", "БД недоступна: ${e.message}")
            }
            val items = withContext(Dispatchers.IO) {
                docs.map { d ->
                    val (n, chars) = try {
                        store.docStats(d.source)
                    } catch (_: Exception) {
                        0 to 0L
                    }
                    DocListItem(id = d.source, title = d.title, chunks = n, chars = chars, active = d.active)
                }
            }
            call.respond(items)
        }

        delete("/v1/docs/{id...}") {
            call.requireAuth()
            val id = call.parameters.getAll("id")?.joinToString("/").orEmpty()
            if (id.isBlank()) throw AppError(HttpStatusCode.BadRequest, "empty_id", "Нужен id документа")
            withContext(Dispatchers.IO) { store.deleteDoc(id) }
            call.respond(mapOf("deleted" to id))
        }
    }
}

private fun embedModelShort(model: String): String = model.replace(Regex("[^a-zA-Z0-9]+"), "")

// Windows-консоль по умолчанию CP866 — переключаем stdout/stderr на UTF-8,
// иначе русские логи превращаются в "???". На Linux no-op.
private fun ensureUtf8Console() {
    if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
        runCatching {
            ProcessBuilder("cmd", "/c", "chcp", "65001")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    runCatching {
        System.setOut(java.io.PrintStream(java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.FileDescriptor.out)), true, Charsets.UTF_8))
    }
    runCatching {
        System.setErr(java.io.PrintStream(java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.FileDescriptor.err)), true, Charsets.UTF_8))
    }
}
