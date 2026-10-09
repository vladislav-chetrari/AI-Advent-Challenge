package task2.data

/**
 * Детерминированное извлечение фактов (без LLM).
 *
 * Почему не LLM: проверка на устройстве показала, что мелкие модели
 * игнорирует мета-инструкции — на просьбу "выпиши факты или ответь НЕТ"
 * отвечает "I am a helpful assistant". Для такой маленькой модели
 * правила надёжнее: мгновенно, бесплатно, предсказуемо, тестируемо.
 * Шов для LLM-экстрактора оставлен: MemoryStore ничего не знает об источнике.
 *
 * Ловит только явные формулировки (RU+EN): имя, предпочтения, "запомни".
 */
object FactExtractor {
    private val NAME_TRIGGERS = listOf(
        Regex("меня зовут\\s+([^\\s,.!?:;]+)", RegexOption.IGNORE_CASE),
        Regex("мо[её] имя\\s+([^\\s,.!?:;]+)", RegexOption.IGNORE_CASE),
        Regex("my name is\\s+([^\\s,.!?:;]+)", RegexOption.IGNORE_CASE),
        // "I'm Vlad" — только с заглавной буквы и стоп-лист против "I'm fine".
        Regex("\\bi['’]?m\\s+([A-ZА-ЯЁ][^\\s,.!?:;]*)", ),
        Regex("\\bi am\\s+([A-ZА-ЯЁ][^\\s,.!?:;]*)", ),
    )
    private val NAME_STOP = setOf(
        "fine", "ok", "okay", "good", "well", "here", "there",
        "sorry", "tired", "busy", "ready", "happy",
    )
    /** Захват из одного местоимения ("it", "это") — мусор, а не факт. */
    private val PRONOUN_STOP = setOf(
        "it", "this", "that", "these", "those", "them", "him", "her",
        "me", "you", "us", "myself", "yourself",
        "это", "этого", "этому", "этим", "тот", "та", "то", "тебя", "меня",
    )
    private val LIKE_TRIGGERS = listOf(
        Regex("я люблю\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("мне нравится\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("я обожаю\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("i love\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("i like\\s+(.+)", RegexOption.IGNORE_CASE),
    )
    private val REMEMBER_TRIGGERS = listOf(
        Regex("запомни[:\\-,]?\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("remember[:\\-,]?\\s+(.+)", RegexOption.IGNORE_CASE),
    )

    /** Чистая функция — покрыта unit-тестами. */
    fun extractFrom(text: String, maxCount: Int = 3, maxLen: Int = 120): List<String> {
        val clean = text.trim().take(300)
        if (clean.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        for (re in NAME_TRIGGERS) {
            val name = re.find(clean)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', ',', '!', '?')
                ?: continue
            if (name.length < 2 || name.lowercase() in NAME_STOP) continue
            out += "Имя пользователя — $name"
        }
        for (re in LIKE_TRIGGERS) {
            val what = re.find(clean)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', ',', '!', '?')
                ?: continue
            if (what.length < 2 || what.lowercase() in PRONOUN_STOP) continue
            out += "Нравится: ${what.take(maxLen)}"
        }
        for (re in REMEMBER_TRIGGERS) {
            val what = re.find(clean)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', ',', '!', '?')
                ?: continue
            if (what.length < 2 || what.lowercase() in PRONOUN_STOP) continue
            out += what.take(maxLen)
        }
        return out.distinct().take(maxCount)
    }
}
