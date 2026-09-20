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

// Task 4 (День 14): валидация EXECUTION-транскрипта по инвариантам.
// На вход — инварианты + сообщения EXECUTION, на выход — строго success либо failure+errors.
const val VALIDATE_SYSTEM: String =
    "You are a strict compliance auditor. You check whether EXECUTION results violate INVARIANTS. " +
        "Return ONLY a JSON object, e.g. {\"verdict\": \"success\", \"errors\": []} or " +
        "{\"verdict\": \"failure\", \"errors\": [{\"rule\": \"...\", \"quote\": \"...\", \"fix\": \"...\"}]}. " +
        "Language of rule/fix: Russian. verdict MUST be exactly \"success\" or \"failure\" (lowercase). " +
        "If no violation — success with empty errors. If any invariant is violated or ignored — failure " +
        "with 1-5 errors, each with rule (which invariant, max 200 chars), quote (offending fragment, max 200 chars), " +
        "fix (how to fix, max 200 chars). No preamble, no markdown, JSON object only."

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

/** Парсинг результата валидации инвариантов (Task 4). Строгий контракт: success | failure+errors. */
fun parseValidationJson(raw: String): Pair<Boolean, List<Triple<String, String, String>>> {
    val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = t.indexOf('{')
    val end = t.lastIndexOf('}')
    if (start < 0 || end <= start) return false to emptyList()
    return try {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val obj = json.parseToJsonElement(t.substring(start, end + 1))
            as? kotlinx.serialization.json.JsonObject ?: return false to emptyList()
        val verdict = obj["verdict"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        }.orEmpty()
        val arr = obj["errors"] as? kotlinx.serialization.json.JsonArray
        val errors = arr?.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun f(k: String, cap: Int): String =
                (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.take(cap).orEmpty()
            val rule = f("rule", 200)
            if (rule.length < 3) return@mapNotNull null
            Triple(rule, f("quote", 200), f("fix", 200))
        }?.take(5).orEmpty()
        // success = verdict success И пустые errors; failure = verdict failure ИЛИ есть errors
        if (verdict == "success" && errors.isEmpty()) true to emptyList()
        else if (verdict == "failure" && errors.isNotEmpty()) false to errors
        else if (errors.isNotEmpty()) false to errors
        else if (verdict == "success") true to emptyList()
        else false to listOf(Triple("Не удалось распарсить вердикт — считаем нарушением", raw.take(200), "Повторить проверку"))
    } catch (_: Exception) {
        false to emptyList()
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

    // Task 4: блок инвариантов — жёсткие ограничения, инжектятся при выполнении задачи.
    // Пустой/blank текст = нет блока, промпт не меняется.
    fun invariantsBlock(text: String?): String? {
        val clean = text?.trim().orEmpty()
        if (clean.isBlank()) return null
        return buildString {
            append("[Инварианты — НЕ НАРУШАТЬ]\n")
            append(clean.take(PER_SCOPE_CAP_CHARS))
            append("\nПравила выше обязательны. Если запрос пользователя конфликтует с ними — ")
            append("откажись предлагать нарушающее решение и объясни какой пункт нарушен и почему.")
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
            // Task 5 (День 15): явные запреты этапа — ассистент не перепрыгивает этап даже по просьбе.
            append("Запреты этапа: ").append(core.domain.TaskStateMachine.stageRules(state.stage).take(400)).append("\n")
            append("Перепрыгивать этапы запрещено: только PLANNING→EXECUTION→VALIDATION→DONE. На просьбу нарушить порядок — откажи со ссылкой на этап.").append("\n")
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
        invariants: String? = null,
    ): String {
        val block = profileBlock(profile)
        val tBlock = taskStateBlock(taskState, taskName)
        val iBlock = invariantsBlock(invariants)
        if (docs.isEmpty() && block == null && tBlock == null && iBlock == null) return BASE
        return buildString {
            append(BASE)
            if (block != null) append("\n\n").append(block)
            if (tBlock != null) append("\n\n").append(tBlock)
            if (iBlock != null) append("\n\n").append(iBlock)
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
