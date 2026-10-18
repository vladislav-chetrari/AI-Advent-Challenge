package core.support

import core.llm.ApiKeyProvider
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ===== Task 3 (День 33): CRM ассистента поддержки =====
//
// Источник правды о тикетах — GitHub Issues проекта (лейбл `support` + `status:*`),
// их привозит ночной Action на /crm/import. Пользователей в продукте нет
// (приложение без регистрации), поэтому профиль юзера лежит во front-matter тикета.
//
// Локальные правки (статус «в работе», комментарий разработчика, эскалация)
// хранятся ОТДЕЛЬНО от импорта — чтобы ночная выгрузка из GitHub их не затирала.

// Профиль пользователя из front-matter тикета.
// CallowFellow без аккаунтов: plan/device/app — это то, чем он пользуется.
@Serializable
data class SupportUser(
    val id: String = "",
    val name: String = "",
    val plan: String = "",
    val device: String = "",
    val os: String = "",
    val app: String = "",
    val locale: String = "",
    val since: String = "",
)

// Тикет в терминах поддержки. Всё, что бот говорит пользователю «про этот тикет»,
// обязано быть здесь — никаких сроков и обещаний вне этих полей.
@Serializable
data class SupportTicket(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    // open | in-progress | needs-info | resolved | closed
    val status: String = TICKET_OPEN,
    val severity: String = "major", // critical | major | minor
    val type: String = "bug", // bug | question | known-issue
    val component: String = "",
    val assignee: String = "",
    // Ориентир по сроку — только если разработчик его назвал. Пусто = не обещаем.
    val eta: String = "",
    // Что именно чиним (коммит/версия/подход) — цитируется в ответе.
    val fix: String = "",
    // Обходной путь, который можно дать пользователю прямо сейчас.
    val workaround: String = "",
    val labels: List<String> = emptyList(),
    val url: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val user: SupportUser = SupportUser(),
    // Локальные пометки: комментарии поддержки/разработчика, флаг эскалации.
    val notes: List<String> = emptyList(),
    val escalated: Boolean = false,
    val escalatedReason: String = "",
)

const val TICKET_OPEN = "open"
const val TICKET_IN_PROGRESS = "in-progress"
const val TICKET_NEEDS_INFO = "needs-info"
const val TICKET_RESOLVED = "resolved"
const val TICKET_CLOSED = "closed"

val TICKET_STATUSES = listOf(
    TICKET_OPEN, TICKET_IN_PROGRESS, TICKET_NEEDS_INFO, TICKET_RESOLVED, TICKET_CLOSED,
)

fun ticketStatusName(status: String): String = when (status) {
    TICKET_OPEN -> "открыт"
    TICKET_IN_PROGRESS -> "в работе"
    TICKET_NEEDS_INFO -> "ждём ответа"
    TICKET_RESOLVED -> "решён"
    TICKET_CLOSED -> "закрыт"
    else -> status
}

// Сырой Issue с GitHub — ровно то, что шлёт Action. Фронтматтер разбирает сервер.
@Serializable
data class RawIssue(
    val number: Int,
    val title: String = "",
    val body: String = "",
    // GitHub: open | closed
    val state: String = "open",
    val labels: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
    val url: String = "",
)

// Снимок CRM: что приехало из GitHub в последний /crm/import.
@Serializable
data class CrmSnapshot(
    val project: String = "",
    val importedAt: Long = 0,
    val issues: List<RawIssue> = emptyList(),
)

