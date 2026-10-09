package task2.data

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection

actual suspend fun downloadFile(url: String, destPath: String, onProgress: (Int) -> Unit) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        val tmp = File("$destPath.part")
        val conn = java.net.URI(url).toURL().openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "ai-advent-task2")
        conn.connect()
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong.coerceAtLeast(1L)
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                    if (p != last) {
                        last = p
                        onProgress(p)
                    }
                }
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        onProgress(100)
    }
}
