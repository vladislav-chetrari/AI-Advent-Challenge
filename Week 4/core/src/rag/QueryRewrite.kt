package core.rag

import core.llm.ChatMsg
import core.llm.LlmClient
import core.llm.LlmResult

// Task 3 (День 23): query rewrite — короткий/разговорный вопрос далёк
// по эмбеддингу от энциклопедического текста («а при чём тут Косово?» vs
// «сербское кос = чёрный дрозд»). Просим LLM развернуть вопрос в 1-2
// поисковых запроса, ищем по оригиналу + вариантам, выдачи сливаем
// (см. mergeRetrievals). Любая ошибка LLM — тихий fallback: только оригинал.
suspend fun rewriteQueries(
    query: String,
    llm: LlmClient,
    apiKey: String?,
    maxVariants: Int = 2,
): List<String> {
    if (query.isBlank()) return emptyList()
    val system = "Ты помощник поиска по локальной базе знаний. " +
        "Переформулируй вопрос пользователя в поисковые запросы: " +
        "разверни местоимения и намёки, добавь вероятные термины и синонимы. " +
        "Верни от 1 до $maxVariants запросов, каждый с новой строки, без нумерации и пояснений."
    val result = try {
        llm.complete(
            listOf(ChatMsg("system", system), ChatMsg("user", query)),
            apiKey,
        )
    } catch (_: Exception) {
        return emptyList()
    }
    if (result !is LlmResult.Ok) return emptyList()
    val lower = query.trim().lowercase()
    return result.text.lines()
        .map { it.trim().trimStart('-', '•', '*', ' ').trim() }
        .map { it.replaceFirst(Regex("^\\d+[.)]\\s*"), "") }
        .map { it.trim().trim('"', '«', '»') }
        .filter { it.isNotBlank() && it.lowercase() != lower }
        .distinct()
        .take(maxVariants.coerceIn(1, 3))
}
