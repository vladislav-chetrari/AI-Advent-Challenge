package task3.data

/** Минимальный HTTP-слой: в common только контракт, транспорт — expect/actual на HttpURLConnection. */
data class HttpResult(val code: Int, val body: String)

expect suspend fun httpGet(url: String): String

expect suspend fun httpPostJson(url: String, body: String, headers: Map<String, String>): HttpResult

expect fun urlEncode(s: String): String

expect fun deleteFile(path: String)
