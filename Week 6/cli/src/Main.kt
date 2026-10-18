package cli

import core.llm.ApiKeyProvider
import core.rag.ChatStore
import core.rag.DEFAULT_FINAL_K
import core.rag.DEFAULT_MIN_SCORE
import core.rag.DEFAULT_PROBES
import core.rag.DEFAULT_RETRIEVE_K
import core.rag.RagChat
import core.rag.RagMessage
import core.rag.RagService
import core.rag.RerankModes
import core.rag.ReviewResult
import core.rag.newChatId
import core.support.CrmState
import core.support.CrmTools
import core.support.McpClient
import core.support.RawIssue
import core.support.SupportReply
import core.support.SupportService
import core.support.SupportTicket
import core.support.TICKET_STATUSES
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Общий сериализатор для review.json: полные дефолты, чтобы post_review.py
// и VPS всегда видели одинаковую схему.
private val reviewOutJson = Json { prettyPrint = true; encodeDefaults = true }

// Headless-доступ к тому же RagService, что и у desktop-приложения.
// Единственный источник документов — статьи Википедии.
//
//   week4 wiki <название статьи|URL> [...] [--strategy both]
//   week4 search <запрос...> [--strategy structure] [--topK 5] [--minScore 0.35]
//   week4 ask <вопрос...> [--strategy structure] [--topK 20] [--filter on|off] [--temperature 0.35] [--postK 5|off] [--rewrite on|off] [--chat <название>]
//   week4 chat [--chat <название>] [--strategy both] [--topK 20] [--filter on|off] [--temperature 0.35] [--postK 5|off] [--rewrite on|off]
//   week4 eval-modes [--probe "вопрос..."] [--strategy both]  (Задание 3: без фильтра vs фильтр vs фильтр+rewrite)
fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty()) {
        usage()
        return@runBlocking
    }
    try {
        runCommand(args)
    } catch (e: IllegalArgumentException) {
        // fail() уже напечатал usage — короткий итог
        println("ОШИБКА: ${e.message}")
    } catch (e: Exception) {
        println("ОШИБКА: ${e.message}")
    }
}

