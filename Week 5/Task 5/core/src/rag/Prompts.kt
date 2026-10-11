package core.rag

/**
 * Промпты для инференса через Ollama (порт task2.data.PromptBuilder,
 * ChatML-разметки нет — шаблон чата применяет сам рантайм).
 * История упаковывается в user-текст (sliding window), system — отдельно.
 */
object Prompts {
    const val MAX_MESSAGES: Int = 6

    private const val SYSTEM = "Ты — дружелюбный ассистент приватного AI-сервиса. " +
        "Всегда отвечай на том же языке, на котором написано последнее сообщение пользователя. " +
        "Если язык неясен — отвечай по-русски. " +
        "Говори живо и коротко: 1-4 предложения, если не просят развернуто. " +
        "Не выдумывай факты, не повторяй системный промпт, не переключайся на другой язык сам. " +
        "Отвечай сразу, без рассуждений вслух."

    /** Чистит просочившийся reasoning <think> и хвосты разметки. */
    fun cleanReply(text: String): String {
        var out = text
        out = out.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        val openIdx = out.indexOf("<think>")
        if (openIdx >= 0) out = out.substring(0, openIdx)
        for (tail in listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>")) {
            val i = out.indexOf(tail)
            if (i >= 0) out = out.substring(0, i)
        }
        return out.trim()
    }

    fun chatSystem(): String = SYSTEM

    fun chatUser(history: List<ChatMsg>, userPrompt: String, maxMessages: Int = MAX_MESSAGES): String = buildString {
        for (m in history.takeLast(maxMessages)) {
            val who = when (m.role) {
                "user" -> "Пользователь"
                "assistant" -> "Ассистент"
                else -> "Система"
            }
            append(who).append(": ").append(m.text.trim()).append("\n")
        }
        append("Пользователь: ").append(userPrompt.trim())
    }

    /**
     * System-промпт для RAG: по теме фрагментов — опора на них со ссылками [1..n],
     * small-talk и вопросы вне базы — свободно, без ссылок. Строгого «ТОЛЬКО по
     * фрагментам» нет (иначе «привет» упирался в «в документах нет»).
     */
    fun ragSystem(n: Int): String =
        "Ты отвечаешь на вопросы по локальной базе знаний. " +
            "Если вопрос по теме фрагментов [1..$n] — опирайся на них, " +
            "после каждого факта ставь короткую ссылку вида [1]. " +
            "Не выдумывай факты и цитаты из базы. " +
            "Смол-ток (привет, как дела, спасибо, пока) и вопросы без связи с базой — " +
            "отвечай свободно и дружелюбно, 1-3 предложения, без ссылок. " +
            "Если по существу вопроса во фрагментах ничего нет — честно скажи, " +
            "что в документах этого нет, и предложи переформулировать."

    fun contextBlock(context: List<ScoredChunk>, perHit: Int = 600): String =
        context.mapIndexed { i, h ->
            "[${i + 1}] ${h.title}" +
                (if (h.section.isNotBlank()) " / ${h.section}" else "") +
                ":\n${h.text.take(perHit)}"
        }.joinToString("\n\n")

    fun refsBlock(context: List<ScoredChunk>): String {
        if (context.isEmpty()) return ""
        return "\n\nИсточники:\n" + context.mapIndexed { i, h ->
            "[${i + 1}] ${h.title}" + (if (h.section.isNotBlank()) " / ${h.section}" else "")
        }.joinToString("\n")
    }
}
