package core.rag

// Промпты ассистента разработчика (Week 6).
//
// Цель — честные и точные ответы: модель обязана опираться ТОЛЬКО на
// фрагменты документации проекта, явно отделять факты от своих выводов
// и честно говорить «не знаю», вместо того чтобы додумывать.
// Правила короткие и нумерованные сознательно: малые локальные модели
// (qwen 7b и т.п.) лучше соблюдают структуру, чем абзац prose + пример
// формата (few-shot) в конце системного промпта.

// Фактические RAG-ответы генерируем почти детерминированно: меньше
// выдумки. Живость чата тут не нужна — нужна точность.
const val ANSWER_TEMPERATURE = 0.1
const val PROMPT_CHUNK_CHARS = 1500
const val PROMPT_MAX_CHARS = 9000

// Обрезка фрагмента для промпта: по границе слова + маркер, чтобы модель
// видела, что текст неполный, и не считала обрывок целым предложением.
fun clipForPrompt(text: String, max: Int = PROMPT_CHUNK_CHARS): String {
    val t = text.replace(Regex("\\s+"), " ").trim()
    if (t.length <= max) return t
    val cut = t.take(max).trimEnd()
    val edge = cut.lastIndexOf(' ').takeIf { it > max * 2 / 3 } ?: max
    return cut.take(edge).trimEnd() + " …[фрагмент обрезан]"
}

fun buildContextBlock(hits: List<ScoredChunk>): String =
    hits.mapIndexed { i, h ->
        "[${i + 1}] ${h.chunk.title}" +
            (if (h.chunk.section.isNotBlank()) " / ${h.chunk.section}" else "") +
            ":\n${clipForPrompt(h.chunk.text)}"
    }.joinToString("\n\n")

// Системный промпт RAG-ответа: grounding-правила + пример формата.
// projectLine уже содержит имя/ветку/коммит (MCP-контекст) или пуст.
fun buildAnswerSystem(
    projectLine: String,
    slots: Int,
    memoryBlock: String,
): String = buildString {
    append("Ты — ассистент разработчика. Отвечаешь на вопросы о проекте. ")
    if (projectLine.isNotBlank()) append(projectLine)
    append("Ниже — фрагменты документации проекта с номерами [1..$slots]. ")
    append("Это ЕДИНСТВЕННЫЙ источник фактов о проекте.\n")
    append("Правила (обязательны):\n")
    append("1. Каждый факт о проекте — только из фрагментов, с номером: «… [2]».\n")
    append("2. Запрещено додумывать из общих знаний: не приписывай проекту файлы, API, зависимости и поведение, которых нет во фрагментах.\n")
    append("3. Если ответа во фрагментах нет — напиши «В документации проекта этого нет» и перечисли, каких сведений не хватило. Не гадай.\n")
    append("4. Разделяй факты и выводы: «По документации: … [N]» — факты; «Мой вывод: …» — твоя интерпретация (помечай явно).\n")
    append("5. Файлы называй коротким путём из заголовка фрагмента, а не внутренним source.\n")
    append("6. Не выдумывай цитаты: цитируй только текст, который есть во фрагментах.\n")
    append("7. Отвечай на языке вопроса (по умолчанию — русский), коротко и по делу.\n")
    append("8. В конце ответа — строка «Уверенность: высокая/средняя/низкая».\n")
    append("Пример формата:\n")
    append("По документации: ключ читается из env и файлов .env [1]. Хранится в ~/.ai-advent-week6/.env [1].\n")
    append("Мой вывод: для локального запуска достаточно переменной окружения.\n")
    append("Уверенность: высокая")
    if (memoryBlock.isNotBlank()) {
        append("\nПамять задачи (цель и факты диалога — учитывай в ответе, но фактам из фрагментов она не противоречит):\n")
        append(memoryBlock.take(1200))
    }
}

fun buildAnswerUser(
    historyBlock: String,
    context: String,
    query: String,
): String = buildString {
    if (historyBlock.isNotBlank()) append("Последние реплики:\n").append(historyBlock).append("\n\n")
    append("Фрагменты документации:\n").append(context).append("\n\nВопрос: ").append(query)
}.take(PROMPT_MAX_CHARS)

// Обычный чат без поиска: та же честность, но без фрагментов —
// запрещаем выдумывать и требуем маркировать неуверенность.
const val PLAIN_SYSTEM =
    "Ты — ассистент разработчика. Отвечай точно и коротко. " +
        "Если не уверен — так и скажи и укажи, в чём сомнение. " +
        "Не выдумывай факты, файлы, API и цитаты."

// ===== Task 2: системный промпт код-ревью PR =====
// Требуем СТРОГО JSON без fences: {summary, findings, comments[]}.
fun buildReviewSystem(projectLine: String, rulesBlock: String): String = buildString {
    append("Ты — строгий код-ревьювер. Проверяешь PR по diff и правилам проекта. ")
    if (projectLine.isNotBlank()) append(projectLine)
    append("\n")
    if (rulesBlock.isNotBlank()) {
        append("Правила проекта из документации (приоритет над общими практиками):\n")
        append(rulesBlock.take(4000)).append("\n")
    }
    append("Верни СТРОГО JSON без markdown-фенсов, без текста вокруг:\n")
    append("{\"summary\": \"краткий итог + баги/архитектура/рекомендации\", ")
    append("\"findings\": [\"строка-вывод\"], ")
    append("\"comments\": [{\"path\": \"путь/файл\", \"line\": 123, \"severity\": \"bug|arch|nit|comment\", \"body\": \"что не так + как чинить\"}]}\n")
    append("Правила:\n")
    append("1. path — точный путь из diff, line — номер в НОВОМ файле и ТОЛЬКО добавленная (+) строка.\n")
    append("2. Каждый коммент — конкретный: проблема, почему, как исправить. Вода запрещена.\n")
    append("3. severity: bug — потенциальный баг, arch — архитектурная проблема, nit — мелочь.\n")
    append("4. Не выдумывай API/файлы проекта — опирайся на diff и фрагменты доков.\n")
    append("5. Если проблем нет — верни пустой comments и summary «LGTM».\n")
}

fun buildReviewUser(fileDiff: String, addedHint: String): String = buildString {
    append("Diff файла для ревью:\n").append(fileDiff.take(9000))
    if (addedHint.isNotBlank()) append("\nДобавленные строки (комментить только их): ").append(addedHint.take(500))
}
