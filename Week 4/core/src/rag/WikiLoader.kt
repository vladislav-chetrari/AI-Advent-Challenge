package core.rag

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Статья Википедии -> RawDoc. Вход: название ("Искусственный интеллект")
// или полный URL (язык подхватится из поддомена).
// Чистый текст (extracts/explaintext): без разметки, сносок и карточек.
// Заголовки секций сохраняем в wiki-формате (== ... ==, exsectionformat=wiki),
// иначе StructureChunker не видит структуру и вырождается в fixed-окна.
object WikiLoader {
    private val json = Json { ignoreUnknownKeys = true }
    private val client: HttpClient by lazy { HttpClient(CIO) }

    data class WikiRef(val lang: String, val title: String)

    fun parseInput(input: String, defaultLang: String = "ru"): WikiRef {
        val t = input.trim()
        // URL вида https://en.wikipedia.org/wiki/Title_With_Underscores
        val m = Regex("https?://([a-z-]+)\\.wikipedia\\.org/wiki/([^#?]+)").find(t)
        if (m != null) {
            val title = java.net.URLDecoder.decode(m.groupValues[2], "UTF-8").replace('_', ' ')
            return WikiRef(m.groupValues[1], title)
        }
        return WikiRef(defaultLang, t.removePrefix("https://").removePrefix("http://"))
    }

    suspend fun fetch(input: String, defaultLang: String = "ru"): RawDoc {
        val ref = parseInput(input, defaultLang)
        val encoded = URLEncoder.encode(ref.title, "UTF-8")
        val url = "https://${ref.lang}.wikipedia.org/w/api.php" +
            "?action=query&prop=extracts&explaintext=1&exsectionformat=wiki&redirects=1&format=json&titles=$encoded"
        val http = client.get(url)
        if (!http.status.isSuccess()) {
            throw IllegalStateException("Wikipedia HTTP ${http.status.value}")
        }
        val root = json.parseToJsonElement(http.bodyAsText()).jsonObject
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

    // Имя файла для сохранения статьи в локальный корпус.
    fun fileNameFor(doc: RawDoc): String {
        val safe = doc.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80)
        return "$safe.md"
    }
}
