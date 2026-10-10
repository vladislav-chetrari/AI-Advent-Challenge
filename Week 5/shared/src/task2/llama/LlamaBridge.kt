package task2.llama

import kotlinx.coroutines.flow.Flow

/**
 * Тонкий порт к локальному инференсу. Реализация — expect/actual:
 * android — LiteRT-LM, jvm — LiteRT-LM (десктоп тоже считает по-настоящему).
 * SOLID (DIP): домен зависит только от этого интерфейса.
 *
 * Промпт идёт парой (system, user): шаблон чата применяет сам рантайм
 * (LiteRT-LM), ChatML-строки из прошлого (llama.cpp) больше не нужны.
 */
interface LlamaBridge {
    val isReady: Boolean
    suspend fun load(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4)
    fun generateChat(system: String, user: String): Flow<String>
    fun cancel()
    fun close()
}

expect fun createLlamaBridge(): LlamaBridge

/**
 * Минимальный ChatML-темплейт вместо встроенного в бандл.
 * Штатный Qwen-шаблон падает на системном сообщении: делает
 * '<|im_start|>system\n' + messages[0].content, а LiteRT отдаёт контент
 * списком блоков (sequence), не строкой — рендер роняет ошибку
 * ("tried to use + operator on unsupported types string and sequence").
 * Здесь контент забираем явно: строка — как есть, список — склейкой
 * текстовых блоков. Роли LiteRT ("system"/"user"/"assistant") уже ChatML.
 *
 * Два ограничения парсера override-темплейтов (проверены зондом
 * на настоящем .litertlm, см. историю):
 * - НИКАКИХ минусов whitespace-control ({%-, -}}, {{-, -}}) — парсер
 *   их не понимает ("unexpected `-`, expected end of block");
 * - переводы строк внутри строковых литералов — только escapes \\n.
 */
internal const val CHATML_TEMPLATE: String =
    "{% for message in messages %}" +
        "{{ '<|im_start|>' + message.role + '\\n' }}" +
        "{% if message.content is string %}" +
        "{{ message.content }}" +
        "{% else %}" +
        "{% for block in message.content %}{{ block.text }}{% endfor %}" +
        "{% endif %}" +
        "{{ '<|im_end|>\\n' }}" +
        "{% endfor %}" +
        "{% if add_generation_prompt %}" +
        "{{ '<|im_start|>assistant\\n' }}" +
        "{% endif %}"