// Локальные правки поверх импорта: null = «не трогать, взять из GitHub».
@Serializable
data class TicketOverride(
    val status: String? = null,
    val assignee: String? = null,
    val notes: List<String> = emptyList(),
    val escalated: Boolean? = null,
    val escalatedReason: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class CrmOverrides(
    val tickets: Map<String, TicketOverride> = emptyMap(),
)

// Ответ ассистента: текст для пользователя + чем он подкреплён.
// На видео видно всё: сработал ли тикет, откуда цитаты, была ли эскалация.
@Serializable
data class SupportReply(
    val text: String = "",
    val chatId: String = "",
    val ticketId: String = "",
    val ticketStatus: String = "",
    // Тикет реально нашёлся и подмешан в промпт (иначе бот отвечает только по FAQ).
    val usedTicket: Boolean = false,
    val escalated: Boolean = false,
    val escalateReason: String = "",
    // Цитаты из FAQ и документации.
    val faqRefs: List<core.rag.RagRef> = emptyList(),
    // Сколько реплик из истории диалога ушло в промпт.
    val historyUsed: Int = 0,
    val retrieval: core.rag.RetrievalDebug = core.rag.RetrievalDebug(),
    // Честный отказ: в базе ничего релевантного не нашлось, LLM не звали.
    val refused: Boolean = false,
    val warning: String = "",
)

// ===== Фронтматтер =====
// YAML-подмножество без зависимости на YAML-библиотеку: разбираем ровно то, что
// сами пишем в теле Issue (вложенный map + скаляры). Всё остальное — тело тикета.
object FrontMatter {
    // "---" в начале + "---" закрывает блок. Остальное — тело.
    fun split(text: String): Pair<String, String> {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return "" to text
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                return lines.subList(1, i).joinToString("\n") to lines.subList(i + 1, lines.size).joinToString("\n")
            }
        }
        return "" to text
    }

    // Плоская карта "ticket.user.id" -> значение. Стек по отступу вместо
    // полноценного парсера вложенности — формат фиксирован и проверяется тестом.
    fun parseMap(fm: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val stack = ArrayDeque<Pair<Int, String>>()
        for (raw in fm.lines()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().trim('"', '\'')
            val value = line.substring(colon + 1).trim()
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
            if (value.isEmpty()) {
                stack.addLast(indent to key)
            } else {
                out[(stack.map { it.second } + key).joinToString(".")] = unquote(value)
            }
        }
        return out
    }

    private fun unquote(v: String): String {
        val t = v.trim()
        return if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))) {
            t.substring(1, t.length - 1)
        } else {
            t
        }
    }
}

// Issue -> тикет поддержки: лейблы GitHub + front-matter.
object TicketMapper {
    // Лейбл вида "status:in-progress" -> ("status", "in-progress").
    // Обычные лейблы (support, needs-triage) игнорируем: они про процесс, не про состояние.
    private val LABELED = setOf("status", "severity", "type", "component")

    fun toTicket(issue: RawIssue): SupportTicket {
        val (fm, body) = FrontMatter.split(issue.body)
        val m = FrontMatter.parseMap(fm)
        val labels = issue.labels.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val labeled = labels.filter { it.contains(':') }.mapNotNull { l ->
            val i = l.indexOf(':')
            val k = l.substring(0, i).trim()
            if (k in LABELED) k to l.substring(i + 1).trim() else null
        }.toMap()
        // Явный status: в лейбле приоритетнее закрытого state (issue закрыт,
        // но помечен resolved — отвечаем «исправлено», а не «тема закрыта»).
        val status = labeled["status"] ?: if (issue.state.equals("closed", true)) TICKET_CLOSED else TICKET_OPEN
        return SupportTicket(
            id = issue.number.toString(),
            title = issue.title.trim(),
            body = body.trim(),
            status = if (status in TICKET_STATUSES) status else TICKET_OPEN,
            severity = labeled["severity"] ?: m["ticket.severity"] ?: "major",
            type = labeled["type"] ?: m["ticket.type"] ?: "bug",
            component = labeled["component"] ?: m["ticket.component"].orEmpty(),
            assignee = (m["ticket.assignee"]?.takeIf { it.isNotBlank() } ?: issue.assignees.firstOrNull())
                ?.trim()?.trimStart('@').orEmpty(),
            eta = m["ticket.eta"].orEmpty(),
            fix = m["ticket.fix"].orEmpty(),
            workaround = m["ticket.workaround"].orEmpty(),
            labels = labels,
            url = issue.url,
            createdAt = issue.createdAt,
            updatedAt = issue.updatedAt,
            user = SupportUser(
                id = m["ticket.user.id"].orEmpty(),
                name = m["ticket.user.name"].orEmpty(),
                plan = m["ticket.user.plan"].orEmpty(),
                device = m["ticket.user.device"].orEmpty(),
                os = m["ticket.user.os"].orEmpty(),
                app = m["ticket.user.app"].orEmpty(),
                locale = m["ticket.user.locale"].orEmpty(),
                since = m["ticket.user.since"].orEmpty(),
            ),
        )
    }
}

