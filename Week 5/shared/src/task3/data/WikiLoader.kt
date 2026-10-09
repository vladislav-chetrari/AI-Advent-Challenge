package task3.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json

/**
 * Загрузка статей Википедии (порт core.rag.WikiLoader из Недели 4).
 * Чистый текст через extracts/explaintext, секции в wiki-формате.
 */
object WikiLoader {
    private val json = Json { ignoreUnknownKeys = true }

    data class WikiRef(val lang: String, val title: String)

    fun parseInput(input: String, defaultLang: String = "ru"): WikiRef {
        val t = input.trim()
        val m = Regex("https?://([a-z-]+)\\.wikipedia\\.org/wiki/([^#?]+)").find(t)
        if (m != null) {
            val title = urlDecode(m.groupValues[2]).replace('_', ' ')
            return WikiRef(m.groupValues[1], title)
        }
        return WikiRef(defaultLang, t.removePrefix("https://").removePrefix("http://"))
    }

    suspend fun fetch(input: String, defaultLang: String = "ru"): RawDoc {
        val ref = parseInput(input, defaultLang)
        val encoded = urlEncode(ref.title)
        val url = "https://${ref.lang}.wikipedia.org/w/api.php" +
            "?action=query&prop=extracts&explaintext=1&exsectionformat=wiki&redirects=1&format=json&titles=$encoded"
        val body = try {
            httpGet(url)
        } catch (e: Exception) {
            throw IllegalStateException("Нет связи с Wikipedia: ${e.message}")
        }
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("Wikipedia вернула не-JSON")
        }
        val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject
            ?: throw IllegalStateException("Пустой ответ Wikipedia API")
        val page = pages.values.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("Статья не найдена: ${ref.title}")
        if ("missing" in page) throw IllegalStateException("Статья не найдена: ${ref.title}")
        val title = page["title"]?.jsonPrimitive?.contentOrNull ?: ref.title
        val extract = page["extract"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (extract.length < 100) throw IllegalStateException("В статье нет текста (возможно, редирект/список)")
        return RawDoc(
            source = "wikipedia/${ref.lang}/$title",
            title = "$title (wikipedia)",
            text = extract,
        )
    }
}

/** Процент-декодирование %XX без java.net (common-код). */
private fun urlDecode(s: String): String {
    val sb = StringBuilder()
    val bytes = mutableListOf<Byte>()
    fun flush() {
        if (bytes.isNotEmpty()) {
            sb.append(bytes.toByteArray().decodeToString())
            bytes.clear()
        }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val hex = s.substring(i + 1, i + 3)
            val b = hex.toIntOrNull(16)
            if (b != null) {
                bytes += b.toByte()
                i += 3
                continue
            }
        }
        flush()
        sb.append(if (c == '+') ' ' else c)
        i++
    }
    flush()
    return sb.toString()
}
