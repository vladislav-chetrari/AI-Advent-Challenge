package task2.data

/** Кроссплатформенная докачка GGUF. Реализация — HttpURLConnection (jvm+android). */
expect suspend fun downloadFile(url: String, destPath: String, onProgress: (Int) -> Unit)