private suspend fun runCommand(args: Array<String>) {
    val svc = RagService()
    when (args[0]) {
        "wiki" -> {
            val (inputs, rest) = splitPaths(args)
            if (inputs.isEmpty()) fail("нужна статья: week4 wiki <название|URL>")
            val strategy = flag(rest, "--strategy") ?: "both"
            val list = if (strategy == "both") listOf("fixed", "structure") else listOf(strategy)
            for (input in inputs) {
                println("Загружаю Википедию: $input ...")
                val doc = svc.fetchWikiDoc(input)
                println("Статья: ${doc.title} (${doc.text.length / 1024} КБ)")
                for (s in list) {
                    val stats = svc.indexDocs(listOf(doc), s) { println(it) }
                    println("ИТОГ [$s]: ${stats.chunks} чанков (avg ${stats.avgChars} симв)")
                }
            }
        }
        "search" -> {
            val (query, rest) = splitQuery(args)
            val strategy = flag(rest, "--strategy") ?: "structure"
            val topK = flag(rest, "--topK")?.toIntOrNull() ?: 5
            // Task 3: --minScore помечает кандидатов ниже порога как отсечённые.
            val minScore = flag(rest, "--minScore")?.toFloatOrNull()
            val hits = svc.search(query, strategy, topK)
            if (hits.isEmpty()) println("Ничего не найдено (индекс пуст?). Сначала: week4 index <путь...>")
            hits.forEachIndexed { i, h ->
                val cut = minScore != null && h.score < minScore
                println("[${"%.4f".format(h.score)}]${if (cut) " ×ОТСЕЧЁН" else ""} ${h.chunk.title} / ${h.chunk.section.ifBlank { "—" }} (${h.chunk.source})")
                println("  ${h.chunk.text.take(300).replace("\n", " ")}")
                if (i < hits.size - 1) println()
            }
        }
        "ask" -> {
            val (query, rest) = splitQuery(args)
            val strategy = flag(rest, "--strategy") ?: "structure"
            // Задание 3: --topK — до фильтрации (= старый --retrieveK);
            // фильтр --filter on|off (дефолт on), --temperature 0..1 (алиас --minScore),
            // --postK — top-K после фильтрации 1..topK ("off"/пусто = без ограничения).
            val topK = flag(rest, "--topK")
                ?: flag(rest, "--retrieveK") ?: DEFAULT_RETRIEVE_K.toString()
            val topKInt = topK.toIntOrNull() ?: DEFAULT_RETRIEVE_K
            val filterFlag = flag(rest, "--filter")?.lowercase()
            val rerankFlag = flag(rest, "--rerank")?.lowercase()
            val filterEnabled = when {
                filterFlag != null -> filterFlag != "off" && filterFlag != "0" && filterFlag != "false"
                rerankFlag != null -> rerankFlag != RerankModes.OFF && rerankFlag != "0" && rerankFlag != "false"
                else -> true
            }
            val temperature = flag(rest, "--temperature")?.toFloatOrNull()
                ?: flag(rest, "--minScore")?.toFloatOrNull() ?: DEFAULT_MIN_SCORE
            val postKRaw = flag(rest, "--postK") ?: flag(rest, "--post-filter-k") ?: flag(rest, "--finalK")
            val postFilterK: Int? = when {
                postKRaw == null -> DEFAULT_FINAL_K
                postKRaw.equals("off", true) || postKRaw.equals("none", true) || postKRaw.isBlank() -> null
                else -> postKRaw.toIntOrNull()
            }
            val rewrite = flag(rest, "--rewrite") == "on"
            // Task 5: --chat <название> — разовый вопрос в контексте чата:
            // история + факты подмешиваются в поиск и промпт, память обновляется.
            val chatName = flag(rest, "--chat")
            val chats = if (chatName != null) ChatStore() else null
            val chatId = if (chatName != null && chats != null) findOrCreateChat(chats, chatName) else null
            val history = if (chatId != null && chats != null) chats.state.messages[chatId].orEmpty() else emptyList()
            val memory = if (chatId != null && chats != null) chats.getMemory(chatId) else null
            val ans = svc.ask(query, strategy, topKInt, onlySources = svc.activeSourcesOrNull(),
                filterEnabled = filterEnabled, temperature = temperature,
                postFilterK = postFilterK, rewrite = rewrite,
                history = history, memory = memory)
            println(ans.text)
            println("\n[${ans.retrieval.summary(temperature)}]")
            if (ans.retrieval.rewritten.isNotEmpty()) {
                println("Rewrite-запросы:")
                ans.retrieval.rewritten.forEach { println("  + $it") }
            }
            // Task 4: тот же формат, что в GUI — коротко [N], детали + цитата ниже.
            if (ans.refs.isNotEmpty()) {
                println("Источники:")
                ans.refs.forEach { r ->
                    println("  [${r.index}] ${r.title} / ${r.section.ifBlank { "—" }} (${"%.3f".format(r.score)})")
                    println("      ${r.source} · ${r.chunkId.ifBlank { "—" }}")
                    if (r.excerpt.isNotBlank()) println("      “${r.excerpt}”")
                }
            } else {
                println("Источники:")
                ans.sources.forEachIndexed { i, h ->
                    println("  [${i + 1}] ${h.chunk.title} / ${h.chunk.section.ifBlank { "—" }} (${"%.3f".format(h.score)})")
                }
            }
            if (chatId != null && chats != null) {
                chats.appendMessage(chatId, RagMessage(role = "user", content = query))
                val refs = ans.refs.map { r ->
                    "[${r.index}] ${r.title}" + (if (r.section.isNotBlank()) " / ${r.section}" else "")
                }
                chats.appendMessage(chatId, RagMessage(role = "assistant", content = ans.text, sources = refs, info = ans.retrieval.summary(temperature), refs = ans.refs))
                chats.setMemory(chatId, svc.refreshMemory(memory ?: core.rag.TaskMemory(), query, ans.text))
                val mem = chats.getMemory(chatId)
                if (!mem.isEmpty()) println("[память: ${mem.facts.size} фактов${if (mem.goal.isNotBlank()) "; цель: ${mem.goal.take(80)}" else ""}]")
            }
        }
        // Task 5: мини-чат с RAG + памятью задачи. Команды: /memory, /forget, /exit.
        "chat" -> {
            val (_, rest) = splitPaths(args)
            val chatName = flag(rest, "--chat") ?: "cli-чат"
            val strategy = flag(rest, "--strategy") ?: "both"
            val topKInt = flag(rest, "--topK")?.toIntOrNull() ?: DEFAULT_RETRIEVE_K
            val filterFlag = flag(rest, "--filter")?.lowercase()
            val filterEnabled = filterFlag == null || (filterFlag != "off" && filterFlag != "0" && filterFlag != "false")
            val temperature = flag(rest, "--temperature")?.toFloatOrNull()
                ?: flag(rest, "--minScore")?.toFloatOrNull() ?: DEFAULT_MIN_SCORE
            val postKRaw = flag(rest, "--postK") ?: flag(rest, "--finalK")
            val postFilterK: Int? = when {
                postKRaw == null -> DEFAULT_FINAL_K
                postKRaw.equals("off", true) || postKRaw.equals("none", true) || postKRaw.isBlank() -> null
                else -> postKRaw.toIntOrNull()
            }
            val rewrite = flag(rest, "--rewrite") == "on"
            val chats = ChatStore()
            val chatId = findOrCreateChat(chats, chatName)
            println("Чат «$chatName». RAG + память задачи. Команды: /memory /forget /exit")
            while (true) {
                print("вы> ")
                val line = readlnOrNull()?.trim() ?: break
                if (line.isEmpty()) continue
                when (line.lowercase()) {
                    "/exit", "/quit", "/q" -> break
                    "/memory" -> {
                        val mem = chats.getMemory(chatId)
                        if (mem.isEmpty()) println("(память пуста)") else println(mem.promptBlock(maxFacts = 20, maxChars = 2000))
                        continue
                    }
                    "/forget" -> {
                        chats.clearMemory(chatId)
                        println("(память очищена)")
                        continue
                    }
                }
                val history = chats.state.messages[chatId].orEmpty()
                val memory = chats.getMemory(chatId)
                chats.appendMessage(chatId, RagMessage(role = "user", content = line))
                val ans = svc.ask(line, strategy, topKInt, onlySources = svc.activeSourcesOrNull(),
                    filterEnabled = filterEnabled, temperature = temperature,
                    postFilterK = postFilterK, rewrite = rewrite,
                    history = history, memory = memory)
                println("\n${ans.text}")
                println("[${ans.retrieval.summary(temperature)}]")
                if (ans.refs.isNotEmpty()) {
                    println("Источники:")
                    ans.refs.forEach { r ->
                        println("  [${r.index}] ${r.title} / ${r.section.ifBlank { "—" }} (${"%.3f".format(r.score)})")
                    }
                }
                val refs = ans.refs.map { r ->
                    "[${r.index}] ${r.title}" + (if (r.section.isNotBlank()) " / ${r.section}" else "")
                }
                chats.appendMessage(chatId, RagMessage(role = "assistant", content = ans.text, sources = refs, info = ans.retrieval.summary(temperature), refs = ans.refs))
                chats.setMemory(chatId, svc.refreshMemory(memory, line, ans.text))
            }
        }
        // Задание 3: сравнение режимов без переиндексации — одни и те же пробы
        // через без фильтра / с фильтром / фильтр+rewrite. Для rewrite нужен DEEPSEEK_API_KEY.
        "eval-modes" -> {
            val (_, rest) = splitPaths(args)
            val probes = flags(rest, "--probe").ifEmpty { DEFAULT_PROBES }
            val strategy = flag(rest, "--strategy") ?: "both"
            val topK = flag(rest, "--topK")?.toIntOrNull()
                ?: flag(rest, "--retrieveK")?.toIntOrNull() ?: DEFAULT_RETRIEVE_K
            val temperature = flag(rest, "--temperature")?.toFloatOrNull()
                ?: flag(rest, "--minScore")?.toFloatOrNull() ?: DEFAULT_MIN_SCORE
            val postKRaw = flag(rest, "--postK") ?: flag(rest, "--post-filter-k") ?: flag(rest, "--finalK")
            val postFilterK: Int? = when {
                postKRaw == null -> DEFAULT_FINAL_K
                postKRaw.equals("off", true) || postKRaw.equals("none", true) || postKRaw.isBlank() -> null
                else -> postKRaw.toIntOrNull()
            }
            val res = svc.compareModes(probes, strategy, topK, temperature, postFilterK, onLog = { println(it) })
            for (rep in res.reports) {
                println("\n### ${rep.modeLabel}: в среднем kept=${"%.1f".format(rep.avgKept)} dropped=${"%.1f".format(rep.avgDropped)}")
                rep.probes.forEach { (q, hits) ->
                    println("  Q: $q")
                    hits.forEach { println("    - $it") }
                }
            }
        }
        // Task 2: управление проектами на headless VPS (десктоп-UI там недоступен).
        //   project-add --name <имя> --input <git-URL|путь> [--strategy both]
        //   project-list | project-update --name <имя> [--strategy both]
        "project-add" -> {
            val (_, rest) = splitPaths(args)
            val name = flag(rest, "--name") ?: fail("нужно --name <имя>")
            val input = flag(rest, "--input") ?: fail("нужно --input <git-URL|путь>")
            val strat = flag(rest, "--strategy") ?: "both"
            try {
                svc.checkEmbeddings()
            } catch (e: Exception) {
                throw IllegalStateException("нет связи с эмбеддингами (Ollama): ${e.message}")
            }
            val n = svc.addProject(name, input, strat, onLog = { println(it) })
            println("OK project=$name files=$n")
        }
        "project-list" -> {
            svc.listProjects().forEach { println("${it.name} ${it.localPath} indexed=${it.indexedCommit}") }
            println("selected=${svc.selectedProject()?.name}")
        }
        "project-update" -> {
            val (_, rest) = splitPaths(args)
            val name = flag(rest, "--name") ?: fail("нужно --name <имя>")
            val strat = flag(rest, "--strategy") ?: "both"
            val n = svc.updateProject(name, strat, onLog = { println(it) })
            println("OK updated=$name files=$n")
        }
        // Task 2: pure-проверка парсинга diff/JSON без сети и БД (для CI без секретов).
        //   review --verify
        "verify-review" -> {
            val diff = """
                diff --git a/a.kt b/a.kt
                --- a/a.kt
                +++ b/a.kt
                @@ -1,3 +1,4 @@
                 line1
                -old
                +new1
                +new2
                 line3
                diff --git a/b.kt b/b.kt
                --- a/b.kt
                +++ b/b.kt
                @@ -10,2 +10,3 @@
                 ctx
                +added10
                \ No newline at end of file
            """.trimIndent()
            val added = core.project.GitTools.parseAddedLines(diff)
            check(added["a.kt"] == setOf(2, 3)) { "added a.kt: $added" }
            check(added["b.kt"] == setOf(11)) { "added b.kt: $added" }
            val byFile = core.project.GitTools.splitDiffByFile(diff)
            check(byFile.keys == setOf("a.kt", "b.kt")) { "split: ${byFile.keys}" }
            val (sum, findings, comments) = core.rag.parseReviewJson(
                """{"summary": "s", "findings": ["f1"], "comments": [
                |{"path": "a.kt", "line": 2, "severity": "bug", "body": "b1"},
                |{"path": "a.kt", "line": 99, "severity": "nit", "body": "drop-me"},
                |{"path": "b.kt", "line": 11, "severity": "arch", "body": "b2"}]}""".trimMargin()
            )
            check(sum == "s" && findings == listOf("f1")) { "parse: $sum $findings" }
            val valid = core.rag.validateComments(comments, added)
            check(valid.size == 2 && valid.none { it.line == 99 }) { "validate: $valid" }
            check(valid.map { it.severity }.toSet() == setOf("bug", "arch")) { "sev: $valid" }
            check(core.project.ProjectLoader.repoNameFromUrl("https://github.com/o/my-svc.git") == "my-svc") { "repourl" }
            check(core.project.ProjectLoader.repoNameFromUrl("https://github.com/o/my-svc") == "my-svc") { "repourl2" }
            println("VERIFY-REVIEW PASSED")
        }
        // Task 2: ревью PR. Ключ ТОЛЬКО из env (GH Secrets -> DEEPSEEK_API_KEY), на диск не пишем.
        //   review [--project name] [--base origin/main] [--head HEAD] [--maxFiles 15] [--format json|md] [--out review.json] [--dry-run]
        // dry-run — без LLM: только freshness + файлы diff (дымовой тест в CI без ключа).
        "review" -> {
            val (_, rest) = splitPaths(args)
            val project = flag(rest, "--project")
            val base = flag(rest, "--base") ?: "origin/main"
            val head = flag(rest, "--head") ?: "HEAD"
            val maxFiles = flag(rest, "--maxFiles")?.toIntOrNull()?.coerceIn(1, 50) ?: 15
            val format = flag(rest, "--format")?.lowercase() ?: "json"
            val out = flag(rest, "--out")
            val dryRun = rest.contains("--dry-run")
            if (dryRun) {
                val (entry, fresh) = svc.reviewFreshness(project)
                val dir = svc.projectDir(entry.name) ?: throw IllegalStateException("папка проекта пропала")
                val pr = core.project.GitTools.prDiff(dir, base, head)
                val added = core.project.GitTools.parseAddedLines(pr.diff)
                val json = reviewOutJson
                val payload = json.encodeToString(
                    ReviewResult.serializer(),
                    ReviewResult(
                        summary = "dry-run: файлов ${pr.files.size}, добавленных строк ${added.values.sumOf { it.size }}",
                        freshness = fresh, filesReviewed = pr.files.size, truncated = pr.truncated,
                    ),
                )
                if (out != null) java.io.File(out).writeText(payload)
                println(payload)
                return@runCommand
            }
            val apiKey = System.getenv("DEEPSEEK_API_KEY")?.trim()?.takeIf { it.isNotBlank() }
            val res = svc.review(project, base, head, apiKey = apiKey, maxFiles = maxFiles)
            if (format == "md") {
                val md = buildString {
                    appendLine(res.summary)
                    if (res.bugs.isNotEmpty()) { appendLine("\n## Баги"); res.bugs.forEach { appendLine("- $it") } }
                    if (res.arch.isNotEmpty()) { appendLine("\n## Архитектура"); res.arch.forEach { appendLine("- $it") } }
                    if (res.recommendations.isNotEmpty()) { appendLine("\n## Рекомендации"); res.recommendations.forEach { appendLine("- $it") } }
                    appendLine("\n_RAG: доков ${res.freshness.docsFound}, чанков ${res.freshness.chunks}, upToDate=${res.freshness.upToDate}${if (res.freshness.warning.isNotBlank()) " (${res.freshness.warning})" else ""}_")
                }.trim()
                if (out != null) java.io.File(out).writeText(md)
                println(md)
            } else {
                val payload = reviewOutJson.encodeToString(ReviewResult.serializer(), res)
                if (out != null) java.io.File(out).writeText(payload)
                println(payload)
            }
            // Машиночитаемый итог для Action: число inline-комментов в stderr-строке.
            System.err.println("REVIEW_COMMENTS=${res.comments.size} FILES=${res.filesReviewed}")
        }
        // ===== Task 3 (День 33): ассистент поддержки =====
        // Одно сообщение пользователя в диалоге по тикету. chatId == номер тикета.
        //   support-ask --chat 1042 --message "текст" [--project CallowFellow] [--server URL | --local] [--topK 8]
        // --server: по умолчанию берётся $VPS_URL, иначе работаем локально (тот же код).
        "support-ask" -> {
            val (_, rest) = splitPaths(args)
            val chatId = flag(rest, "--chat") ?: fail("нужен --chat <номер тикета>")
            val message = flags(rest, "--message").joinToString(" ").ifBlank { fail("нужен --message <текст обращения>") }
            val project = flag(rest, "--project") ?: supportProject()
            val topK = flag(rest, "--topK")?.toIntOrNull()?.coerceIn(1, 20) ?: 8
            val server = flag(rest, "--server") ?: vpsUrl()
            if (server != null) {
                val key = System.getenv("DEEPSEEK_API_KEY")?.trim()?.takeIf { it.isNotBlank() }
                val payload = buildJsonObject {
                    put("project", project)
                    put("chatId", chatId)
                    put("message", message)
                    put("topK", topK)
                }
                val body = postJson("$server/support/ask", payload.toString(), key = key)
                val reply = reviewOutJson.decodeFromString(SupportReply.serializer(), body)
                printReply(reply)
            } else {
                val crm = localCrmFor(project)
                val chats = ChatStore(ApiKeyProvider.appDir(), "support_chats.json")
                val history = chats.state.messages[chatId].orEmpty().takeLast(12)
                val key = System.getenv("DEEPSEEK_API_KEY")?.trim()?.takeIf { it.isNotBlank() }
                val reply = SupportService.ask(
                    rag = svc, project = project, chatId = chatId, message = message,
                    apiKey = key, history = history, crm = crm, topK = topK,
                    onLog = { m -> System.err.println("  · $m") },
                )
                chats.appendMessage(chatId, RagMessage("user", message))
                chats.appendMessage(chatId, RagMessage("assistant", reply.text, refs = reply.faqRefs))
                printReply(reply)
            }
        }
        // Контекст CRM: список тикетов / правка статуса. Правки живут на VPS и
        // сразу меняют ответы бота — на видео это «разработчик взял тикет в работу».
        //   support-ticket --list | --id 1042 [--status in-progress] [--assignee ilya] [--comment "..."] [--escalate "..."]
        "support-ticket" -> {
            val (_, rest) = splitPaths(args)
            val project = flag(rest, "--project") ?: supportProject()
            val id = flag(rest, "--id")
            val status = flag(rest, "--status")
            val assignee = flag(rest, "--assignee")
            val comment = flag(rest, "--comment")
            val escalate = flag(rest, "--escalate")
            // --list без значения: splitPaths отнёс бы его в позиционные, смотрим args.
            val list = args.contains("--list")
            status?.let { require(it in TICKET_STATUSES) { "статус должен быть одним из: ${TICKET_STATUSES.joinToString(", ")}" } }
            val server = flag(rest, "--server") ?: vpsUrl()
            if (list || (id == null && status == null)) {
                val tickets = if (server != null) {
                    val body = postJson(
                        "$server/crm/tickets",
                        buildJsonObject { put("project", project) }.toString(),
                    )
                    val st = reviewOutJson.decodeFromString(CrmState.serializer(), body)
                    println("CRM ${st.project}: импорт ${stamp(st.importedAt)}, открытых ${st.open}, эскалаций ${st.escalated}")
                    st.tickets
                } else {
                    localCrmFor(project).store(project).all()
                }
                if (tickets.isEmpty()) println("CRM пуста — запусти support-index или импортируй тикеты") else tickets.forEach { printTicketLine(it) }
            } else {
                requireNotNull(id) { "нужен --id <номер тикета>" }
                val ticket = if (server != null) {
                    val payload = buildJsonObject {
                        put("project", project)
                        put("ticketId", id)
                        put("status", status)
                        put("assignee", assignee)
                        put("comment", comment)
                        put("escalate", escalate)
                    }
                    reviewOutJson.decodeFromString(
                        SupportTicket.serializer(),
                        postJson("$server/crm/ticket", payload.toString()),
                    )
                } else {
                    val store = localCrmFor(project).store(project)
                    store.patch(id, status = status, assignee = assignee, comment = comment)
                    val esc = escalate?.takeIf { it.isNotBlank() }
                    (if (esc != null) store.escalate(id, esc) else store.get(id))
                        ?: throw IllegalStateException("тикет #$id не найден")
                }
                println("обновлён:")
                printTicketLine(ticket)
            }
        }
        // CRM как MCP-сервер: initialize + tools/list. Показывает, что поддержка
        // говорит с CRM по протоколу, а не прямым вызовом.
        //   mcp-tools [--server URL]
        "mcp-tools" -> {
            val (_, rest) = splitPaths(args)
            val server = flag(rest, "--server") ?: vpsUrl() ?: throw IllegalStateException("нужен --server <URL> или переменная VPS_URL")
            McpClient(server, vpsToken()).use { mcp ->
                val info = mcp.initialize()
                println("MCP сервер: ${info["name"]} ${info["version"]}")
                val tools = mcp.toolsList()
                println("тулов: ${tools.size}")
                tools.forEach { println("  · ${it.name} — ${it.description}") }
            }
        }
        // Прогон кейсов качества: сколько ответов подкреплены тикетом/FAQ и не содержат
        // запрещённого. Ничего не «подкручивает» — только меряет.
        //   support-eval --cases support/cases.json [--project P] [--server URL | --local]
        "support-eval" -> {
            val (_, rest) = splitPaths(args)
            val casesPath = flag(rest, "--cases") ?: fail("нужен --cases <файл>")
            val project = flag(rest, "--project") ?: supportProject()
            val server = flag(rest, "--server") ?: vpsUrl()
            val suite = reviewOutJson.decodeFromString(SupportSuite.serializer(), java.io.File(casesPath).readText())
            val cases = suite.cases.map { if (it.project.isBlank()) it.copy(project = project) else it }
            var passed = 0
            cases.forEachIndexed { i, c ->
                val reply = askSupport(svc, c, server)
                val reasons = checkCase(c, reply)
                val ok = reasons.isEmpty()
                if (ok) passed++
                println("#${i + 1} ${if (ok) "OK  " else "FAIL"} [${c.chatId}] ${c.message.take(70)}")
                if (!ok) reasons.forEach { println("       ✗ $it") }
                println("       ${reply.text.replace('\n', ' ').take(160)}")
            }
            println("SUPPORT-EVAL $passed/${cases.size}")
        }
        // Чистый оффлайн-тест слоя поддержки: фронтматтер, маппинг тикета, MCP-эндпоинты,
        // промпт и CrmStore. Без сети и без БД — как verify-review для Task 2.
        "verify-support" -> {
            // 1) Фронтматтер из тела Issue.
            val body = "---\nticket:\n  user:\n    id: u17\n    name: Ирина\n    plan: pro\n    device: Pixel 8\n  status: in-progress\n  assignee: \"@ilya\"\n  workaround: положить .litertlm в models/llm/\n---\n\nМодель падает на 80%"
            val (fm, rest) = core.support.FrontMatter.split(body)
            val map = core.support.FrontMatter.parseMap(fm)
            check(rest.trim() == "Модель падает на 80%") { "тело тикета не отделено: $rest" }
            check(map["ticket.user.id"] == "u17") { "вложенный ключ: $map" }
            check(map["ticket.workaround"]?.startsWith("положить") == true) { "значение с двоеточием: $map" }

            // 2) Issue -> тикет.
            val issue = RawIssue(
                number = 1042, title = "Модель не скачивается",
                body = body, state = "open",
                labels = listOf("support", "type:bug", "severity:major", "status:in-progress", "component:models"),
                url = "https://github.com/o/r/issues/1042",
            )
            val t = core.support.TicketMapper.toTicket(issue)
            check(t.id == "1042" && t.status == "in-progress") { "статус тикета: ${t.status}" }
            check(t.assignee == "ilya") { "assignee без @: ${t.assignee}" }
            check(t.component == "models" && t.severity == "major") { "лейблы: ${t.component}/${t.severity}" }
            check(t.user.id == "u17" && t.user.plan == "pro") { "профиль юзера: ${t.user}" }
            check(t.workaround.isNotBlank()) { "нет обходного пути" }
            check(t.body == "Модель падает на 80%") { "в тело тикета попал фронтматтер" }
            val closed = core.support.TicketMapper.toTicket(issue.copy(state = "closed", labels = listOf("status:resolved")))
            check(closed.status == "resolved") { "лейбл важнее state: ${closed.status}" }

            // 3) Блок тикета: шапка, статус, отсутствие выдуманного срока.
            val sum = core.support.ticketSummary(t, full = true)
            check(sum.startsWith("Тикет #1042")) { "шапка блока: ${sum.take(40)}" }
            check(SupportService.ticketStatusOf(sum) == "in-progress") { "статус из блока" }
            check(sum.contains("Ориентир по сроку: не задан")) { "срок не выдуман, но и не назван" }
            check(SupportService.ticketIdOf(sum) == "1042") { "номер из блока" }

            // 4) Источники тикетов в RAG изолированы от доков.
            check(core.support.ticketPrefix("CallowFellow") == "proj:CallowFellow:support/tickets/") { "префикс" }
            check(core.support.ticketSource("CallowFellow", "7") == "proj:CallowFellow:support/tickets/7.md") { "source тикета" }

            // 5) MCP: JSON-RPC без сети — сам сервер и реестр тулов.
            val dir = java.nio.file.Files.createTempDirectory("crm-verify").toFile()
            val crm = CrmTools("CallowFellow", dir)
            val server = McpServerProbe(crm)
            val init = server.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
            val proto = core.support.MCP_PROTOCOL_VERSION
            check(init.contains(proto) && init.contains("callowfellow-support-crm")) { "initialize: $init" }
            val list = server.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            listOf("get_ticket", "list_open_tickets", "search_tickets", "get_user_tickets").forEach {
                check(list.contains("\"$it\"")) { "тул $it не в tools/list" }
            }
            check(server.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_ticket","arguments":{"ticketId":"404"}}}""")
                .contains("не найден")) { "тул на несуществующий тикет должен честно сказать об этом" }
            val schemas = server.handle("""{"jsonrpc":"2.0","id":4,"method":"tools/list"}""")
            check(schemas.contains("ticketId") && schemas.contains("inputSchema") && schemas.contains("required")) { "схема тула в tools/list" }
            check(server.handle("""{"jsonrpc":"2.0","id":5,"method":"нет-такого"}""").contains("-32601")) { "неизвестный метод" }
            check(server.handle("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"нет-тула"}}""").contains("нет тула")) { "неизвестный тул" }
            check(server.handle("""{"jsonrpc":"2.0","id":7,"method":"tools/list"}""").isNotEmpty()) { "tools/list без id" }

            // 6) CrmStore: импорт -> правка -> повторный импорт не затирает правки.
            val store = crm.store("CallowFellow")
            store.import(listOf(issue, issue.copy(number = 1043, title = "Нужен ключ DeepSeek",
                body = "---\nticket:\n  type: question\n---\nГде ключ?", labels = listOf("support"))))
            check(store.all().size == 2) { "импортировано ${store.all().size}" }
            check(store.get("1043")?.type == "question") { "тип из фронтматтера" }
            check(store.open().size == 2) { "оба тикета открыты" }
            store.patch("1043", status = "resolved", comment = "починили в 0.1.1")
            check(store.get("1043")?.status == "resolved") { "локальный статус" }
            store.escalate("1043", "нет ответа разработчика")
            check(store.get("1043")?.escalated == true) { "эскалация" }
            store.import(listOf(issue, issue.copy(number = 1043, title = "Нужен ключ DeepSeek",
                body = "---\nticket:\n  type: question\n---\nГде ключ?", labels = listOf("status:in-progress"))))
            check(store.get("1043")?.status == "resolved") { "ночной импорт затёр локальную правку" }
            check(store.get("1043")?.notes?.any { it.contains("починили") } == true) { "комментарий потерян" }
            check(store.open().size == 1) { "после resolved открытых должен остаться один" }

            // 7) Промпт: тикет авторитетнее FAQ, сроки запрещены.
            val sys = core.rag.buildSupportSystem("CallowFellow", sum, 2, "")
            check(sys.contains("ДАННЫЕ ТИКЕТА ИЗ CRM")) { "блок тикета не попал в системный промпт" }
            check(sys.contains("ориентира по срокам пока нет")) { "правило про отсутствие срока" }
            check(sys.contains("первой фразой") || sys.contains("Первой фразой")) { "правило реакции на пользователя" }
            val user = core.rag.buildSupportUser(sum, "[1] FAQ: обходной путь", "", "модель падает")
            check(user.contains("ДАННЫЕ ТИКЕТА") && user.contains("FAQ:")) { "блоки в user-промпте" }
            // Длинная история не должна выдавливать FAQ: раньше общий лимит резал
            // промпт с конца, и модель отвечала, не увидев цитат.
            val longHistory = (1..40).joinToString("\n") { "реплика $it " + "х".repeat(200) }
            val big = core.rag.buildSupportUser(sum, "[1] FAQ: важная цитата", longHistory, "вопрос")
            check(big.contains("FAQ:") && big.contains("важная цитата")) { "FAQ вытеснен историей" }
            check(big.length <= core.rag.PROMPT_MAX_CHARS) { "промпт длиннее лимита: ${big.length}" }
            check(big.indexOf("ДАННЫЕ ТИКЕТА") < big.indexOf("ФРАГМЕНТЫ FAQ")) { "тикет должен идти перед FAQ" }

            println("VERIFY-SUPPORT PASSED")
        }
        // Скрытый дымовой тест новых путей: RAG вкл/выкл, фильтр документов. He для видео.
        "selftest" -> {
            val docs = svc.indexedDocs()
            println("docs=${docs.size}")
            check(docs.isNotEmpty()) { "пустая база — сначала index" }
            val first = docs.first()
            println("first=${first.source} chunks=${first.chunks} active=${first.active}")
            // 1. фильтр: поиск только по первому документу
            val hits = svc.search("искусственный интеллект", "structure", 5, setOf(first.source))
            check(hits.isNotEmpty()) { "поиск по документу пуст" }
            check(hits.all { it.chunk.source == first.source }) { "фильтр пропустил чужой source" }
            println("filter-ok hits=${hits.size}")
            // 2. выключение документа: поиск с учётом фильтра пуст
            svc.setDocActive(first.source, false)
            val filtered = svc.activeSourcesOrNull()
            val hits2 = svc.search("искусственный интеллект", "structure", 5, filtered)
            check(hits2.none { it.chunk.source == first.source }) { "выключенный док попал в выдачу" }
            println("toggle-off-ok")
            svc.setDocActive(first.source, true)
            // 3. RAG выключен: прямой ответ LLM без поиска
            val plain = svc.askPlain("Ответь одним словом: какой сегодня день недели?", emptyList())
            check(plain.isNotBlank()) { "askPlain пуст" }
            println("plain-ok: ${plain.take(80)}")
            println("SELFTEST PASSED")
        }
        else -> usage()
    }
}

