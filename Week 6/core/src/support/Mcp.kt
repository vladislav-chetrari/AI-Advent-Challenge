package core.support

import core.llm.ApiKeyProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// ===== Task 3: CRM как MCP-сервер =====
//
// Поддержка общается с CRM по MCP (JSON-RPC 2.0), а не прямым вызовом Kotlin:
// бот — MCP-клиент, CRM — MCP-сервер. Тот же приём, что и в Task 2 (движок на
// VPS, проект приходит по HTTP), только источник данных вместо git.
//
// Реализован подмножество протокола, нужное для тулов:
//   initialize / notifications/initialized / ping / tools/list / tools/call
// Зависимостей нет — JSON-RPC поверх обычного POST /mcp.

private val mcpJson = Json { prettyPrint = false; ignoreUnknownKeys = true; isLenient = true }
private val mcpJsonOut = Json { prettyPrint = true; encodeDefaults = true }

const val MCP_PROTOCOL_VERSION = "2024-11-05"
const val MCP_SERVER_NAME = "callowfellow-support-crm"
const val MCP_SERVER_VERSION = "0.1.0"

private fun obj(vararg pairs: Pair<String, String>) = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, v) }
}

private fun str(o: JsonObject, key: String): String =
    (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

private fun intOf(o: JsonObject, key: String, def: Int): Int =
    (o[key] as? JsonPrimitive)?.intOrNull ?: def

// Номер тикета из референса: chunkId = "<source>#<ord>", source заканчивается "42.md".
internal fun ticketIdOfRef(ref: core.rag.RagRef): String =
    ref.chunkId.substringAfterLast('/').substringBefore('#').removeSuffix(".md")

// Описание одного MCP-тула: схема аргументов + исполнение.
class McpToolDef(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val handler: suspend (JsonObject) -> String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", schema)
    }
}

// Реестр тулов: tools/list и tools/call живут здесь, транспорт — снаружи.
class McpToolRegistry(private val defaultProject: String) {
    private val tools = LinkedHashMap<String, McpToolDef>()

    fun register(def: McpToolDef) {
        require(def.name.isNotBlank()) { "имя тула пустое" }
        tools[def.name] = def
    }

    fun names(): List<String> = tools.keys.toList()

    fun list(): JsonArray = JsonArray(tools.values.map { it.toJson() })

    suspend fun call(name: String, args: JsonObject): String {
        val def = tools[name] ?: throw IllegalArgumentException("нет тула «$name». Доступны: ${names().joinToString(", ")}")
        return def.handler(args)
    }

    fun project(args: JsonObject): String =
        str(args, "project").ifBlank { defaultProject }
}

// Проекция тикета в компактный текст для агента: то, что реально видно модели.
// Статус пишем кодом + переводом ("in-progress (в работе)"): код нужен и модели для
// правил промпта, и SupportService.ticketStatusOf для ответа в JSON.
fun ticketSummary(t: SupportTicket, full: Boolean = false): String = buildString {
    appendLine("Тикет #${t.id} — ${t.title}")
    appendLine("Статус: ${t.status} (${ticketStatusName(t.status)}) · тип: ${t.type} · важность: ${t.severity} · компонент: ${t.component.ifBlank { "—" }}")
    // Что именно сообщить по статусу — считает CRM, не модель: мелкие LLM путают
    // resolved и in-progress, а ответ должен зависеть только от данных тикета.
    appendLine(ticketGuidance(t))
    appendLine("Пользователь: ${t.user.name.ifBlank { "без имени" }} (id ${t.user.id}, план ${t.user.plan.ifBlank { "—" }}, устройство ${t.user.device.ifBlank { "—" }}, ${t.user.os}, версия ${t.user.app}, язык ${t.user.locale})")
    if (t.assignee.isNotBlank()) appendLine("Ответственный: ${t.assignee}")
    if (t.workaround.isNotBlank()) appendLine("Обходной путь: ${t.workaround}")
    if (t.fix.isNotBlank()) appendLine("В работе/исправлено: ${t.fix}")
    if (t.eta.isNotBlank()) appendLine("Ориентир по сроку: ${t.eta}") else appendLine("Ориентир по сроку: не задан")
    if (t.notes.isNotEmpty()) appendLine("Комментарии: ${t.notes.joinToString(" | ")}")
    if (t.escalated) appendLine("ЭСКАЛАЦИЯ: ${t.escalatedReason}")
    if (full && t.body.isNotBlank()) {
        appendLine("Текст обращения:")
        appendLine(t.body.take(TICKET_BODY_CHARS))
    }
    if (t.url.isNotBlank()) appendLine("Ссылка: ${t.url}")
}

