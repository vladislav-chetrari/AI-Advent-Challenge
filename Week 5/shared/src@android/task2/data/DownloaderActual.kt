package task2.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection

actual suspend fun downloadFile(
    url: String,
    destPath: String,
    onProgress: (doneBytes: Long, totalBytes: Long?) -> Unit,
) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(3) {
            // Чистый старт: оборванный .part от прошлой попытки не склеиваем.
            runCatching { File("$destPath.part").delete() }
            try {
                downloadOnce(url, destPath, onProgress)
                return@withContext
            } catch (e: IOException) {
                // Обрыв/таймаут мобильной сети — ретраим; HTTP-ошибки (код ответа)
                // кидаем как IllegalStateException и не ретраим.
                lastError = e
                kotlinx.coroutines.delay(2000)
            }
        }
        throw lastError ?: IllegalStateException("Не удалось скачать после 3 попыток")
    }
}

private fun downloadOnce(
    url: String,
    destPath: String,
    onProgress: (doneBytes: Long, totalBytes: Long?) -> Unit,
) {
    val dest = File(destPath)
    dest.parentFile?.mkdirs()
    val tmp = File("$destPath.part")
    val conn = java.net.URI(url).toURL().openConnection() as HttpURLConnection
    conn.setRequestProperty("User-Agent", "ai-advent-task2")
    conn.connectTimeout = 30_000
    // Read timeout — на stall, а не на всю закачку: тикают с последнего байта,
    // гигабайтную модель на медленной сети не убиваем.
    conn.readTimeout = 300_000
    conn.connect()
    if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
    // null = сервер не отдал длину (chunked/прокси): проценты врать не будем.
    val total: Long? = conn.contentLengthLong.takeIf { it > 0 }
    conn.inputStream.use { input ->
        FileOutputStream(tmp).use { out ->
            val buf = ByteArray(256 * 1024)
            var done = 0L
            var lastPercent = -1
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (total != null) {
                    val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                    if (p != lastPercent) {
                        lastPercent = p
                        onProgress(done, total)
                    }
                } else if (done % (1024 * 1024) == 0L) {
                    onProgress(done, null)
                }
            }
            if (total != null && done < total) {
                throw IOException("Скачивание оборвалось: $done из $total байт")
            }
            onProgress(done, total)
        }
    }
    if (!tmp.renameTo(dest)) {
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
    }
}
