package server

import com.sun.net.httpserver.HttpServer
import core.rag.RagService
import core.rag.ReviewResult
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// VPS-сервер ревью для Task 2: держит rag.db/projects.json + Ollama рядом,
// ключ DeepSeek НЕ хранит — прилетает per-request в X-LLM-Key из GH Secrets.
//   PORT=8080 VPS_TOKEN=xxx ./kotlin run --module server
//   POST /health -> ok
//   POST /review {diff, project?, maxFiles?, refresh?} -> ReviewResult JSON
//   Headers: Authorization: Bearer <VPS_TOKEN>, X-LLM-Key: <DEEPSEEK_API_KEY>
//   project — имя проекта в RAG-индексе VPS (см. integration/README.md).
@Serializable
private data class ReviewRequest(
    val diff: String = "",
    val project: String? = null,
    val maxFiles: Int = 15,
    val refresh: Boolean = true,
)

private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; isLenient = true }

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
                svc.reviewDiff(req.project, req.diff, apiKey = llmKey, maxFiles = req.maxFiles.coerceIn(1, 50), refresh = req.refresh)
            }
            val out = Json { prettyPrint = true; encodeDefaults = true }.encodeToString(ReviewResult.serializer(), res).toByteArray()
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
    server.start()
    println("review-server on :$port (VPS_TOKEN set: ${token.isNotBlank()})")
}