// Текст тикета для RAG-индекса: его ищем семантически, а не только по номеру.
// Одна запись = один тикет, чтобы top-K мог поднять конкретный чанк.
fun ticketDocText(project: String, t: SupportTicket): String = buildString {
    appendLine("Тикет #${t.id}: ${t.title}")
    appendLine("Продукт: $project. Статус: ${ticketStatusName(t.status)}. Тип: ${t.type}. Важность: ${t.severity}.")
    if (t.component.isNotBlank()) appendLine("Компонент: ${t.component}.")
    appendLine(
        "Пользователь: ${t.user.name.ifBlank { "без имени" }} (id ${t.user.id}, план ${t.user.plan}, " +
            "устройство ${t.user.device}, ${t.user.os}, версия ${t.user.app})."
    )
    if (t.assignee.isNotBlank()) appendLine("Ответственный: ${t.assignee}.")
    if (t.workaround.isNotBlank()) appendLine("Обходной путь: ${t.workaround}")
    if (t.fix.isNotBlank()) appendLine("Что чиним: ${t.fix}")
    if (t.eta.isNotBlank()) appendLine("Ориентир по сроку: ${t.eta}")
    if (t.notes.isNotEmpty()) appendLine("Комментарии поддержки: ${t.notes.joinToString(" | ")}")
    if (t.body.isNotBlank()) {
        appendLine("Обращение пользователя:")
        appendLine(t.body.take(TICKET_BODY_CHARS))
    }
}

// Детерминированная подсказка по статусу: маленькие модели путают resolved и
// in-progress, поэтому «что именно говорить» считаем из CRM в коде, а LLM
// остаётся только за формулировками. Это же делает поведение воспроизводимым.
fun ticketGuidance(t: SupportTicket): String = buildString {
    append("ЧТО СООБЩИТЬ ПО ЭТОМУ СТАТУСУ: ")
    when (t.status) {
        TICKET_IN_PROGRESS -> {
            append("проблема уже в работе")
            if (t.assignee.isNotBlank()) append(", её взял ${t.assignee}")
            append(". ")
            if (t.workaround.isNotBlank()) append("Дай обходной путь как пошаговую инструкцию. ")
            else append("Обходного пути нет — не придумывай его. ")
            if (t.eta.isNotBlank()) append("Ориентир по срокам, который можно назвать: ${t.eta}. ")
            else append("Срока нет — так и скажи словами «ориентира по срокам пока нет». ")
        }
        TICKET_OPEN -> {
            append("обращение зарегистрировано, но конкретной работы по нему ещё нет. ")
            append("Скажи, что запрос передали команде, и НЕ называй ни сроков, ни причин. ")
            if (t.workaround.isNotBlank()) append("Обходной путь, который можно дать сразу: ${t.workaround}")
        }
        TICKET_NEEDS_INFO -> {
            append("от пользователя не хватает данных, поэтому разбирать пока нечего. ")
            append("Главное — спросить недостающее (логи, версия приложения, устройство, скриншот) и объяснить зачем. ")
            append("Сроков не называть.")
        }
        TICKET_RESOLVED -> {
            append("проблема УЖЕ ИСПРАВЛЕНА, а не «в работе». ")
            append("Скажи, что исправлено: ${t.fix.ifBlank { "работа завершена" }} ")
            if (t.eta.isNotBlank()) append("Версия/срок: ${t.eta}. ")
            append("Подскажи, как обновиться.")
        }
        TICKET_CLOSED -> append("обращение закрыто. Сообщи итог: ${t.fix.ifBlank { "работа завершена" }} Срока не обещать.")
        else -> append("статус неизвестен — спроси пользователя детали и скажи, что уточняешь у команды. ")
    }
}

