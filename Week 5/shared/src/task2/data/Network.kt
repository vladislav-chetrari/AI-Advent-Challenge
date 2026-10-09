package task2.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

/**
 * Весь сетевой слой — Ktor + okio в common-коде, без expect/actual
 * (раньше был HttpURLConnection в двух копиях под android/jvm).
 * Один HttpClient на всё время жизни приложения (переиспользование
 * соединений), файлы — через okio FileSystem.
 */
private val ktorClient = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 120_000
    }
}

private const val USER_AGENT = "ai-advent"

/** GET строкой. Не-2xx — исключение (как раньше). */
suspend fun httpGet(url: String): String {
    val response = ktorClient.prepareGet(url) {
        header(HttpHeaders.UserAgent, USER_AGENT)
    }.execute()
    if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value}")
    return response.bodyAsText()
}

/** Процент-кодирование для query-параметров (напр. заголовки статей Википедии). */
fun urlEncode(s: String): String = s.encodeURLQueryComponent()

/**
 * Скачивание файла с прогрессом и ретраями.
 * Семантика как у старого HttpURLConnection-варианта:
 * - до 3 попыток при сетевых обрывах (IOException), HTTP-ошибки не ретраятся;
 * - чистый старт: оборванный .part от прошлой попытки не склеиваем;
 * - колбэк прогресса: при известной длине — по смене процента,
 *   при неизвестной (chunked/прокси) — каждый мегабайт, в конце — всегда;
 * - оборванный хвост (done < total) — IOException, то есть ретрай;
 * - атомарное появление файла: пишем в .part, в конце переименовываем.
 */
suspend fun downloadFile(
    url: String,
    destPath: String,
    onProgress: (doneBytes: Long, totalBytes: Long?) -> Unit,
) {
    val fs = FileSystem.SYSTEM
    val dest = destPath.toPath()
    val tmp = "$destPath.part".toPath()
    dest.parent?.let { fs.createDirectories(it) }
    var lastError: Exception? = null
    repeat(3) {
        // Чистый старт: оборванный .part от прошлой попытки не склеиваем.
        runCatching { fs.delete(tmp) }
        try {
            downloadOnce(url, tmp, dest, onProgress)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // HTTP-ошибки (код ответа) — не ретраим.
            throw e
        } catch (e: Exception) {
            // Обрыв/таймаут мобильной сети — ретраим.
            lastError = e
            delay(2000)
        }
    }
    throw lastError ?: IllegalStateException("Не удалось скачать после 3 попыток")
}

private suspend fun downloadOnce(
    url: String,
    tmp: okio.Path,
    dest: okio.Path,
    onProgress: (doneBytes: Long, totalBytes: Long?) -> Unit,
) {
    val fs = FileSystem.SYSTEM
    ktorClient.prepareGet(url) {
        header(HttpHeaders.UserAgent, USER_AGENT)
        timeout {
            // Гигабайтную модель на медленной сети не убиваем: общий таймаут
            // фактически выкл (7 суток), тикает только stall-тишина.
            // В этом Ktor нет INFINITE_TIMEOUT_MS — только конечные значения.
            requestTimeoutMillis = 7L * 24 * 60 * 60 * 1000
            socketTimeoutMillis = 300_000
        }
    }.execute { response ->
        if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value}")
        // null = сервер не отдал длину (chunked/прокси): проценты врать не будем.
        val total: Long? = response.contentLength()?.takeIf { it > 0 }
        val channel = response.bodyAsChannel()
        fs.sink(tmp).buffer().use { sink ->
            val buf = ByteArray(256 * 1024)
            var done = 0L
            var lastPercent = -1
            while (true) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                sink.write(buf, 0, n)
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
                // Хвост оборван — не HTTP-ошибка, поэтому обычный Exception: уйдёт в ретрай выше.
                throw Exception("Скачивание оборвалось: $done из $total байт")
            }
            onProgress(done, total)
        }
    }
    try {
        fs.atomicMove(tmp, dest)
    } catch (_: Exception) {
        fs.copy(tmp, dest)
        fs.delete(tmp)
    }
}