// ===== Task 3: кейсы качества и помощники CLI поддержки =====

@Serializable
private data class SupportCase(
    val chatId: String = "",
    val message: String = "",
    val project: String = "",
    // Должен ли был сработать тикет (null = не проверяем).
    val expectTicket: Boolean? = null,
    val expectStatus: String = "",
    // Хотя бы одна из фраз должна прозвучать в ответе (без учёта регистра).
    val expectAny: List<String> = emptyList(),
    // Фразы, которых в ответе быть не должно (обещания сроков, выдумки).
    val forbidAny: List<String> = emptyList(),
)

@Serializable
private data class SupportSuite(
    val project: String = "",
    val cases: List<SupportCase> = emptyList(),
)

// Общий HTTP-клиент CLI к VPS: тот же Bearer, что у сервера, ключ LLM — заголовком.
private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

private fun vpsUrl() = System.getenv("VPS_URL")?.trim()?.takeIf { it.isNotBlank() }

private fun vpsToken() = System.getenv("VPS_TOKEN")?.trim().orEmpty()

private fun llmKey() = System.getenv("DEEPSEEK_API_KEY")?.trim()?.takeIf { it.isNotBlank() }

private fun supportProject() =
    System.getenv("SUPPORT_PROJECT")?.trim()?.takeIf { it.isNotBlank() } ?: "CallowFellow"