const val TICKET_BODY_CHARS = 3000
const val TICKET_SOURCE_DIR = "support/tickets"

// Хранилище CRM на сервере: импорт из GitHub + локальные правки поверх.
// crm/<project>.json — снимок, crm/<project>.overrides.json — наши правки.
class CrmStore(
    project: String,
    appDir: File = ApiKeyProvider.appDir(),
) {
    // Имя проекта нормализуем: в секрете REVIEW_PROJECT часто остаётся пробел
    // (" CallowFellow"), и тогда CRM писала в файл " CallowFellow.json", а RAG
    // смотрел в "CallowFellow" — тикеты находились, но не для того проекта.
    private val project = project.trim()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(appDir, "crm/$project.json")
    private val overridesFile = File(appDir, "crm/$project.overrides.json")
    private val lock = Any()

    private var snapshot: CrmSnapshot = CrmSnapshot()
    private var overrides: CrmOverrides = CrmOverrides()

    init {
        file.parentFile?.mkdirs()
        load()
    }

    private fun load() {
        synchronized(lock) {
            snapshot = read(file) ?: CrmSnapshot(project = project)
            overrides = read(overridesFile) ?: CrmOverrides()
        }
    }

    private inline fun <reified T> read(f: File): T? = try {
        if (f.isFile) json.decodeFromString<T>(f.readText()) else null
    } catch (_: Exception) {
        null
    }

    private fun persistLocked() {
        runCatching { file.writeText(json.encodeToString(CrmSnapshot.serializer(), snapshot)) }
        runCatching { overridesFile.writeText(json.encodeToString(CrmOverrides.serializer(), overrides)) }
    }

    // Импорт из GitHub: снимок затирается целиком, локальные правки переживают
    // (они в отдельном файле) — иначе ночная выгрузка съела бы «в работе».
    fun import(issues: List<RawIssue>, onLog: (String) -> Unit = {}): CrmImportResult {
        val tickets = issues.map { TicketMapper.toTicket(it) }
        val stale = synchronized(lock) {
            val before = snapshot.issues.map { it.number.toString() }.toSet()
            snapshot = CrmSnapshot(project, System.currentTimeMillis(), issues)
            val after = tickets.map { it.id }.toSet()
            // Тикеты, которых больше нет в GitHub, оставляем в overrides — их статусы
            // нельзя потерять молча; но из выдачи они уйдут (их нет в снимке).
            (before - after).also { persistLocked() }
        }
        if (stale.isNotEmpty()) onLog("из CRM исчезли тикеты: ${stale.joinToString(", ")}")
        val byStatus = tickets.groupingBy { it.status }.eachCount()
            .entries.joinToString(", ") { "${ticketStatusName(it.key)}: ${it.value}" }
        onLog("CRM импорт: тикетов ${tickets.size} ($byStatus)")
        return CrmImportResult(
            project = project,
            imported = tickets.size,
            open = tickets.count { it.status == TICKET_OPEN || it.status == TICKET_IN_PROGRESS || it.status == TICKET_NEEDS_INFO },
            importedAt = snapshot.importedAt,
            gone = stale.toList().sorted(),
            tickets = tickets.sortedByDescending { it.id.toIntOrNull() ?: 0 },
        )
    }

    // Проекция: снимок из GitHub + локальные override'ы.
    fun all(): List<SupportTicket> = synchronized(lock) {
        snapshot.issues.map { applyOverride(TicketMapper.toTicket(it)) }
    }

    fun get(id: String): SupportTicket? = all().firstOrNull { it.id == id.trim() }

    fun open(onlyActive: Boolean = true): List<SupportTicket> = all()
        .filter { !onlyActive || it.status in setOf(TICKET_OPEN, TICKET_IN_PROGRESS, TICKET_NEEDS_INFO) }

    fun byUser(userId: String): List<SupportTicket> =
        all().filter { it.user.id.isNotBlank() && it.user.id == userId.trim() }

    // Локальная правка статуса (или ответ пользователю через эскалацию).
    fun patch(id: String, status: String? = null, assignee: String? = null, comment: String? = null): SupportTicket {
        val key = id.trim()
        synchronized(lock) {
            val cur = overrides.tickets[key] ?: TicketOverride()
            overrides = CrmOverrides(
                overrides.tickets + (key to cur.copy(
                    status = status ?: cur.status,
                    assignee = assignee ?: cur.assignee,
                    notes = if (comment.isNullOrBlank()) cur.notes else cur.notes + comment.trim(),
                    updatedAt = System.currentTimeMillis(),
                ))
            )
            persistLocked()
        }
        return get(key) ?: throw IllegalStateException("тикет #$key не найден в CRM проекта «$project»")
    }

    fun escalate(id: String, reason: String): SupportTicket = synchronized(lock) {
        val key = id.trim()
        val cur = overrides.tickets[key] ?: TicketOverride()
        overrides = CrmOverrides(
            overrides.tickets + (key to cur.copy(
                escalated = true,
                escalatedReason = reason,
                updatedAt = System.currentTimeMillis(),
            ))
        )
        persistLocked()
        get(key) ?: throw IllegalStateException("тикет #$key не найден в CRM проекта «$project»")
    }

    fun importedAt(): Long = synchronized(lock) { snapshot.importedAt }

    private fun applyOverride(t: SupportTicket): SupportTicket {
        val o = overrides.tickets[t.id] ?: return t
        return t.copy(
            status = o.status ?: t.status,
            assignee = o.assignee ?: t.assignee,
            notes = o.notes,
            escalated = o.escalated ?: false,
            escalatedReason = o.escalatedReason ?: "",
        )
    }
}

