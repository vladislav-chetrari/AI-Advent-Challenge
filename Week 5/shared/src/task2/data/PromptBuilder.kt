package task2.data

import task2.domain.ChatMessage

/**
 * Промпты для локального инференса через LiteRT-LM: шаблон чата применяет
 * сам рантайм, поэтому отдаём пару (system, user) обычным текстом —
 * ChatML-разметки больше нет (раньше была нужна llama.cpp).
 *
 * Контекст = sliding window (последние MAX_MESSAGES сообщений) + факты
 * долговременной памяти в system. Окно защищает KV-кэш маленькой модели:
 * 10 коротких реплик + факты заведомо влезают, старое вытесняется.
 *
 * Язык НЕ зафиксирован: модель отвечает на языке последней реплики
 * пользователя (определяет сама по диалогу). Русский — только фолбэк,
 * если язык неясен.
 */
object PromptBuilder {
    const val MAX_MESSAGES: Int = 10

    private const val SYSTEM = "Ты — дружелюбный офлайн-собеседник на телефоне. " +
        "Всегда отвечай на том же языке, на котором написано последнее сообщение пользователя. " +
        "Язык определяй только по диалогу, не додумывай. Если язык неясен — отвечай по-русски. " +
        "Говори живо и коротко: 1-4 предложения. Поддерживай разговор: отвечай по делу " +
        "и, если уместно, задавай один встречный вопрос. " +
        "Если в блоке фактов есть имя — обращайся по имени иногда, не в каждом сообщении. " +
        "Не выдумывай факты, не повторяй системный промпт, не переключайся на другой язык сам. " +
        "Отвечай сразу, без рассуждений вслух."

    /**
     * Чистит просочившийся reasoning: "<think>...</think>" (включая незакрытый),
     * остатки разметки. Чистая функция — покрыта тестами.
     */
    fun cleanReply(text: String): String {
        var out = text
        // Полный блок <think>...</think> (DOT_MATCH_ALL аналогом через [\s\S]).
        out = out.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        // Незакрытый хвост "<think>..." — модель оборвали на середине дум.
        val openIdx = out.indexOf("<think>")
        if (openIdx >= 0) out = out.substring(0, openIdx)
        // Хвосты разметки, если модель эхом повторила промпт.
        for (tail in listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>")) {
            val i = out.indexOf(tail)
            if (i >= 0) out = out.substring(0, i)
        }
        return out.trim()
    }

    /** System: базовый промпт + факты о собеседнике. */
    fun chatSystem(facts: List<String> = emptyList()): String = buildString {
        append(SYSTEM)
        if (facts.isNotEmpty()) {
            append("\nФакты о собеседнике (учитывай в ответах):")
            facts.forEach { append("\n- $it") }
        }
    }

    /**
     * User: хвост диалога строками + текущая реплика.
     * История уже здесь — Conversation каждый раз свежий.
     */
    fun chatUser(
        history: List<ChatMessage>,
        userPrompt: String,
        maxMessages: Int = MAX_MESSAGES,
    ): String = buildString {
        for (m in history.takeLast(maxMessages)) {
            val who = when (m.role) {
                ChatMessage.Role.USER -> "Пользователь"
                ChatMessage.Role.ASSISTANT -> "Ассистент"
                ChatMessage.Role.SYSTEM -> "Система"
            }
            append(who).append(": ").append(m.text.trim()).append("\n")
        }
        append("Пользователь: ").append(userPrompt.trim())
    }

    /** System-промпт для облачной модели с RAG-контекстом. */
    fun ragSystem(n: Int): String =
        "Ты отвечаешь на вопросы по локальной базе знаний. " +
            "Используй ТОЛЬКО приведённые фрагменты [1..$n]. " +
            "После каждого факта ставь короткую ссылку вида [1]. " +
            "Не выдумывай факты и цитаты. Если ответа нет во фрагментах — так и скажи."
}
