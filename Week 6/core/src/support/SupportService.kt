package core.support

import core.project.ProjectLoader
import core.rag.RagMessage
import core.rag.RagRef
import core.rag.RagService
import core.rag.ScoredChunk
import core.rag.RawDoc
import core.rag.RetrievalDebug
import core.rag.buildContextBlock
import core.rag.buildSupportSystem
import core.rag.buildSupportUser
import core.rag.refsFromHits
import core.rag.recentHistoryBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ===== Task 3 (День 33): ассистент поддержки =====
//
// Один диалог = один тикет (chatId == ticketId). На каждое сообщение пользователя:
//   1) MCP-клиент забирает тикет и профиль пользователя из CRM (номер не угадывается);
//   2) RAG ищет ответ в FAQ и документации проекта;
//   3) если FAQ молчит — MCP-тул search_tickets ищет похожие обращения;
//   4) LLM (DeepSeek) пишет ответ: признать проблему, опереться на тикет, дать шаг;
//   5) реплики пишутся в support_chats.json и подмешиваются в следующий промпт.
//
// Ключевое правило: тикет авторитетнее FAQ. Поэтому блок тикета уходит в промпт
// и в системный, и в пользовательский текст — так модель не теряет его даже рядом
// с длинной цитатой из FAQ.

const val SUPPORT_MIN_SCORE = 0.3f
const val SUPPORT_TOP_K = 8
const val SUPPORT_FINAL_K = 4

// Префикс RAG-документов тикетов: изолирует их от доков проекта в том же индексе.
fun ticketPrefix(project: String) = "proj:$project:$TICKET_SOURCE_DIR/"

fun ticketSource(project: String, id: String) = "${ticketPrefix(project)}$id.md"

object SupportService {
    // Сбор тикетов в RAG-индекс: их ищут семантически («такая же ошибка была у
    // других»), а не только по номеру. Источник на тикет — один, чтобы top-K мог
    // поднять конкретный чанк с номером обращения.
    suspend fun indexTickets(
        rag: RagService,
        project: String,
        tickets: List<SupportTicket>,
        onLog: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val docs = tickets.map { t ->
            RawDoc(
                source = ticketSource(project, t.id),
                title = "$project/тикет #${t.id}: ${t.title}",
                text = ticketDocText(project, t),
            )
        }
        // Тикеты, пропавшие из CRM, из индекса тоже уходят — иначе бот советует
        // по уже несуществующему обращению.
        val wanted = docs.map { it.source }.toSet()
        val stale = rag.store.sourcesByPrefix(ticketPrefix(project)).filterNot { it in wanted }
        if (stale.isNotEmpty()) {
            rag.store.deleteSources(stale)
            onLog("из индекса убрано тикетов: ${stale.size}")
        }
        if (docs.isEmpty()) return@withContext 0
        rag.markIndexing(docs, "both")
        rag.indexNewDocs(docs, "both", onLog = onLog)
        onLog("тикетов в индексе: ${docs.size}")
        docs.size
    }

    // FAQ и доки проекта минус тикеты.
    fun faqSources(rag: RagService, project: String): Set<String> {
        val tPrefix = ticketPrefix(project)
        return rag.projectSources(project).filterNot { it.startsWith(tPrefix) }.toSet()
    }

    // Поиск по FAQ с честной деградацией: без эмбеддингов (Ollama недоступна)
    // ассистент всё равно должен отвечать по тикету из CRM — молчать нельзя.
    // Второй элемент пары — предупреждение, которое уходит в SupportReply.warning.
    suspend fun searchFaq(
        rag: RagService,
        project: String,
        query: String,
        topK: Int,
        temperature: Float,
    ): Pair<List<ScoredChunk>, String> {
        val sources = faqSources(rag, project)
        if (sources.isEmpty()) return emptyList<ScoredChunk>() to "в RAG нет документов проекта $project"
        return try {
            rag.search(query, "both", topK.coerceIn(1, 20), sources)
                .filter { it.score >= temperature }
                .take(SUPPORT_FINAL_K) to ""
        } catch (e: Exception) {
            emptyList<ScoredChunk>() to "эмбеддинги недоступны (${e.message?.take(80)}), отвечаю только по тикету"
        }
    }

