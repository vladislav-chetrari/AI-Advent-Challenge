package core.agent

import core.llm.ApiKeyProvider
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

// ===== Task 4: гейт разрешений на запись =====
//
// Прямая калька правил opencode (permission.ts): решение вычисляется по паре
// (действие, ресурс), дефолт — ask, а не allow. Читать можно всегда, писать
// — только по разрешению.
//
// Отличие от opencode одно и намеренное: диф считается ДО проверки правила и
// показывается в запросе (PendingChange.diff). Пользователь решает по сути
// правки, а не по слову «обновлю документацию».

enum class PermissionEffect { Allow, Deny, Ask }

enum class PermissionReply {
    // Разрешить один раз — правило не запоминается.
    Once,

    // Разрешить и запомнить правило для этого проекта и действия.
    Always,

    // Отклонить. Прогон обрывается, модель НЕ узнаёт причину (иначе она
    // попытается сделать то же самое другим способом).
    Reject,
}

@Serializable
data class PermissionRule(
    val action: String, // read | write | create | rewrite | edit | * | delete
    val resource: String, // путь или маска: "docs/*", "*"
    val effect: String = PermissionEffect.Allow.name,
) {
    val kind: PermissionEffect
        get() = runCatching { PermissionEffect.valueOf(effect) }.getOrDefault(PermissionEffect.Ask)
}

// Маска -> регулярка: только "*", как в opencode (Wildcard.match).
fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    glob.forEach { c ->
        when (c) {
            '*' -> sb.append(".*")
            '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
    }
    return Regex(sb.toString())
}

fun globMatch(glob: String, value: String): Boolean =
    glob == "*" || glob == value || globToRegex(glob).matches(value)

// Правила хранятся на проект: ~/.ai-advent-week6/permissions.json.
// «Всегда» из диалога попадает сюда и переживает перезапуск приложения.
class PermissionStore(appDir: File = ApiKeyProvider.appDir()) {
    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val file = File(appDir, "permissions.json")
    private val lock = Any()

    @Volatile
    private var all: Map<String, List<PermissionRule>> = emptyMap()

    init {
        load()
    }

    fun load() {
        synchronized(lock) {
            all = try {
                if (file.isFile) json.decodeFromString<Map<String, List<PermissionRule>>>(file.readText())
                else emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    private fun persistLocked() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(MapSerializer(String.serializer(), ListSerializer(PermissionRule.serializer())), all))
        } catch (_: Exception) {
        }
    }

    fun rules(project: String): List<PermissionRule> = all[project].orEmpty()

    fun remember(project: String, rule: PermissionRule) {
        synchronized(lock) {
            val cur = all[project].orEmpty().filterNot { it.action == rule.action && it.resource == rule.resource }
            all = all + (project to (cur + rule))
            persistLocked()
        }
    }

    fun forget(project: String) {
        synchronized(lock) {
            all = all - project
            persistLocked()
        }
    }

    // Последнее подходящее правило выигрывает (findLast в opencode).
    // Нет правила — ask: закрывать по умолчанию запись нельзя.
    fun evaluate(project: String, action: String, resource: String): PermissionEffect {
        val matched = rules(project).lastOrNull { r ->
            (r.action == "*" || globMatch(r.action, action)) && globMatch(r.resource, resource)
        }
        return matched?.kind ?: PermissionEffect.Ask
    }
}

// Решение по одной конкретной правке.
@Serializable
data class PermissionVerdict(
    val path: String,
    val effect: String,
    val remembered: Boolean = false,
)

// Связывает правила, диалог и правило «всегда».
// open — задача Task 5 (docs-sync) переопределяет проверку целиком:
// ограничение «писать только в документацию» не выражается правилами
// проекта, оно свойство самой задачи.
open class PermissionGate(
    private val store: PermissionStore,
    // null = спрашивать некому (headless/CI): пишем без вопроса.
    private val ask: (suspend (PendingChange) -> PermissionReply)? = null,
) {
    // open — см. объявление класса: docs-sync переопределяет проверку целиком.
    open suspend fun check(project: String, change: PendingChange): PermissionVerdict {
        val action = change.action
        when (store.evaluate(project, action, change.path)) {
            PermissionEffect.Allow -> return PermissionVerdict(change.path, PermissionEffect.Allow.name)
            PermissionEffect.Deny -> return PermissionVerdict(change.path, PermissionEffect.Deny.name)
            PermissionEffect.Ask -> {
                // Спрашивать некому — headless-режим считается «разрешено»,
                // но правило не запоминается молча.
                val reply = ask?.invoke(change) ?: return PermissionVerdict(change.path, PermissionEffect.Allow.name, remembered = false)
                when (reply) {
                    PermissionReply.Always -> {
                        store.remember(project, PermissionRule(action, change.path, PermissionEffect.Allow.name))
                        return PermissionVerdict(change.path, PermissionEffect.Allow.name, remembered = true)
                    }
                    PermissionReply.Reject -> return PermissionVerdict(change.path, PermissionEffect.Deny.name)
                    PermissionReply.Once -> return PermissionVerdict(change.path, PermissionEffect.Allow.name)
                }
            }
        }
    }
}