// CRM-тулы: точный доступ по номеру + семантический поиск по тикетам.
class CrmTools(
    private val defaultProject: String = "CallowFellow",
    private val appDir: File = ApiKeyProvider.appDir(),
    // Семантический поиск по тикетам внедряется хостом (RagService): без него
    // search_tickets откатится на лексику, что тоже работает, но хуже.
    private val search: (suspend (project: String, query: String, limit: Int) -> List<core.rag.RagRef>)? = null,
) {
    // Кэш на процесс: статус, поставленный через /crm/ticket, должен быть виден
    // следующему запросу без перечитывания файла (иначе демо «разработчик починил» мигает).
    private val stores = ConcurrentHashMap<String, CrmStore>()

    fun store(project: String = defaultProject): CrmStore =
        stores.computeIfAbsent(project) { CrmStore(it, appDir) }

    fun registry(): McpToolRegistry = McpToolRegistry(defaultProject).apply {
        register(getTicket())
        register(listOpenTickets())
        register(searchTickets())
        register(userTickets())
    }

    // Проект из аргументов тула: пусто -> проект по умолчанию.
    private fun proj(args: JsonObject): String =
        (args["project"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: defaultProject

    // Описание аргументов тула: properties + required — как требует MCP tools/list.
    private fun schema(
        props: List<Pair<String, Pair<String, String>>>,
        required: List<String> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    props.forEach { (name, spec) ->
                        put(name, buildJsonObject { put("type", spec.first); put("description", spec.second) })
                    }
                },
            )
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
        }

    private fun getTicket() = McpToolDef(
        name = "get_ticket",
        description = "Тикет поддержки по номеру: статус, ответственный, обходной путь, срок, профиль пользователя.",
        schema = schema(
            listOf(
                "ticketId" to ("string" to "номер тикета, напр. 42"),
                "project" to ("string" to "имя проекта в CRM (необязательно)"),
                "full" to ("boolean" to "включить текст обращения целиком"),
            ),
            listOf("ticketId"),
        ),
        handler = { args ->
            val p = proj(args)
            val id = str(args, "ticketId")
            val t = store(p).get(id)
                ?: throw IllegalArgumentException("тикет #$id не найден в проекте $p")
            ticketSummary(t, full = str(args, "full") == "true")
        },
    )

    private fun listOpenTickets() = McpToolDef(
        name = "list_open_tickets",
        description = "Открытые тикеты (open / in-progress / needs-info) — оценка нагрузки на поддержку.",
        schema = schema(
            listOf(
                "project" to ("string" to "имя проекта в CRM (необязательно)"),
                "limit" to ("integer" to "сколько вернуть, по умолчанию 20"),
                "status" to ("string" to "фильтр по статусу"),
            ),
        ),
        handler = { args ->
            val p = proj(args)
            val limit = intOf(args, "limit", 20).coerceIn(1, 100)
            val status = str(args, "status")
            val list = store(p).open()
                .filter { status.isBlank() || it.status == status }
                .sortedBy { it.severity }
                .take(limit)
            if (list.isEmpty()) "Открытых тикетов нет." else list.joinToString("\n\n") { ticketSummary(it) }
        },
    )

    private fun searchTickets() = McpToolDef(
        name = "search_tickets",
        description = "Похожие обращения других пользователей: семантический поиск по тексту тикетов, добор по ключевым словам.",
        schema = schema(
            listOf(
                "query" to ("string" to "текст проблемы пользователя"),
                "project" to ("string" to "имя проекта в CRM (необязательно)"),
                "limit" to ("integer" to "сколько вернуть, по умолчанию 3"),
                "status" to ("string" to "фильтр по статусу"),
            ),
            listOf("query"),
        ),
        handler = { args ->
            val p = proj(args)
            val query = str(args, "query")
            val limit = intOf(args, "limit", 3).coerceIn(1, 10)
            val status = str(args, "status")
            val all = store(p).all().filter { status.isBlank() || it.status == status }
            // Векторная выдача — основная; если её нет (индекс не пересобирался или
            // запрос слишком общий) — добираем лексикой по title/body.
            val hits = search?.invoke(p, query, limit)
                .orEmpty()
                .mapNotNull { ref -> all.firstOrNull { it.id == ticketIdOfRef(ref) } }
            val picked = hits.ifEmpty { lexicalFallback(all, query, limit) }
            if (picked.isEmpty()) {
                "Похожих обращений не нашлось. Отвечай только по FAQ и тикету текущего обращения."
            } else {
                picked.joinToString("\n\n") { ticketSummary(it) }
            }
        },
    )

    private fun userTickets() = McpToolDef(
        name = "get_user_tickets",
        description = "Все обращения пользователя по его id — контекст \"что у этого человека уже было\".",
        schema = schema(
            listOf(
                "userId" to ("string" to "id пользователя из тикета"),
                "project" to ("string" to "имя проекта в CRM (необязательно)"),
            ),
            listOf("userId"),
        ),
        handler = { args ->
            val p = proj(args)
            val uid = str(args, "userId")
            val list = store(p).byUser(uid)
            if (list.isEmpty()) {
                "У пользователя $uid других обращений нет."
            } else {
                list.joinToString("\n\n") { ticketSummary(it) }
            }
        },
    )

    // Грубый, но предсказуемый добор: слово из запроса встречается в title/body тикета.
    private fun lexicalFallback(all: List<SupportTicket>, query: String, limit: Int): List<SupportTicket> {
        val words = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 4 }
            .toSet()
        if (words.isEmpty()) return emptyList()
        return all
            .map { t ->
                val hay = (t.title + " " + t.body + " " + t.component).lowercase()
                val score = words.count { hay.contains(it) }
                t to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}

// JSON-RPC 2.0: разбор запроса, маршрутизация, сбор ответа. Транспорт не наш.
class McpServer(private val registry: McpToolRegistry) {
    suspend fun handle(body: String): String {
        val req = try {
            mcpJson.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return rpcError(JsonNull, -32700, "не разобрал JSON: ${e.message}")
        }
        val id = req["id"] ?: JsonNull
        val method = str(req, "method")
        // Уведомление (id == null) — ответ не отправляем вовсе, но и не падаем.
        return try {
            when (method) {
                "initialize" -> rpcResult(
                    id,
                    buildJsonObject {
                        put("protocolVersion", MCP_PROTOCOL_VERSION)
                        put(
                            "capabilities",
                            buildJsonObject { put("tools", buildJsonObject {}) },
                        )
                        put(
                            "serverInfo",
                            obj("name" to MCP_SERVER_NAME, "version" to MCP_SERVER_VERSION),
                        )
                    },
                )
                "notifications/initialized", "ping" -> if (id is JsonNull) "" else rpcResult(id, buildJsonObject {})
                "tools/list" -> rpcResult(id, buildJsonObject { put("tools", registry.list()) })
                "tools/call" -> {
                    val params = (req["params"] as? JsonObject) ?: buildJsonObject {}
                    val name = str(params, "name")
                    val args = (params["arguments"] as? JsonObject) ?: buildJsonObject {}
                    // Ошибка тула — это содержимое ответа (isError), а не JSON-RPC error:
                    // так агент прочитает текст и скажет пользователю, а не упадёт.
                    val text = try {
                        registry.call(name, args)
                    } catch (e: Exception) {
                        "Ошибка тула $name: ${e.message}"
                    }
                    rpcResult(
                        id,
                        buildJsonObject {
                            put(
                                "content",
                                buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) },
                            )
                            put("isError", false)
                        },
                    )
                }
                else -> if (id is JsonNull) "" else rpcError(id, -32601, "метод не поддерживается: $method")
            }
        } catch (e: Exception) {
            if (id is JsonNull) "" else rpcError(id, -32603, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun rpcResult(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.let { mcpJsonOut.encodeToString(JsonObject.serializer(), it) }

    private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }.let { mcpJsonOut.encodeToString(JsonObject.serializer(), it) }
}

// MCP-клиент: так ассистент поддержки ходит в CRM.
class McpClient(
    private val baseUrl: String,
    private val token: String,
) : AutoCloseable {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val http by lazy {
        HttpClient(io.ktor.client.engine.cio.CIO) {
            expectSuccess = true
        }
    }

    // Базовый URL сервиса -> адрес MCP-эндпоинта.
    private val endpoint = baseUrl.trimEnd('/').let {
        if (it.endsWith("/mcp")) it else "$it/mcp"
    }

    private var rpcId = 0
    private val serverInfo: MutableMap<String, String> = linkedMapOf()

    private suspend fun rpc(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ++rpcId)
            put("method", method)
            put("params", params)
        }
        val res = http.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), payload))
        }
        val text = res.bodyAsText()
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            throw IllegalStateException("MCP $endpoint вернул не JSON: ${text.take(200)}")
        }
        (obj["error"] as? JsonObject)?.let { e ->
            throw IllegalStateException("MCP ошибка ${str(e, "code")}: ${str(e, "message")}")
        }
        return (obj["result"] as? JsonObject) ?: buildJsonObject {}
    }

    suspend fun initialize(): Map<String, String> {
        val r = rpc(
            "initialize",
            buildJsonObject {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "week6-support-agent")
                        put("version", MCP_SERVER_VERSION)
                    },
                )
            },
        )
        val info = (r["serverInfo"] as? JsonObject)
            ?.let { mapOf("name" to str(it, "name"), "version" to str(it, "version")) }
            ?: emptyMap()
        serverInfo.clear()
        serverInfo.putAll(info)
        return serverInfo
    }

    data class ToolInfo(val name: String, val description: String)

    suspend fun toolsList(): List<ToolInfo> {
        val r = rpc("tools/list")
        return (r["tools"] as? JsonArray)?.map {
            val o = it.jsonObject
            ToolInfo(str(o, "name"), str(o, "description"))
        } ?: emptyList()
    }

    // Текст первого content-блока — как его отдаёт любой MCP-сервер.
    suspend fun callTool(name: String, args: JsonObject = buildJsonObject {}): String {
        val r = rpc("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", args)
        })
        val content = r["content"] as? JsonArray ?: return ""
        return content.mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull }
            .joinToString("\n")
    }

    override fun close() = http.close()
}

// Конструкция аргументов тула из пар — чтобы вызовы читались как в MCP-доках.
fun toolArgs(vararg pairs: Pair<String, String?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> if (v != null) put(k, v) }
}