    // Похожие обращения (для референсов в ответе и для MCP-тула search_tickets).
    suspend fun searchTicketRefs(
        rag: RagService,
        project: String,
        query: String,
        limit: Int = 3,
    ): List<RagRef> {
        val sources = rag.store.sourcesByPrefix(ticketPrefix(project))
        if (sources.isEmpty()) return emptyList()
        return refsFromHits(rag.search(query, "both", limit, sources), excerptLen = 0)
    }

    // Один шаг диалога.
    //   mcp != null — CRM по MCP (обычный режим: бот здесь, CRM на VPS);
    //   mcp == null и crm != null — локальный режим CLI (без сети).
    suspend fun ask(
        rag: RagService,
        project: String,
        chatId: String,
        message: String,
        apiKey: String? = null,
        history: List<RagMessage> = emptyList(),
        mcp: McpClient? = null,
        crm: CrmTools? = null,
        topK: Int = SUPPORT_TOP_K,
        temperature: Float = SUPPORT_MIN_SCORE,
        onLog: (String) -> Unit = {},
    ): SupportReply = withContext(Dispatchers.IO) {
        val clean = ProjectLoader.sanitizeName(project)
        val id = chatId.trim()

        // 1) Тикет из CRM: точный доступ по номеру, а не догадка модели.
        // Блок тикета всегда начинается с "Тикет #N" — этим же проверяем, что CRM
        // ответил данными, а не «не найдено».
        val ticketText = resolveTicket(clean, id, mcp, crm, onLog)
        val usedTicket = ticketText.startsWith(TICKET_HEADER)
        val ticketId = ticketIdOf(ticketText).ifBlank { id }

        // 2) FAQ/доки проекта.
        val (hits, faqWarning) = searchFaq(rag, clean, message, topK, temperature)

        // 3) Похожие обращения — только если FAQ не ответил: иначе чужой баг
        //    перебил бы документированный ответ.
        val similar = if (hits.isEmpty() && id.isNotBlank()) {
            val text = mcp?.callTool("search_tickets", buildJsonObject {
                put("query", message)
                put("project", clean)
                put("limit", 3)
            }) ?: crm?.let { c -> runCatching { findSimilar(c, clean, message) }.getOrNull() }
            text.orEmpty()
        } else {
            ""
        }

        val historyBlock = recentHistoryBlock(history)

        // Нет ни документации, ни CRM-контекста — честный отказ шаблоном, LLM не зовём.
        if (hits.isEmpty() && !usedTicket) {
            return@withContext SupportReply(
                text = "Пока не знаю, что подсказать: в базе знаний нет подходящей страницы, " +
                    "и обращения в CRM не нашлось. Описал вопрос команде — ответим в этом чате.",
                chatId = id,
                retrieval = RetrievalDebug(),
                refused = true,
                warning = listOfNotNull(faqWarning.ifBlank { null }, "в FAQ ничего не нашлось и CRM не дала контекста")
                    .joinToString("; "),
            )
        }

        val faqContext = if (hits.isEmpty()) "" else buildContextBlock(hits)
        val system = buildSupportSystem(clean, ticketText, maxOf(hits.size, 1), historyBlock)
        val user = buildSupportUser(ticketText, faqContext, historyBlock, message) + similarBlock(similar)
        if (similar.isNotBlank()) onLog("похожие обращения подмешаны: ${similar.take(160)}…")

        val key = rag.resolveApiKey(apiKey)
        val debug = RetrievalDebug(
            retrieved = hits.size,
            kept = hits.size,
            minKeptScore = hits.minOfOrNull { it.score },
            rerankMode = "threshold",
        )
        return@withContext when (val r = rag.askLlm(system, user, key)) {
            is core.llm.LlmResult.Ok -> SupportReply(
                text = r.text.trim(),
                chatId = id,
                ticketId = ticketId,
                ticketStatus = ticketStatusOf(ticketText),
                usedTicket = usedTicket,
                faqRefs = refsFromHits(hits),
                historyUsed = history.size,
                retrieval = debug,
                warning = faqWarning,
            )
            is core.llm.LlmResult.HttpError -> SupportReply(
                text = "Поддержка временно недоступна (${rag.llmLabelOrProvider()} HTTP ${r.code}). " +
                    "Обращение #$ticketId уже зафиксировано — ответим, как связь восстановится.",
                chatId = id, ticketId = ticketId, ticketStatus = ticketStatusOf(ticketText),
                usedTicket = usedTicket, warning = "llm http ${r.code}",
            )
            is core.llm.LlmResult.NetworkError -> SupportReply(
                text = "Поддержка временно недоступна: ${r.detail.take(120)}. " +
                    "Обращение #$ticketId уже зафиксировано.",
                chatId = id, ticketId = ticketId, ticketStatus = ticketStatusOf(ticketText),
                usedTicket = usedTicket, warning = "llm network",
            )
            core.llm.LlmResult.Empty -> SupportReply(
                text = "Поддержка временно недоступна: пустой ответ модели.",
                chatId = id, ticketId = ticketId, ticketStatus = ticketStatusOf(ticketText),
                usedTicket = usedTicket, warning = "llm empty",
            )
        }
    }

