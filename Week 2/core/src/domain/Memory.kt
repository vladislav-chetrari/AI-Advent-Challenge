package core.domain

import kotlinx.serialization.json.contentOrNull

// Дистилляция сырого текста реплики в 1-3 компактных факта для памяти.
// Сохраняем дистиллят, а не сырец — иначе окно раздуется за пару кликов.
const val DISTILL_SYSTEM: String =
    "You distill a chat excerpt into 1-3 durable memory facts. " +
        "Return ONLY a JSON array of strings, e.g. [\"fact 1\", \"fact 2\"]. " +
        "Language of facts: Russian. Each fact max 200 chars, self-contained, no pronouns without antecedent. " +
        "Skip chit-chat, keep: user profile, decisions, tech stack, goals, constraints, task details. " +
        "No preamble, no markdown, JSON array only."

fun parseDistillJson(raw: String): List<String> {
    val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = t.indexOf('[')
    val end = t.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    return try {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val arr = json.parseToJsonElement(t.substring(start, end + 1))
            as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val s = (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?.trim()?.take(500)?.takeIf { it.isNotBlank() }
            s
        }.take(3).filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}

// Сборка system prompt с наследованием scope + cap'ы чтобы окно оставалось малым.
object PromptBuilder {
    const val BASE: String =
        "Ты coding-агент. Отвечай кратко и по-русски. " +
            "Сначала обсуждай: план, 1-2 варианта, уточняющие вопросы. " +
            "Готовый код пиши ТОЛЬКО по явной просьбе (слова «давай код», «напиши код»). " +
            "Память ниже — установленные факты, доверяй им даже если их нет в последних сообщениях."

    const val PER_SCOPE_CAP_CHARS: Int = 3200 // ~800 токенов на scope
    const val SLIDING_N: Int = 12

    fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    // docs: уже отфильтрованные active доки релевантных scope в порядке general->project->task
    fun buildSystemPrompt(docs: List<Pair<String, String>>): String {
        if (docs.isEmpty()) return BASE
        return buildString {
            append(BASE)
            for ((title, content) in docs) {
                val capped = content.take(PER_SCOPE_CAP_CHARS)
                if (capped.isBlank()) continue
                append("\n\n[Память: ").append(title).append("]\n").append(capped)
            }
        }
    }

    fun effectiveHistory(
        systemPrompt: String,
        live: List<core.domain.ChatMessage>,
        n: Int = SLIDING_N,
    ): List<core.domain.ChatMessage> {
        val tail = live.takeLast(n.coerceAtLeast(1))
        return buildList {
            add(core.domain.ChatMessage("system", systemPrompt))
            addAll(tail)
        }
    }
}