private fun postJson(url: String, payload: String, key: String? = llmKey()): String {
    val res = http.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${vpsToken()}")
            .apply { if (key != null) header("X-LLM-Key", key) }
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    if (res.statusCode() !in 200..299) {
        throw IllegalStateException("VPS HTTP ${res.statusCode()}: ${res.body().take(300)}")
    }
    return res.body()
}

private fun stamp(ms: Long) = if (ms <= 0) "нет" else java.time.Instant.ofEpochMilli(ms).toString()

private fun localCrmFor(project: String) =
    CrmTools(project) { p, q, l -> SupportService.searchTicketRefs(localSvc(), p, q, l) }

// RagService для локальных хелперов: создаётся один раз на процесс.
private var localSvcInstance: RagService? = null

private fun localSvc(): RagService =
    localSvcInstance ?: RagService().also { localSvcInstance = it }

private suspend fun askSupport(svc: RagService, c: SupportCase, server: String?): SupportReply =
    if (server != null) {
        val payload = buildJsonObject {
            put("project", c.project)
            put("chatId", c.chatId)
            put("message", c.message)
        }
        reviewOutJson.decodeFromString(SupportReply.serializer(), postJson("$server/support/ask", payload.toString()))
    } else {
        SupportService.ask(
            rag = svc, project = c.project, chatId = c.chatId, message = c.message,
            apiKey = llmKey(), crm = localCrmFor(c.project),
        )
    }

