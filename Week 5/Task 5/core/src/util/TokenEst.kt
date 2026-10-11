package core.util

// Грубая оценка токенов без токенизатора: ~4 символа на токен
// (для русского — консервативно, обычно 2-3 символа). Нужна только для
// pre-flight проверки max context (точное usage отдаёт Ollama).
fun estTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

fun estMessagesTokens(messages: List<Pair<String, String>>): Int {
    var n = 0
    for ((_, content) in messages) n += estTokens(content) + 4 // + overhead на роль/разметку
    return n + 8
}
