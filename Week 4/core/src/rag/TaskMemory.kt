package core.rag

import core.llm.ChatMsg
import core.llm.LlmClient
import core.llm.LlmResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Task 5 (День 25): «память задачи» — факты на чат.
// Вместо пересылки всех сообщений в LLM храним сжатый итог диалога:
// цель + факты (уточнения, ограничения, термины, хотелки пользователя).
// Лежит в chats.json рядом с сообщениями (см. ChatStore), обновляется
// раз в ход дешёвым вызовом LLM с тихим fallback на эвристику.
@Serializable
data class TaskMemory(
    val goal: String = "",
    val facts: List<String> = emptyList(),
    val updatedAt: Long = 0,
) {
    fun isEmpty(): Boolean = goal.isBlank() && facts.isEmpty()

    // Компактный блок для system-промпта и rewrite-контекста.
    fun promptBlock(maxFacts: Int = 12, maxChars: Int = 1200): String {
        if (isEmpty()) return ""
        val sb = StringBuilder()
        if (goal.isNotBlank()) sb.append("Цель диалога: ").append(goal.take(GOAL_CHARS)).append('\n')
        val fs = facts.takeLast(maxFacts)
        if (fs.isNotEmpty()) {
            sb.append("Факты задачи:\n")
            for (f in fs) sb.append("- ").append(f).append('\n')
        }
        val s = sb.toString().trimEnd()
        return if (s.length <= maxChars) s else s.take(maxChars)
    }
}

const val TASK_MEMORY_MAX_FACTS = 20
const val TASK_MEMORY_FACT_CHARS = 240
const val GOAL_CHARS = 300

// Слияние: старые факты + новые от модели, без дублей (case-insensitive), хвост.
fun mergeTaskFacts(old: List<String>, newFacts: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    val out = ArrayList<String>()
    for (f in old + newFacts) {
        val t = f.trim().trim('.', ' ', '"', '«', '»')
        if (t.length < 3) continue
        val key = t.lowercase()
        if (seen.add(key)) out += t.take(TASK_MEMORY_FACT_CHARS)
    }
    return out.takeLast(TASK_MEMORY_MAX_FACTS)
}

// Обогащённый запрос для эмбеддинга: разрешает «а у них?», «а в городе?»
// за счёт цели и свежих фактов. Обрезка — чтобы не размывать вектор.
fun buildSearchQuery(query: String, memory: TaskMemory?): String {
    if (memory == null || memory.isEmpty()) return query
    val tail = buildString {
        if (memory.goal.isNotBlank()) append(memory.goal.take(200)).append(". ")
        append(memory.facts.takeLast(6).joinToString("; ").take(500))
    }.trim().trimEnd('.', ' ')
    if (tail.isEmpty()) return query
    return "$query. Контекст задачи: $tail".take(800)
}

// Последние реплики для анафор — только хвост, факты уже несут остальное.
fun recentHistoryBlock(history: List<RagMessage>, limit: Int = 4, maxChars: Int = 2000): String {
    if (history.isEmpty()) return ""
    val tail = history.takeLast(limit.coerceIn(1, 8))
    val s = tail.joinToString("\n") { m ->
        val who = if (m.role == "user") "пользователь" else "ассистент"
        "$who: ${m.content.replace(Regex("\\s+"), " ").trim().take(500)}"
    }
    return s.take(maxChars)
}

@Serializable
private data class MemoryWire(
    val goal: String = "",
    val facts: List<String> = emptyList(),
)

// Один ход обновления памяти. Никогда не бросает: любая ошибка LLM —
// возврат старого состояния (+ эвристический факт из явных маркеров).
suspend fun extractTaskMemory(
    query: String,
    answer: String,
    old: TaskMemory,
    llm: LlmClient,
    apiKey: String?,
): TaskMemory {
    val now = System.currentTimeMillis()
    val q = query.trim().take(1000)
    if (q.isEmpty()) return old
    val llmFacts = try {
        extractViaLlm(q, answer.take(1200), old, llm, apiKey)
    } catch (_: Exception) {
        null
    }
    if (llmFacts != null) return llmFacts.copy(updatedAt = now)
    // Офлайн-fallback: явные «запомни / только / всегда / меня зовут / готовлю…».
    val heur = heuristicFact(q)
    if (heur == null) return old
    return old.copy(facts = mergeTaskFacts(old.facts, listOf(heur)), updatedAt = now)
}

private suspend fun extractViaLlm(
    query: String,
    answer: String,
    old: TaskMemory,
    llm: LlmClient,
    apiKey: String?,
): TaskMemory? {
    val system = "Ты извлекаешь память задачи из диалога. " +
        "Верни СТРОГО JSON без пояснений: {\"goal\": \"краткая цель диалога или пусто\", " +
        "\"facts\": [\"факт 1\", ...]}. " +
        "Факты: уточнения пользователя, ограничения (только/кроме/кратко), термины, имена, " +
        "предпочтения. Максимум 15 фактов, каждый до 200 символов, на русском. " +
        "Сохрани прежние факты, если они не противоречат новым; устаревшие удали."
    val user = buildString {
        if (old.goal.isNotBlank() || old.facts.isNotEmpty()) {
            append("Текущая память:\n")
            if (old.goal.isNotBlank()) append("Цель: ").append(old.goal.take(300)).append('\n')
            old.facts.takeLast(15).forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("Новая реплика пользователя: ").append(query).append('\n')
        if (answer.isNotBlank()) append("Ответ ассистента (кратко): ").append(answer.take(600))
    }.take(2500)
    val r = llm.complete(listOf(ChatMsg("system", system), ChatMsg("user", user)), apiKey)
    if (r !is LlmResult.Ok) return null
    return parseMemoryJson(r.text, old)
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseMemoryJson(text: String, old: TaskMemory): TaskMemory? {
    val raw = text.trim()
        .substringAfter('{', missingDelimiterValue = "")
        .let { if (it.isEmpty()) return null else "{$it" }
        .substringBeforeLast('}').let { "$it}" }
    val wire = try {
        lenientJson.decodeFromString<MemoryWire>(raw)
    } catch (_: Exception) {
        // Не-JSON ответ модели: построчный fallback.
        val lines = raw.lines().map { it.trim().trimStart('-', '•', '*', ' ').trim() }
            .filter { it.length >= 4 }.take(15)
        if (lines.isEmpty()) return null
        return old.copy(facts = mergeTaskFacts(old.facts, lines))
    }
    val goal = wire.goal.trim().take(GOAL_CHARS).ifBlank { old.goal }
    // Пустой ответ модели = «ничего нового», а не «стереть всё».
    if (wire.goal.isBlank() && wire.facts.isEmpty()) return old
    return old.copy(goal = goal, facts = mergeTaskFacts(old.facts, wire.facts))
}

private val heuristicMarkers = listOf(
    "запомни", "только", "всегда", "никогда", "кроме",
    "меня зовут", "готовлю", "пишу ", "доклад", "кратко", "подробно",
)

private fun heuristicFact(query: String): String? {
    val q = query.replace(Regex("\\s+"), " ").trim()
    if (q.length < 8 || q.length > TASK_MEMORY_FACT_CHARS) return null
    val low = q.lowercase()
    if (heuristicMarkers.none { low.contains(it) }) return null
    return q.take(TASK_MEMORY_FACT_CHARS)
}