// Проверка кейса: тикет/статус + обязательные фразы − запрещённые.
private fun checkCase(c: SupportCase, r: SupportReply): List<String> {
    val bad = mutableListOf<String>()
    val text = r.text.lowercase()
    if (c.expectTicket != null && r.usedTicket != c.expectTicket) {
        bad += "тикет usedTicket=${r.usedTicket}, ждали ${c.expectTicket}"
    }
    if (c.expectStatus.isNotBlank() && r.ticketStatus != c.expectStatus) {
        bad += "статус тикета «${r.ticketStatus}» != «${c.expectStatus}»"
    }
    if (c.expectAny.isNotEmpty() && c.expectAny.none { it.lowercase() in text }) {
        bad += "в ответе нет ни одной из фраз: ${c.expectAny.joinToString(" / ")}"
    }
    c.forbidAny.forEach { if (it.lowercase() in text) bad += "запрещённая фраза в ответе: $it" }
    return bad
}

private fun printReply(r: SupportReply) {
    println(r.text)
    println()
    println("— чат #${r.chatId} · " + (if (r.usedTicket) "тикет #${r.ticketId} (${r.ticketStatus})" else "тикет не найден, только FAQ"))
    if (r.faqRefs.isNotEmpty()) {
        println("— источники:")
        r.faqRefs.forEach { println("  [${it.index}] ${it.title}${if (it.section.isNotBlank()) " / ${it.section}" else ""} · ${"%.2f".format(it.score)}") }
    } else {
        println("— источников FAQ нет")
    }
    if (r.refused) println("— честный отказ: в базе ничего не нашлось, LLM не звали")
    if (r.escalated) println("— эскалировано: ${r.escalateReason}")
    if (r.historyUsed > 0) println("— истории диалога в промпте: ${r.historyUsed} реплик")
    if (r.warning.isNotBlank()) println("— предупреждение: ${r.warning}")
}

