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

// Дистилляция ВСЕГО разговора в чистый план (Task 3).
// На вход — полный транскрипт, на выход — только шаги, без болтовни.
const val PLAN_DISTILL_SYSTEM: String =
    "You extract a clean actionable plan from a FULL conversation transcript. " +
        "Return ONLY a JSON array of strings, e.g. [\"step 1\", \"step 2\", \"step 3\"]. " +
        "Language of steps: Russian. 3-7 steps, each max 80 chars, imperative, self-contained, " +
        "no pronouns without antecedent. Merge duplicates, drop chit-chat and questions, " +
        "keep only: goal, concrete actions, deliverables, validation. Order logically. " +
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

/** Парсинг дистиллята плана: до 10 шагов, каждый ≤80 симв. */
fun parsePlanDistillJson(raw: String): List<String> {
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
                ?.trim()?.take(80)?.takeIf { it.length >= 3 }
            s
        }.take(10).filter { it.isNotBlank() }
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

    // Блок профиля. null (Аноним) или все поля пустые = нет блока, промпт не меняется.
    fun profileBlock(p: core.domain.UserProfile?): String? {
        if (p == null) return null
        val style = p.style.trim()
        val format = p.format.trim()
        val limits = p.constraints.trim()
        if (style.isBlank() && format.isBlank() && limits.isBlank()) return null
        return buildString {
            append("[Профиль: ").append(p.name.trim().ifBlank { "пользователь" }).append("]\n")
            if (style.isNotBlank()) append("Стиль: ").append(style.take(500)).append("\n")
            if (format.isNotBlank()) append("Формат: ").append(format.take(500)).append("\n")
            if (limits.isNotBlank()) append("Ограничения: ").append(limits.take(500))
        }.trimEnd()
    }

    // Task 3: блок состояния задачи — инжектится чтобы LLM не повторял пройденное
    fun taskStateBlock(state: core.domain.TaskState?, taskName: String? = null): String? {
        if (state == null) return null
        return buildString {
            append("[Задача")
            if (taskName != null) append(": ").append(taskName.take(60)) else append("")
            append("]\n")
            append("Этап: ").append(state.stage.name).append(" (").append(state.stage.label).append(")\n")
            append("Статус: ").append(state.status.name)
            if (state.status == core.domain.TaskStatus.PAUSED) append(" — на паузе, жди команду 'продолжи'")
            append("\n")
            if (state.steps.isNotEmpty()) {
                append("Шаг: ").append(state.stepIndex + 1).append("/").append(state.steps.size)
                state.steps.getOrNull(state.stepIndex)?.let { append(" — ").append(it.title.take(80)) }
                append("\n")
                // краткий прогресс шагов без повторов
                val doneTitles = state.steps.filter { it.done }.map { it.title.take(30) }
                if (doneTitles.isNotEmpty()) append("Пройдено: ").append(doneTitles.joinToString(", ")).append("\n")
            } else {
                append("Шаг: ").append(state.stepIndex + 1).append("\n")
            }
            append("Ожидаемое действие: ").append(state.nextAction.take(200)).append("\n")
            if (state.history.isNotEmpty()) {
                val hist = state.history.takeLast(5).joinToString(" → ") { "${it.from.name}→${it.to.name}" }
                append("История переходов: ").append(hist)
            }
        }.trimEnd()
    }

    // docs: уже отфильтрованные active доки релевантных scope в порядке general->project->task
    fun buildSystemPrompt(
        docs: List<Pair<String, String>>,
        profile: core.domain.UserProfile? = null,
        taskState: core.domain.TaskState? = null,
        taskName: String? = null,
    ): String {
        val block = profileBlock(profile)
        val tBlock = taskStateBlock(taskState, taskName)
        if (docs.isEmpty() && block == null && tBlock == null) return BASE
        return buildString {
            append(BASE)
            if (block != null) append("\n\n").append(block)
            if (tBlock != null) append("\n\n").append(tBlock)
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