// Помним, какое обращение обсуждаем в диалоге. Продолжение разговора
// («а сроки какие?») не содержит ключевых слов и заново не ищется — иначе
// ассистент теряет контекст между репликами.
class SupportSessions(private val max: Int = 1000) {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun ticketOf(chatId: String): String? = map[chatId]?.takeIf { it.isNotBlank() }

    fun remember(chatId: String, ticketId: String) {
        if (ticketId.isBlank()) return
        if (map.size > max) map.clear()
        map[chatId] = ticketId
    }
}

@Serializable
data class CrmImportResult(
    val project: String = "",
    val imported: Int = 0,
    val open: Int = 0,
    val importedAt: Long = 0,
    val gone: List<String> = emptyList(),
    val tickets: List<SupportTicket> = emptyList(),
)

// Текущее состояние CRM проекта (после наложения локальных правок) — для CLI и /crm/tickets.
@Serializable
data class CrmState(
    val project: String = "",
    val importedAt: Long = 0,
    val open: Int = 0,
    val escalated: Int = 0,
    val tickets: List<SupportTicket> = emptyList(),
    // Режим "*": какие проекты вообще есть в CRM (нужно, когда имя проекта задано
    // секретом и не видно в логах).
    val projects: List<CrmProjectBrief> = emptyList(),
)

@Serializable
data class CrmProjectBrief(
    val project: String = "",
    val importedAt: Long = 0,
    val open: Int = 0,
    val total: Int = 0,
)