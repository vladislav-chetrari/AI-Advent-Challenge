package core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ===== Task 4: описание тулов для function-calling =====
//
// Формат — OpenAI-совместимый (`tools`), его понимают и DeepSeek, и локальная
// Ollama на /v1/chat/completions. Отдельной библиотеки не нужно: схема тула
// это JsonObject, собирается теми же хелперами, что и MCP-схемы в Mcp.kt.
//
// Слой нужен агенту, который сам инициирует работу с файлами: модель получает
// список тулов, возвращает ToolCall, а цикл (core/agent/AgentRunner.kt)
// исполняет его и отдаёт результат обратно.

// Тип аргумента тула + человеческое описание — как в MCP tools/list.
typealias ArgSpec = Pair<String, String>

// Один тул: имя (snake_case, как его зовёт модель), описание и схема аргументов.
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
) {
    init {
        require(name.isNotBlank()) { "имя тула пустое" }
        require(name.all { it.isLetterOrDigit() || it == '_' }) {
            "имя тула «$name»: только латиница, цифры и _ (так требует function-calling)"
        }
    }

    fun toWire(): WireTool = WireTool(
        type = "function",
        function = WireToolFunction(name = name, description = description, parameters = parameters),
    )

    // Имена обязательных аргументов — из схемы, чтобы валидатор и промпт
    // не расходились (opencode требует того же: одно место правды).
    val required: List<String>
        get() = (parameters["required"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
}

// Вызов, который модель запросила. arguments разобран в JsonObject заранее:
// сырые строки от мелких локальных моделей регулярно приходят битыми, и падать
// на этом нельзя — тогда rejected=true и агент отдаст модели текст ошибки
// вместо того, чтобы крутить цикл на одном и том же невалидном вызове.
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val rawArgs: String,
    val rejected: Boolean = false,
)

private val toolJson = Json { ignoreUnknownKeys = true; isLenient = true }

// JSON-схема аргументов тула: properties + required — как требует MCP tools/list
// и как ждёт OpenAI function-calling.
fun toolSchema(props: List<Pair<String, ArgSpec>>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            props.forEach { (name, spec) ->
                putJsonObject(name) {
                    put("type", spec.first)
                    put("description", spec.second)
                }
            }
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
    }

// Разбор аргументов вызова. Терпит мусор: без фигурных скобок — пробуем
// дописать обёртку, битый JSON — null (агент отдаст модели текст ошибки
// вместо падения цикла).
fun parseToolArgs(raw: String?): JsonObject? {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty()) return JsonObject(emptyMap())
    return try {
        toolJson.parseToJsonElement(t).jsonObjectOrNull()
    } catch (_: Exception) {
        // Модель часто оборачивает JSON в пояснение: {"path":"a.kt",} # комментарий
        try {
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            if (start >= 0 && end > start) {
                toolJson.parseToJsonElement(t.substring(start, end + 1)).jsonObjectOrNull()
            } else null
        } catch (_: Exception) {
            null
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
    this as? JsonObject

// --- чтение аргументов ---
//
// Хелперы живут на JsonObject, а не на ToolCall: одни и те же обработчики
// работают и когда вызов пришёл от модели (function-calling), и когда от
// внешнего MCP-клиента (tools/call). Технически модели любят писать число
// вместо строки ("limit": 5) и "true"/"да" вместо булева — терпим.

// Строковый аргумент; пустое/"null"/отсутствие = default.
fun JsonObject.strOf(key: String, default: String = ""): String {
    val p = this[key] as? JsonPrimitive ?: return default
    if (!p.isString && p.contentOrNull?.toDoubleOrNull() != null) return p.content
    val v = p.contentOrNull?.trim().orEmpty()
    return if (v.isEmpty() || v == "null") default else v
}

fun JsonObject.intOf(key: String, default: Int): Int {
    val p = this[key] as? JsonPrimitive ?: return default
    p.contentOrNull?.trim()?.toIntOrNull()?.let { return it }
    return p.contentOrNull?.trim()?.toDoubleOrNull()?.toInt() ?: default
}

fun JsonObject.boolOf(key: String, default: Boolean = false): Boolean {
    val v = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return default
    return when (v) {
        "true", "1", "yes", "да" -> true
        "false", "0", "no", "нет" -> false
        else -> default
    }
}

// Обязательные аргументы, которых нет. Пустой список = всё на месте.
// Возвращается списком, чтобы модель увидела СРАЗУ все пропуски, а не по одному.
fun JsonObject.missingRequired(spec: ToolSpec): List<String> =
    spec.required.filter { key -> (this[key] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() }