private fun printTicketLine(t: SupportTicket) {
    println("#${t.id} [${core.support.ticketStatusName(t.status)}] ${t.title}")
    val bits = buildList {
        add("${t.type}/${t.severity}")
        if (t.component.isNotBlank()) add(t.component)
        if (t.assignee.isNotBlank()) add(t.assignee)
        if (t.eta.isNotBlank()) add("срок: ${t.eta}")
        if (t.workaround.isNotBlank()) add("обходной путь: ${t.workaround.take(70)}")
        if (t.fix.isNotBlank()) add("фикс: ${t.fix.take(70)}")
    }
    println("   ${bits.joinToString(" · ")}")
    println("   юзер: ${t.user.name} (${t.user.id}, план ${t.user.plan}, ${t.user.device} ${t.user.os}, ${t.user.app})")
    if (t.escalated) println("   ЭСКАЛАЦИЯ: ${t.escalatedReason}")
    t.notes.forEach { println("   комментарий: $it") }
}

// Проба MCP-слоя без сети: handle() — чистая функция над JSON-строкой.
private class McpServerProbe(crm: CrmTools) {
    private val server = core.support.McpServer(crm.registry())
    suspend fun handle(body: String): String = server.handle(body)
}

private fun fail(msg: String): Nothing {
    usage()
    throw IllegalArgumentException(msg)
}

