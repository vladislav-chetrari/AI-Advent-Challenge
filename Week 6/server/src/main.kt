package server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import core.rag.ChatStore
import core.rag.RagMessage
import core.rag.RagService
import core.rag.ReviewFreshness
import core.rag.ReviewResult
import core.support.CrmImportResult
import core.support.CrmProjectBrief
import core.support.CrmState
import core.support.CrmTools
import core.support.McpServer
import core.support.RawIssue
import core.support.SUPPORT_TOP_K
import core.support.SupportReply
import core.support.SupportService
import core.support.SupportSessions
import core.support.SupportTicket
import core.support.TICKET_STATUSES
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// VPS-сервер ревью для Task 2: держит rag.db/projects.json + Ollama рядом,
// ключ DeepSeek НЕ хранит — прилетает per-request в X-LLM-Key из GH Secrets.
//   PORT=8080 VPS_TOKEN=xxx ./kotlin run --module server
//   POST /health -> ok
//   POST /review {diff, project?, repoUrl?, repoToken?, maxFiles?, refresh?} -> ReviewResult JSON
//   Headers: Authorization: Bearer <VPS_TOKEN>, X-LLM-Key: <DEEPSEEK_API_KEY>
//   project — имя в RAG-индексе VPS; repoUrl — git-URL вызывающего проекта
//   (сервер сам склонирует при первом обращении и подтянет при следующих).
//   repoToken — опционально, только для приватных репозиториев (в памяти, не хранится).
@Serializable
private data class ReviewRequest(
    val diff: String = "",
    val project: String? = null,
    val repoUrl: String? = null,
    val repoToken: String? = null,
    val maxFiles: Int = 15,
    // Ревью по умолчанию НЕ трогает индекс: индексация — отдельная ночная задача.
    val refresh: Boolean = false,
    // Если проект вообще не зарегистрирован — клон + первичная индексация (долго).
    val ensureIfMissing: Boolean = true,
)

@Serializable
private data class IndexRequest(
    val project: String? = null,
    val repoUrl: String? = null,
    val repoToken: String? = null,
)

// ===== Task 3: поддержка пользователей =====

// CRM-выгрузка из GitHub Issues (её собирает ночной workflow support-index).
// Сервер сам разбирает front-matter тикетов и кладёт их в RAG-индекс.
@Serializable
private data class CrmImportRequest(
    val project: String = "",
    val issues: List<RawIssue> = emptyList(),
    // Пустая выгрузка НЕ затирает CRM: случайный запрос без тела стирал бы все
    // тикеты. Очистить снимок можно осознанно — forceEmpty.
    val forceEmpty: Boolean = false,
)

// Локальная правка тикета: «разработчик взял в работу», «починил», «эскалировали».
@Serializable
private data class CrmPatchRequest(
    val project: String = "",
    val ticketId: String = "",
    val status: String? = null,
    val assignee: String? = null,
    val comment: String? = null,
    // Непустое значение = эскалировать обращение с этой причиной.
    val escalate: String? = null,
)

// Одно сообщение пользователя в диалоге по тикету.
@Serializable
private data class SupportAskRequest(
    val project: String = "",
    // chatId == номер тикета: диалог и обращение — одна сущность.
    val chatId: String = "",
    val message: String = "",
    val topK: Int = SUPPORT_TOP_K,
)

// Публичный запрос из чата в браузере: пользователю не нужен ни токен, ни ключ.
@Serializable
private data class SupportChatRequest(
    val project: String = "",
    val chatId: String = "",
    val message: String = "",
)

private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; isLenient = true }
private val outJson = Json { prettyPrint = true; encodeDefaults = true }

