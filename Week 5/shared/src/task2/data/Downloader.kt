package task2.data

/**
 * Кроссплатформенная докачка GGUF. Реализация — HttpURLConnection (jvm+android).
 * Прогресс честный: totalBytes == null, когда сервер не отдал Content-Length
 * (тогда проценты рисовать нельзя — только мегабайты и indeterminate-бар).
 * Внутри до 3 попыток при сетевых обрывах (HTTP-ошибки не ретраятся).
 */
expect suspend fun downloadFile(
    url: String,
    destPath: String,
    onProgress: (doneBytes: Long, totalBytes: Long?) -> Unit,
)