private fun usage() {
    println("week4 wiki <название статьи|URL> [...] [--strategy both]")
    println("week4 search <запрос...> [--strategy structure] [--topK 5] [--minScore 0.35]")
    println("  --minScore: пометить кандидатов ниже порога как отсечённые")
    println("week4 ask <вопрос...> [--strategy structure] [--topK 20] [--filter on|off] [--temperature 0.35] [--postK 5|off] [--rewrite on|off] [--chat <название>]")
    println("  --topK: сколько чанков забрать из индекса (до фильтрации)")
    println("  --filter: вкл/выкл фильтрации (выкл = как было в Task 2)")
    println("  --temperature: точность-порог 0-1; пусто после фильтра = честный отказ (алиас --minScore)")
    println("  --postK: top-K после фильтрации 1..topK (off = без ограничения, идут все прошедшие порог)")
    println("  --rewrite on: переписать вопрос через DeepSeek и искать по всем вариантам")
    println("  --chat: вести вопрос в контексте чата (история + память задачи, Task 5)")
    println("week4 chat [--chat <название>] [--strategy both] [--topK 20] [--filter on|off] [--temperature 0.35] [--postK 5|off] [--rewrite on|off]")
    println("  мини-чат с RAG + памятью задачи; команды: /memory /forget /exit")
    println("week4 eval-modes [--probe \"вопрос...\" ...] [--strategy both] [--topK 20] [--temperature 0.35] [--postK 5|off]")
    println("  сравнение режимов без фильтра / с фильтром / фильтр+rewrite на одних пробах (без переиндексации)")
    println("week6 review [--project <имя>] [--base origin/main] [--head HEAD] [--maxFiles 15] [--format json|md] [--out review.json] [--dry-run]")
    println("  Task 2: RAG-freshness + PR-diff -> JSON {summary, comments[{path,line,severity,body}]} для inline-ревью.")
    println("  Ключ только из env DEEPSEEK_API_KEY (GH Secrets). --dry-run: без LLM, только freshness+diff.")
    println("week6 verify-review")
    println("  Task 2: pure-проверка парсинга diff/JSON без сети и БД (для CI без секретов).")
    println("week6 project-add --name <имя> --input <git-URL|путь> [--strategy both]")
    println("  Task 2: добавить проект в RAG (для headless VPS; git-URL клонируется в repos/<имя>).")
    println("week6 project-list | project-update --name <имя>")
    println("  Task 2: список проектов / обновить индекс (git pull + доиндексация).")
    println()
    println("week6 support-ask --chat <тикет> --message <текст> [--project <имя>] [--server <URL>] [--topK 8]")
    println("  Task 3: ответ ассистента поддержки. --server или переменная VPS_URL — по сети на VPS, иначе локально.")
    println("week6 support-ticket --list | --id <тикет> [--status open|in-progress|needs-info|resolved|closed]")
    println("      [--assignee <ник>] [--comment <текст>] [--escalate <причина>]")
    println("  Task 3: контекст CRM и правки тикета (правка сразу меняет ответы бота).")
    println("week6 mcp-tools [--server <URL>]")
    println("  Task 3: tools/list CRM-сервера по MCP.")
    println("week6 support-eval --cases <файл.json> [--project <имя>] [--server <URL>]")
    println("  Task 3: прогон кейсов качества (тикет сработал / фразы есть / запрещённого нет).")
    println("week6 verify-support")
    println("  Task 3: оффлайн-проверка слоя поддержки (фронтматтер, MCP, промпт, CRM).")
}

