package core.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ===== Task 2: модели код-ревью с inline-комментами =====

// Свежесть RAG: есть ли доки и не отстал ли индекс от HEAD.
@Serializable
data class ReviewFreshness(
    val docsFound: Int = 0,
    val chunks: Int = 0,
    val indexedCommit: String = "",
    val headCommit: String = "",
    val upToDate: Boolean = true,
    val staleFiles: List<String> = emptyList(),
    val warning: String = "",
)

// Один inline-коммент: публикуется через POST /pulls/{pr}/reviews.
// line — номер строки в НОВОМ файле, только "+"-строка diff, иначе GitHub вернёт 422.
@Serializable
data class ReviewComment(
    val path: String,
    val line: Int,
    val severity: String = "comment", // bug | arch | nit | comment
    val body: String = "",
)

// Итог ревью: summary идёт в body ревью, comments — в inline.
@Serializable
data class ReviewResult(
    val summary: String = "",
    val bugs: List<String> = emptyList(),
    val arch: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val comments: List<ReviewComment> = emptyList(),
    val freshness: ReviewFreshness = ReviewFreshness(),
    val filesReviewed: Int = 0,
    val truncated: Boolean = false,
)

private val reviewJson = Json { ignoreUnknownKeys = true; isLenient = true }

// Парсинг ответа LLM: режем ```json-фенсы, ищем первый {...} блок.
fun parseReviewJson(raw: String): Triple<String, List<String>, List<ReviewComment>> {
    var t = raw.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = t.indexOf('{')
    val end = t.lastIndexOf('}')
    if (start < 0 || end <= start) return Triple(t.take(2000), emptyList(), emptyList())
    t = t.substring(start, end + 1)
    return try {
        val v = reviewJson.decodeFromString<RawReview>(t)
        Triple(v.summary, v.findings, v.comments)
    } catch (_: Exception) {
        // Не-JSON ответ: отдаём как summary без inline — Action запостит общий коммент.
        Triple(raw.trim().take(4000), emptyList(), emptyList())
    }
}

@Serializable
private data class RawReview(
    val summary: String = "",
    val findings: List<String> = emptyList(),
    val comments: List<ReviewComment> = emptyList(),
)

// Валидация перед постингом: path из diff, line только добавленная.
// severity нормализуем к bug|arch|nit|comment.
fun validateComments(
    comments: List<ReviewComment>,
    addedLines: Map<String, Set<Int>>,
): List<ReviewComment> = comments.mapNotNull { c ->
    val path = c.path.trim().trimStart('/')
    if (path.isEmpty() || ".." in path) return@mapNotNull null
    val allowed = addedLines[path] ?: return@mapNotNull null
    if (c.line !in allowed) return@mapNotNull null
    val sev = c.severity.lowercase().take(10).let {
        when (it) {
            "bug", "bugs", "error" -> "bug"
            "arch", "architecture" -> "arch"
            "nit", "style", "minor" -> "nit"
            else -> "comment"
        }
    }
    val body = c.body.trim().take(2000)
    if (body.isEmpty()) return@mapNotNull null
    ReviewComment(path, c.line, sev, body)
}