// Логи прогресса ревью: без них запрос на 5+ минут выглядит как зависший.
// Ключи и токены сюда не попадают — только пути, счётчики и тайминги.
private fun log(msg: String) {
    println("[${java.time.LocalDateTime.now().toString().take(19)}] $msg")
    System.out.flush()
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val token = System.getenv("VPS_TOKEN")?.trim().orEmpty()
    require(token.isNotEmpty()) { "нужен VPS_TOKEN в env" }
    val svc = RagService()
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newFixedThreadPool(4)
    server.createContext("/health") { ex ->
        val b = "ok".toByteArray()
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }
    server.createContext("/review") { ex ->
        try {
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, -1)
                return@createContext
            }
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            if (auth != "Bearer $token") {
                ex.sendResponseHeaders(401, -1)
                return@createContext
            }
            // Ключ только из заголовка, в логи/диск не пишем.
            val llmKey = ex.requestHeaders.getFirst("X-LLM-Key")?.trim()?.takeIf { it.isNotBlank() }
            val body = ex.requestBody.bufferedReader().readText()
            val req = try {
                json.decodeFromString(ReviewRequest.serializer(), body)
            } catch (e: Exception) {
                val msg = "bad request: ${e.message}".toByteArray()
                ex.sendResponseHeaders(400, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            if (req.diff.isBlank()) {
                val msg = "empty diff".toByteArray()
                ex.sendResponseHeaders(400, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val res: ReviewResult = runBlocking {
                val t0 = System.currentTimeMillis()
                val r = svc.reviewDiff(
                    req.project, req.diff, apiKey = llmKey, maxFiles = req.maxFiles.coerceIn(1, 50),
                    refresh = req.refresh, ensureIfMissing = req.ensureIfMissing,
                    repoUrl = req.repoUrl, repoToken = req.repoToken,
                    onLog = { m -> log(m) },
                )
                log("ревью готово за ${(System.currentTimeMillis() - t0) / 1000}с: файлов ${r.filesReviewed}, inline ${r.comments.size}")
                r
            }
            val out = outJson.encodeToString(ReviewResult.serializer(), res).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        } catch (e: Exception) {
            val msg = "review failed: ${e.message}".take(500).toByteArray()
            try {
                ex.sendResponseHeaders(500, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
            } catch (_: Exception) {
            }
        } finally {
            ex.close()
        }
    }
    // Тяжёлая индексация доков — отдельная задача (ночной workflow), не часть ревью.
    server.createContext("/index") { ex ->
        try {
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, -1)
                return@createContext
            }
            if (ex.requestHeaders.getFirst("Authorization").orEmpty() != "Bearer $token") {
                ex.sendResponseHeaders(401, -1)
                return@createContext
            }
            val repoToken = ex.requestHeaders.getFirst("X-Repo-Token")?.trim()?.takeIf { it.isNotBlank() }
            val body = ex.requestBody.bufferedReader().readText()
            val req = try {
                json.decodeFromString(IndexRequest.serializer(), body)
            } catch (e: Exception) {
                val msg = "bad request: ${e.message}".toByteArray()
                ex.sendResponseHeaders(400, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val t0 = System.currentTimeMillis()
            val fresh = runBlocking {
                svc.indexProject(req.project, req.repoUrl, repoToken, onLog = { m -> log(m) })
            }
            log("индексация завершена за ${(System.currentTimeMillis() - t0) / 1000}с")
            val out = outJson.encodeToString(ReviewFreshness.serializer(), fresh).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        } catch (e: Exception) {
            val msg = "index failed: ${e.message}".take(500).toByteArray()
            try {
                ex.sendResponseHeaders(500, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
            } catch (_: Exception) {
            }
        } finally {
            ex.close()
        }
    }
    // ===== Task 3: ассистент поддержки =====
    //
    // CRM — это MCP-сервер на /mcp; бот ходит в него по MCP, а не зовёт Kotlin-функции.
    // Тикеты ежедневно приезжают из GitHub Issues через /crm/import (ночной workflow).
    val defaultProject = System.getenv("SUPPORT_PROJECT")?.trim()?.takeIf { it.isNotBlank() } ?: "CallowFellow"
    val crm = CrmTools(defaultProject) { p, q, l -> SupportService.searchTicketRefs(svc, p, q, l) }
    val mcpServer = McpServer(crm.registry())
    // История диалогов живёт рядом с CRM: отдельный chats.json, чтобы не мешать
    // чатам десктоп-ассистента разработчика.
    val supportChats = ChatStore(svcAppDir(), "support_chats.json")
    val throttle = Throttle()
    // Диалог помнит, какое обращение обсуждаем: продолжение разговора
    // («а сроки какие?») не должно терять контекст тикета.
    val sessions = SupportSessions()

    // POST /mcp — CRM как MCP-сервер (JSON-RPC 2.0).
    server.createContext("/mcp") { ex ->
        try {
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, -1)
                return@createContext
            }
            if (!authed(ex, token)) return@createContext
            val body = ex.requestBody.bufferedReader().readText()
            val out = runBlocking { mcpServer.handle(body) }
            if (out.isEmpty()) {
                // Уведомление (без id) — ответа не положено, но соединение закрываем.
                ex.sendResponseHeaders(202, -1)
            } else {
                val b = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
        } catch (e: Exception) {
            val msg = "mcp failed: ${e.message}".take(500).toByteArray()
            try {
                ex.sendResponseHeaders(500, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
            } catch (_: Exception) {
            }
        } finally {
            ex.close()
        }
    }

    // POST /crm/import — выгрузка тикетов из GitHub + переиндексация их в RAG.
    server.createContext("/crm/import") { ex ->
        try {
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, -1)
                return@createContext
            }
            if (!authed(ex, token)) return@createContext
            val body = ex.requestBody.bufferedReader().readText()
            val req = try {
                json.decodeFromString(CrmImportRequest.serializer(), body)
            } catch (e: Exception) {
                val msg = "bad request: ${e.message}".toByteArray()
                ex.sendResponseHeaders(400, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val project = req.project.trim().ifBlank { defaultProject }
            if (req.issues.isEmpty() && !req.forceEmpty) {
                // Не даём случайному пустому запросу обнулить CRM.
                val store0 = crm.store(project)
                val kept = store0.all().size
                val msg = "в выгрузке 0 тикетов — снимок CRM не тронут (осталось $kept). " +
                    "Чтобы очистить CRM осознанно, добавь forceEmpty: true"
                log(msg)
                val b = msg.toByteArray()
                ex.sendResponseHeaders(400, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                return@createContext
            }
            val t0 = System.currentTimeMillis()
            val res = runBlocking {
                val store = crm.store(project)
                val r = store.import(req.issues, onLog = { m -> log(m) })
                // Тикеты кладём в тот же векторный индекс, что и FAQ: по описанию
                // проблемы находятся похожие обращения, а не только «по номеру».
                // Индексация — best-effort: без неё CRM и точный доступ к тикету
                // работают, пропадает только семантический поиск по обращениям.
                runCatching {
                    SupportService.indexTickets(svc, project, store.all(), onLog = { m -> log(m) })
                }.onFailure { log("тикеты импортированы, но не проиндексированы: ${it.message}") }
                r
            }
            log("CRM проекта $project: импортировано ${res.imported}, открытых ${res.open}, за ${(System.currentTimeMillis() - t0) / 1000}с")
            sendJson(ex, 200, outJson.encodeToString(CrmImportResult.serializer(), res))
        } catch (e: Exception) {
            fail(ex, "crm import failed", e)
        } finally {
            ex.close()
        }
    }

    // POST /crm/tickets — текущее состояние CRM (после локальных правок).
    server.createContext("/crm/tickets") { ex ->
        try {
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, -1)
                return@createContext
            }
            if (!authed(ex, token)) return@createContext
            val body = ex.requestBody.bufferedReader().readText()
            val project = try {
                json.decodeFromString(CrmPatchRequest.serializer(), body).project.trim().ifBlank { defaultProject }
            } catch (_: Exception) {
                defaultProject
            }
            // project="*" — показать, какие проекты вообще есть в CRM. Нужен, когда
            // имя проекта задано секретом: в логах GitHub оно замаскировано.
            if (project == "*") {
                val briefs = crm.knownProjects().map { p ->
                    val st = crm.store(p)
                    val all = st.all()
                    CrmProjectBrief(
                        project = p,
                        importedAt = st.importedAt(),
                        open = all.count { it.status in setOf("open", "in-progress", "needs-info") },
                        total = all.size,
                    )
                }
                sendJson(
                    ex, 200,
                    outJson.encodeToString(
                        CrmState.serializer(),
                        CrmState(project = "*", projects = briefs),
                    ),
                )
                return@createContext
            }
            val store = crm.store(project)
            val all = store.all()
            sendJson(
                ex, 200,
                outJson.encodeToString(
                    CrmState.serializer(),
                    CrmState(
                        project = project,
                        importedAt = store.importedAt(),
                        open = all.count { it.status in setOf("open", "in-progress", "needs-info") },
                        escalated = all.count { it.escalated },
                        tickets = all.sortedByDescending { it.id.toIntOrNull() ?: 0 },
                    ),
                ),
            )
        } catch (e: Exception) {
            fail(ex, "crm tickets failed", e)
        } finally {
            ex.close()
        }
    }

    // POST /crm/ticket — локальная правка тикета: статус, ответственный, комментарий,
    // эскалация. Этим на видео «разработчик чинит тикет», и бот сразу меняет тон.
    server.createContext("/crm/ticket") { ex ->
        try {
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, -1)
                return@createContext
            }
            if (!authed(ex, token)) return@createContext
            val body = ex.requestBody.bufferedReader().readText()
            val req = try {
                json.decodeFromString(CrmPatchRequest.serializer(), body)
            } catch (e: Exception) {
                val msg = "bad request: ${e.message}".toByteArray()
                ex.sendResponseHeaders(400, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val project = req.project.trim().ifBlank { defaultProject }
            require(req.ticketId.isNotBlank()) { "нужен ticketId" }
            req.status?.let { require(it in TICKET_STATUSES) { "статус должен быть одним из $TICKET_STATUSES" } }
            val store = crm.store(project)
            store.patch(req.ticketId, status = req.status, assignee = req.assignee, comment = req.comment)
            val escalated = req.escalate?.takeIf { it.isNotBlank() }
            val t = if (escalated != null) store.escalate(req.ticketId, escalated) else store.get(req.ticketId)
                ?: throw IllegalStateException("тикет #${req.ticketId} не найден в CRM проекта $project")
            log("CRM $project: тикет #${t.id} -> ${t.status}" + (if (t.escalated) " (эскалация)" else ""))
            sendJson(ex, 200, outJson.encodeToString(SupportTicket.serializer(), t))
        } catch (e: Exception) {
            fail(ex, "crm ticket failed", e)
        } finally {
            ex.close()
        }
    }

    // POST /support/ask — одно сообщение пользователя в диалоге по тикету.
    server.createContext("/support/ask") { ex ->
        try {
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, -1)
                return@createContext
            }
            if (!authed(ex, token)) return@createContext
            val llmKey = ex.requestHeaders.getFirst("X-LLM-Key")?.trim()?.takeIf { it.isNotBlank() }
            val body = ex.requestBody.bufferedReader().readText()
            val req = try {
                json.decodeFromString(SupportAskRequest.serializer(), body)
            } catch (e: Exception) {
                val msg = "bad request: ${e.message}".toByteArray()
                ex.sendResponseHeaders(400, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            if (req.message.isBlank()) {
                val msg = "empty message".toByteArray()
                ex.sendResponseHeaders(400, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val project = req.project.trim().ifBlank { defaultProject }
            val chatId = req.chatId.ifBlank { "0" }
            val history = supportChats.state.messages[chatId].orEmpty().takeLast(12)
            val t0 = System.currentTimeMillis()
            val res: SupportReply = runBlocking {
                SupportService.ask(
                    rag = svc, project = project, chatId = chatId, message = req.message.trim(),
                    apiKey = llmKey, history = history, crm = crm,
                    topK = req.topK.coerceIn(1, 20),
                    sessionTicketId = sessions.ticketOf(chatId),
                    onTicketFound = { id -> sessions.remember(chatId, id) },
                    onLog = { m -> log(m) },
                )
            }
            // История — по тикету: то, что бот уже отвечал, влияет на следующий ответ.
            supportChats.appendMessage(chatId, RagMessage("user", req.message.trim()))
            supportChats.appendMessage(
                chatId,
                RagMessage(
                    "assistant", res.text,
                    info = "тикет #${res.ticketId} ${res.ticketStatus} · FAQ-цитат: ${res.faqRefs.size}",
                    refs = res.faqRefs,
                ),
            )
            log(
                "поддержка $project/#$chatId за ${(System.currentTimeMillis() - t0) / 1000}с: " +
                    "тикет=${res.usedTicket} faq=${res.faqRefs.size} отказ=${res.refused} эскалация=${res.escalated}"
            )
            sendJson(ex, 200, outJson.encodeToString(SupportReply.serializer(), res))
        } catch (e: Exception) {
            fail(ex, "support ask failed", e)
        } finally {
            ex.close()
        }
    }

    // ===== Пользовательская часть: чат в браузере =====
    //
    // Конечный пользователь не имеет ни VPS_TOKEN, ни DEEPSEEK_API_KEY.
    // Поэтому /support/chat открыт наружу, а ключ модели берётся на сервере
    // (env DEEPSEEK_API_KEY или локальная Ollama). Чтобы это не превратилось
    // в открытый доступ к чужому ключу — простой троттлинг по IP.

    server.createContext("/chat") { ex ->
        try {
            val b = CHAT_HTML.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        } catch (e: Exception) {
            fail(ex, "chat ui failed", e)
        } finally {
            ex.close()
        }
    }

    server.createContext("/support/chat") { ex ->
        try {
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, -1)
                return@createContext
            }
            val ip = ex.remoteAddress?.address?.hostAddress ?: "?"
            if (!throttle.allow(ip)) {
                val msg = "Слишком много сообщений подряд. Подождите минуту."
                val b = msg.toByteArray()
                ex.sendResponseHeaders(429, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                return@createContext
            }
            val body = ex.requestBody.bufferedReader().readText()
            val req = try {
                json.decodeFromString(SupportChatRequest.serializer(), body)
            } catch (e: Exception) {
                val msg = "bad request: ${e.message}".toByteArray()
                ex.sendResponseHeaders(400, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            if (req.message.isBlank()) {
                val msg = "empty message".toByteArray()
                ex.sendResponseHeaders(400, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val project = req.project.trim().ifBlank { defaultProject }
            val chatId = req.chatId.trim().ifBlank { "guest" }
            val history = supportChats.state.messages[chatId].orEmpty().takeLast(10)
            val t0 = System.currentTimeMillis()
            val res: SupportReply = runBlocking {
                SupportService.ask(
                    rag = svc, project = project, chatId = chatId, message = req.message.trim(),
                    // Ключ не от клиента: сервер берёт свой (env DEEPSEEK_API_KEY)
                    // либо локальную модель, если ключа нет.
                    apiKey = ex.requestHeaders.getFirst("X-LLM-Key")?.trim()?.takeIf { it.isNotBlank() },
                    history = history, crm = crm,
                    sessionTicketId = sessions.ticketOf(chatId),
                    onTicketFound = { id -> sessions.remember(chatId, id) },
                    onLog = { m -> log(m) },
                )
            }
            supportChats.appendMessage(chatId, RagMessage("user", req.message.trim()))
            supportChats.appendMessage(chatId, RagMessage("assistant", res.text, refs = res.faqRefs))
            log("чат $project/$chatId за ${(System.currentTimeMillis() - t0) / 1000}с: тикет=${res.usedTicket} faq=${res.faqRefs.size}")
            sendJson(ex, 200, outJson.encodeToString(SupportReply.serializer(), res))
        } catch (e: Exception) {
            fail(ex, "support chat failed", e)
        } finally {
            ex.close()
        }
    }

    server.start()
    log(
        "review-server on :$port (эндпоинты: /health /review /index /mcp /crm/import /crm/tickets " +
            "/crm/ticket /support/ask; ПУБЛИЧНО: /chat (страница) /support/chat; " +
            "продукт поддержки: $defaultProject; VPS_TOKEN задан: ${token.isNotBlank()})"
    )
}

private fun svcAppDir(): java.io.File = core.llm.ApiKeyProvider.appDir()

// Троттлинг публичного чата: N сообщений в минуту с одного IP.
// Живой в памяти процесса — для демо этого достаточно.
private class Throttle(private val perMinute: Int = 12) {
    private val hits = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>>()

    fun allow(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val q = hits.computeIfAbsent(ip) { java.util.ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.peekFirst() > 60_000) q.pollFirst()
            if (q.size >= perMinute) return false
            q.addLast(now)
        }
        if (hits.size > 5000) hits.entries.removeIf { it.value.isEmpty() }
        return true
    }
}

// Авторизация общая для всех защищённых ручек: Bearer-токен, без разных секретов.
private fun authed(ex: HttpExchange, token: String): Boolean {
    if (ex.requestHeaders.getFirst("Authorization").orEmpty() != "Bearer $token") {
        ex.sendResponseHeaders(401, -1)
        ex.close()
        return false
    }
    return true
}

private fun sendJson(ex: HttpExchange, code: Int, payload: String) {
    val b = payload.toByteArray()
    ex.responseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(code, b.size.toLong())
    ex.responseBody.use { it.write(b) }
}

private fun fail(ex: HttpExchange, prefix: String, e: Exception) {
    log("$prefix: ${e.message}")
    val msg = "$prefix: ${e.message}".take(500).toByteArray()
    try {
        ex.sendResponseHeaders(500, msg.size.toLong())
        ex.responseBody.use { it.write(msg) }
    } catch (_: Exception) {
    }
}