    private const val TICKET_HEADER = "Тикет #"

    private fun similarBlock(similar: String) = if (similar.isBlank()) "" else
        "\n\nПОХОЖИЕ ОБРАЩЕНИЯ ДРУГИХ ПОЛЬЗОВАТЕЛЕЙ (справка, не подменяй ими ответ):\n$similar"

    // Тикет через MCP или локально. Отсутствие тикета — не ошибка: у пользователя
    // бывает вопрос до того, как он оформил обращение.
    private suspend fun resolveTicket(
        project: String,
        id: String,
        mcp: McpClient?,
        crm: CrmTools?,
        onLog: (String) -> Unit,
    ): String {
        if (id.isBlank()) return ""
        return try {
            val text = if (mcp != null) {
                mcp.callTool("get_ticket", buildJsonObject {
                    put("ticketId", id)
                    put("project", project)
                    put("full", "true")
                })
            } else {
                crm?.store(project)?.get(id)?.let { ticketSummary(it, full = true) }.orEmpty()
            }
            if (text.isBlank()) onLog("тикет #$id в CRM не найден — отвечаю только по FAQ") else Unit
            text
        } catch (e: Exception) {
            onLog("CRM не отдал тикет #$id: ${e.message}")
            ""
        }
    }

    // Лексический добор похожих обращений для локального режима (без MCP):
    // слова запроса ищем в title/body/component тикетов.
    private fun findSimilar(crm: CrmTools, project: String, query: String, limit: Int = 3): String {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.toSet()
        if (words.isEmpty()) return ""
        return crm.store(project).all()
            .map { t ->
                val hay = (t.title + " " + t.body + " " + t.component).lowercase()
                t to words.count { hay.contains(it) }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .joinToString("\n\n") { ticketSummary(it.first) }
    }

    // Из текстового блока тикета обратно в структуру (блок строит CRM-тул).
    fun ticketIdOf(ticketText: String): String =
        Regex("Тикет #(\\S+)").find(ticketText)?.groupValues?.get(1).orEmpty()

    fun ticketStatusOf(ticketText: String): String =
        Regex("Статус: ([a-z-]+)").find(ticketText)?.groupValues?.get(1).orEmpty()
}