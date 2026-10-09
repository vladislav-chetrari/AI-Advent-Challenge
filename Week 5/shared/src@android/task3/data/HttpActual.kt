package task3.data

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.setRequestProperty("User-Agent", "ai-advent-task3")
    conn.connectTimeout = 30_000
    conn.readTimeout = 60_000
    conn.connect()
    if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
    conn.inputStream.use { it.readBytes().decodeToString() }
}

actual suspend fun httpPostJson(url: String, body: String, headers: Map<String, String>): HttpResult =
    withContext(Dispatchers.IO) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", "ai-advent-task3")
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.outputStream.use { it.write(body.encodeToByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = runCatching { stream?.use { it.readBytes().decodeToString() }.orEmpty() }.getOrDefault("")
        HttpResult(code, text)
    }

actual fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

actual fun deleteFile(path: String) {
    java.io.File(path).delete()
}
