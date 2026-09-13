package agent.domain

import kotlinx.serialization.Serializable

// Task 5, стратегия 2: Sticky Facts / Key-Value Memory.
// Facts живут отдельно от history, в запрос уходят отдельным system-блоком.
// Обновляются LLM-экстрактором после каждого ответа (см. Agent.maybeUpdateFacts).
@Serializable
data class Fact(
    val key: String,
    val value: String,
)

fun buildFactsBlock(facts: Map<String, String>): String {
    if (facts.isEmpty()) return ""
    return buildString {
        append(FACTS_PREFIX)
        append("\n")
        facts.toSortedMap().forEach { (k, v) ->
            append("- ").append(k).append(": ").append(v).append("\n")
        }
    }.trimEnd()
}

const val FACTS_PREFIX: String =
    "Sticky facts (key-value memory, trust as established context even if absent from recent messages):"

const val FACTS_EXTRACTOR_SYSTEM: String =
    "You extract sticky facts from a dialogue. Return ONLY a flat JSON object " +
        "{key: value} with durable facts (goal, constraints, preferences, decisions, names, numbers). " +
        "Keys in Russian snake_case, values concise. Record ALL durable facts, no limit on the number of keys. " +
        "No preamble, no markdown, JSON only."

/** Ленентный парсинг JSON объекта string->string из ответа экстрактора. */
fun parseFactsJson(raw: String): Map<String, String> {
    val t = raw.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = t.indexOf('{')
    val end = t.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyMap()
    val body = t.substring(start, end + 1)
    return try {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val el = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            ?: return emptyMap()
        el.entries.mapNotNull { (k, v) ->
            val key = k.trim().take(80).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = when (v) {
                is kotlinx.serialization.json.JsonPrimitive ->
                    if (v.isString) v.content else v.toString()
                else -> v.toString()
            }.trim().take(500).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to value
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}
