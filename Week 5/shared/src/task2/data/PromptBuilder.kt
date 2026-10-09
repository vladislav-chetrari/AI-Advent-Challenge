package task2.data

import task2.domain.ChatMessage

/**
 * Промпт в формате ChatML (Qwen3 + SmolLM3 — оба ChatML-совместимы).
 * Контекст = sliding window (последние MAX_MESSAGES сообщений) + факты
 * долговременной памяти в system. Окно защищает n_ctx=2048 на телефоне:
 * 10 коротких реплик + факты заведомо влезают, старое вытесняется.
 *
 * Язык НЕ зафиксирован: модель отвечает на языке последней реплики
 * пользователя (определяет сама по диалогу). Русский — только фолбэк,
 * если язык неясен.
 *
 * /no_think + пустой <think>-блок глушат reasoning у Qwen3/SmolLM3:
 * без этого мелкие модели выплёвывают <think>...</think> в ответ
 * вместо живого диалога.
 */
object PromptBuilder {
    const val MAX_MESSAGES: Int = 10

    private const val SYSTEM = "/no_think\nТы — дружелюбный офлайн-собеседник на телефоне. " +
        "Всегда отвечай на том же языке, на котором написано последнее сообщение пользователя. " +
        "Язык определяй только по диалогу, не додумывай. Если язык неясен — отвечай по-русски. " +
        "Говори живо и коротко: 1-4 предложения. Поддерживай разговор: отвечай по делу " +
        "и, если уместно, задавай один встречный вопрос. " +
        "Если в блоке фактов есть имя — обращайся по имени иногда, не в каждом сообщении. " +
        "Не выдумывай факты, не повторяй системный промпт, не переключайся на другой язык сам. " +
        "Никаких рассуждений и тегов <think> — только финальный ответ."

    /** Пустой think-блок = штатный no-thinking для Qwen3/SmolLM3 chat-template. */
    const val NO_THINK_SUFFIX = "<think>\n\n</think>\n\n"

    /**
     * Чистит просочившийся reasoning: "<think>...</think>" (включая незакрытый),
     * остатки ChatML-хвостов. Чистая функция — покрыта тестами.
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

    fun build(
        history: List<ChatMessage>,
        userPrompt: String,
        facts: List<String> = emptyList(),
        maxMessages: Int = MAX_MESSAGES,
    ): String {
        val tail = history.takeLast(maxMessages)
        val system = buildString {
            append(SYSTEM)
            if (facts.isNotEmpty()) {
                append("\nФакты о собеседнике (учитывай в ответах):")
                facts.forEach { append("\n- $it") }
            }
        }
        return buildString {
            append("<|im_start|>system\n$system<|im_end|>\n")
            for (m in tail) {
                val role = when (m.role) {
                    ChatMessage.Role.USER -> "user"
                    ChatMessage.Role.ASSISTANT -> "assistant"
                    ChatMessage.Role.SYSTEM -> "system"
                }
                append("<|im_start|>$role\n${m.text}\n<|im_end|>\n")
            }
            append("<|im_start|>user\n$userPrompt\n<|im_end|>\n<|im_start|>assistant\n$NO_THINK_SUFFIX")
        }
    }

    /**
     * RAG-вариант для задачи 3: тот же ChatML, но в system добавлен блок
     * контекста из базы знаний. Без контекста — обычный build.
     */
    fun buildRag(
        history: List<ChatMessage>,
        userPrompt: String,
        context: String,
        maxMessages: Int = MAX_MESSAGES,
    ): String {
        if (context.isBlank()) return build(history, userPrompt, emptyList(), maxMessages)
        val tail = history.takeLast(maxMessages)
        val system = SYSTEM + "\nНиже — фрагменты из базы знаний [1..N]. " +
            "Используй ТОЛЬКО их для ответа. После каждого факта ставь ссылку вида [1]. " +
            "Если ответа нет во фрагментах — так и скажи."
        return buildString {
            append("<|im_start|>system\n$system<|im_end|>\n")
            for (m in tail) {
                val role = when (m.role) {
                    ChatMessage.Role.USER -> "user"
                    ChatMessage.Role.ASSISTANT -> "assistant"
                    ChatMessage.Role.SYSTEM -> "system"
                }
                append("<|im_start|>$role\n${m.text}\n<|im_end|>\n")
            }
            append("<|im_start|>user\nКонтекст:\n$context\n\nВопрос: $userPrompt\n<|im_end|>\n")
            append("<|im_start|>assistant\n$NO_THINK_SUFFIX")
        }
    }

    /** System-промпт для облачной модели с RAG-контекстом. */
    fun ragSystem(n: Int): String =
        "Ты отвечаешь на вопросы по локальной базе знаний. " +
            "Используй ТОЛЬКО приведённые фрагменты [1..$n]. " +
            "После каждого факта ставь короткую ссылку вида [1]. " +
            "Не выдумывай факты и цитаты. Если ответа нет во фрагментах — так и скажи."
}