// Task 5: чат по имени для CLI — тот же ChatStore, что у desktop.
private fun findOrCreateChat(chats: ChatStore, name: String): String {
    chats.state.chats.firstOrNull { it.name == name }?.let { return it.id }
    val chat = RagChat(id = newChatId(), name = name)
    chats.upsertChat(chat)
    return chat.id
}

private fun flag(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

// Повторяемый флаг: --probe "q1" --probe "q2" -> [q1, q2].
private fun flags(args: Array<String>, name: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        if (args[i] == name && i + 1 < args.size) {
            out += args[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return out
}

// Позиционные аргументы (пути/названия статей) отдельно от --флагов.
private fun splitPaths(args: Array<String>): Pair<List<String>, Array<String>> {
    val pos = mutableListOf<String>()
    val rest = mutableListOf<String>()
    var i = 1
    while (i < args.size) {
        if (args[i].startsWith("--") && i + 1 < args.size) {
            rest += args[i]
            rest += args[i + 1]
            i += 2
        } else {
            pos += args[i]
            i++
        }
    }
    return pos to rest.toTypedArray()
}

// Отделяем текст запроса (позиционные слова) от --флагов.
private fun splitQuery(args: Array<String>): Pair<String, Array<String>> {
    val (pos, rest) = splitPaths(args)
    if (pos.isEmpty()) fail("нужен текст запроса")
    return pos.joinToString(" ") to rest
}
