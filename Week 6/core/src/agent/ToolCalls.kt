package core.agent

import core.llm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ===== Task 4: разбор вызовов тулов из текста =====
//
// Не все модели и не все прокси умеют native function-calling. Локальная
// qwen2.5-coder на Ollama отдаёт вызов как обычный текст:
//
//   {"name": "find_references", "arguments": {"symbol": "NetworkConfig"}}
//
// Ровно как в Task 2 мы это не чиним на уровне промпта, а разбираем сами —
// тот же приём, что parseReviewJson для ответа ревью. Разбор безвредный:
// если текст не похож на вызов тула, возвращаем null и обычный ответ модели
// уходит пользователю как есть.

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

// Похоже ли содержимое на вызов тула. Проверка до разбора: иначе обычный
// ответ модели вида «{...}» (например, список изменений) приняли бы за тул.
private fun looksLikeCall(o: JsonObject): Boolean {
    val fn = o["function"] as? JsonObject
    val name = ((fn?.get("name") ?: o["name"]) as? JsonPrimitive)?.contentOrNull
    return !name.isNullOrBlank() && (o.containsKey("arguments") || o.containsKey("parameters") || fn != null)
}

// Разбирает один объект вызова. name + arguments — плоская форма (как у MCP),
// function.name + function.arguments — форма OpenAI.
private fun toCall(o: JsonObject, ord: Int): ToolCall? {
    val fn = o["function"] as? JsonObject
    val name = ((fn?.get("name") ?: o["name"]) as? JsonPrimitive)?.contentOrNull?.trim()
    if (name.isNullOrBlank() || !name.all { it.isLetterOrDigit() || it == '_' }) return null
    // arguments бывают объектом, строкой-JSON или скрыты под parameters.
    val rawArg = fn?.get("arguments") ?: o["arguments"] ?: o["parameters"] ?: o["input"]
    val args: JsonObject? = when (rawArg) {
        is JsonObject -> rawArg
        is JsonPrimitive -> core.llm.parseToolArgs(rawArg.contentOrNull)
        else -> JsonObject(emptyMap())
    }
    val parsed = args ?: JsonObject(emptyMap())
    return ToolCall(
        id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "call_${ord}_$name",
        name = name,
        arguments = parsed,
        rawArgs = parsed.toString(),
        rejected = args == null && rawArg != null,
    )
}

// Текст -> вызовы тулов, либо null если это не вызовы.
fun parseToolCallsFromText(text: String): List<ToolCall>? {
    val blocks = jsonBlocks(text)
    if (blocks.isEmpty()) return null
    val objs = blocks.mapNotNull { b ->
        try {
            lenient.parseToJsonElement(b)
        } catch (_: Exception) {
            null
        }
    }
    // Модель может вернуть и один объект, и массив вызовов, и несколько
    // объектов подряд (частый вывод локальных моделей) — разбираем всё.
    val list = objs.flatMap {
        when (it) {
            is JsonArray -> it.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(it)
            else -> emptyList()
        }
    }
    if (list.isEmpty() || list.none { looksLikeCall(it) }) return null
    val calls = list.mapIndexedNotNull { i, o -> if (looksLikeCall(o)) toCall(o, i) else null }
    return calls.ifEmpty { null }
}

// Вырезает из текста все сбалансированные {...} блоки, игнорируя фигурные
// скобки внутри строк. Модель может писать «вот мой вызов: {…}» или два
// объекта в разных строках — такой текст надо читать целиком, а не по
// первому совпадению скобок.
private fun jsonBlocks(text: String): List<String> {
    val t = text.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val out = mutableListOf<String>()
    var depth = 0
    var start = -1
    var inStr = false
    var esc = false
    t.forEachIndexed { i, c ->
        when {
            esc -> esc = false
            inStr && c == '\\' -> esc = true
            c == '"' -> inStr = !inStr
            inStr -> Unit
            c == '{' -> {
                if (depth == 0) start = i
                depth++
            }
            c == '}' -> {
                if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += t.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
    }
    return out
}

// Текст результата тулов для переписки, когда вызовы пришли текстом.
// Отдельный формат от role="tool": там провайдер ждёт эха assistant с
// tool_calls, а его не было — иначе DeepSeek отвергнет следующий запрос.
fun fallbackToolMessage(results: List<Pair<String, String>>): String = buildString {
    append("Результаты вызванных тулов (name: результат):\n\n")
    results.forEach { (name, text) ->
        append("--- ").append(name).append(" ---\n")
        append(text).append("\n\n")
    }
    append("Продолжай работу или вызови finish, если цель достигнута.")
}

// Признак того, что ответ модели — попытка вызвать тул, а не финал.
// Используется в цикле, чтобы понять, надо ли разбирать текст.
fun mentionsToolCall(text: String, names: Set<String>): Boolean {
    if (text.contains("\"name\"")) return true
    return names.any { text.contains("\"${it}\"") || text.contains("$it(") }
}
