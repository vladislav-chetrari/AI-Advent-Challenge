package cli

import core.llm.ApiKeyProvider
import core.llm.intOf
import core.llm.missingRequired
import core.llm.strOf
import core.rag.ChatStore
import core.rag.DEFAULT_FINAL_K
import core.rag.DEFAULT_MIN_SCORE
import core.rag.DEFAULT_PROBES
import core.rag.DEFAULT_RETRIEVE_K
import core.rag.RagChat
import core.rag.RagMessage
import core.rag.RagService
import core.rag.agentChats
import core.rag.ragChats
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
            check(core.project.ProjectLoader.repoNameFromUrl("https://github.com/o/my-svc") == "my-svc") { "rejourl2" }
            // Имя проекта приходит из HTTP-запроса и подставляется в путь repos/<имя>:
            // любой разделитель пути обязан отсекаться, иначе это выход за пределы каталога.
            val okNames = listOf("my-svc", "CallowFellow", "a.b", "a_b", "A1", "x")
            okNames.forEach {
                check(core.project.ProjectLoader.sanitizeName(it) == it) { "должно приниматься: $it" }
            }
            val badNames = listOf("a/b", "..", "../etc", "a\\b", "_x", "-x", "", "a b", "x/y", "a:b")
            badNames.forEach {
                val accepted = try {
                    core.project.ProjectLoader.sanitizeName(it); true
                } catch (_: Exception) { false }
                check(!accepted) { "НЕЛЬЗЯ принимать имя проекта: '$it'" }
            }
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
                    put("project", project.trim())
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
                    sessionTicketId = localSessions.ticketOf(chatId),
                    onTicketFound = { id -> localSessions.remember(chatId, id) },
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
            val allProjects = args.contains("--projects")
            status?.let { require(it in TICKET_STATUSES) { "статус должен быть одним из: ${TICKET_STATUSES.joinToString(", ")}" } }
            val server = flag(rest, "--server") ?: vpsUrl()
            if (allProjects) {
                val server0 = flag(rest, "--server") ?: vpsUrl()
                    ?: throw IllegalStateException("нужен --server <URL> или переменная VPS_URL")
                val st = reviewOutJson.decodeFromString(
                    CrmState.serializer(),
                    postJson("$server0/crm/tickets", buildJsonObject { put("project", "*") }.toString()),
                )
                println("CRM на VPS:")
                st.projects.forEach { p ->
                    println("  ${p.project}: тикетов ${p.total}, открытых ${p.open}, импорт ${stamp(p.importedAt)}")
                }
                if (st.projects.isEmpty()) println("  (пусто — CRM ещё ни разу не заполнялась)")
                return@runCommand
            }
            if (list || (id == null && status == null)) {
                val tickets = if (server != null) {
                    val body = postJson(
                        "$server/crm/tickets",
                        buildJsonObject { put("project", project) }.toString(),
                    )
                    val st = reviewOutJson.decodeFromString(CrmState.serializer(), body)
                    if (st.project == "*") {
                        println("CRM на VPS:")
                        st.projects.forEach { p ->
                            println("  ${p.project}: тикетов ${p.total}, открытых ${p.open}, импорт ${stamp(p.importedAt)}")
                        }
                        return@runCommand
                    }
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
        // ===== Task 4: агент, который сам работает с файлами проекта =====
        //   agent-run --goal "цель" [--project P] [--chat <имя>] [--auto-approve]
        //            [--max-steps N] [--max-files N] [--out run.json] [--format json|md]
        "agent-run" -> {
            val (_, rest) = splitPaths(args)
            val goal = flag(rest, "--goal")?.takeIf { it.isNotBlank() } ?: fail("нужен --goal \"<цель>\"")
            val projectName = flag(rest, "--project") ?: svc.selectedProject()?.name
                ?: fail("проект не выбран: добавь через project-add или укажи --project")
            val dir = svc.projectDir(projectName) ?: fail("папка проекта пропала: $projectName")
            val auto = rest.contains("--auto-approve")
            val cfg = core.agent.AgentConfig(
                maxSteps = flag(rest, "--max-steps")?.toIntOrNull() ?: core.agent.DEFAULT_MAX_STEPS,
                maxFilesChanged = flag(rest, "--max-files")?.toIntOrNull() ?: core.agent.DEFAULT_MAX_FILES_CHANGED,
            )
            val svc4 = svc
            val store = core.agent.RunStore()
            val ws = core.agent.Workspace(dir)
            // --auto-approve -> спрашивать некому (CI/headless). Без флага —
            // диалог на stdin, ровно как окно подтверждения в desktop.
            val gate = core.agent.PermissionGate(
                core.agent.PermissionStore(),
                if (auto) null else { change -> askChange(change) },
            )
            println("Проект: $projectName · ${ws.listFiles(limit = 5000).size} файлов")
            println("Цель: $goal")
            // --chat <имя> — привязать прогон к агентному чату: та же история
            // прогонов и дифов, что и в desktop, но без окна.
            val chatName = flag(rest, "--chat")
            val chatId = chatName?.let { findOrCreateAgentChat(ChatStore(), it) }.orEmpty()
            if (chatName != null) println("Агентный чат: $chatName")
            if (auto) println("Режим --auto-approve: изменения пишутся без подтверждения.")
            println("—".repeat(60))
            val runner = core.agent.AgentRunner(
                rag = svc4,
                workspace = ws,
                runStore = store,
                gate = gate,
                config = cfg,
                onUpdate = { r ->
                    if (r.state is core.agent.RunState.AwaitingApproval) {
                        println("   ⏸ ${r.label()}")
                    }
                },
                onLog = { println("   · $it") },
            )
            val run = runner.run(goal, projectName, chatId = chatId)
            println("—".repeat(60))
            printRun(run)
            val out = flag(rest, "--out")
            if (out != null) {
                java.io.File(out).writeText(reviewOutJson.encodeToString(core.agent.AgentRun.serializer(), run))
                println("\nСохранено: $out")
            }
            if (run.state is core.agent.RunState.Failed || run.state is core.agent.RunState.Cancelled) {
                println("\nПрогон не завершён успешно — см. run: ${run.id}")
            }
        }
        // Схемы тулов агента: то же, что видит модель (tools) и MCP-клиент
        // (tools/list). Доказательство, что слой настоящий, а не имитация.
        "agent-tools" -> {
            val projectName = flag(args, "--project") ?: svc.selectedProject()?.name ?: "demo"
            val dir = svc.projectDir(projectName) ?: java.io.File(".").canonicalFile
            val tools = core.agent.FileTools(core.agent.Workspace(dir))
            val specs = tools.specs()
            println("Тулов агента: ${specs.size}")
            specs.forEach { s ->
                println("  · ${s.name} — ${s.description}")
                val props = s.parameters["properties"] as? kotlinx.serialization.json.JsonObject
                if (props != null) {
                    val req = s.required
                    props.forEach { (k, v) ->
                        val desc = (v as? kotlinx.serialization.json.JsonObject)?.get("description")
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                        println("      $k${if (k in req) "*" else ""} — ${desc.take(90)}")
                    }
                }
            }
            println("\nМутирующие (всегда через подтверждение): " +
                tools.definitions().filter { it.mutating }.joinToString(", ") { it.name })
        }
        // Прогоны на диске: ~/.ai-advent-week6/agent_runs.json
        "agent-runs" -> {
            val store = core.agent.RunStore()
            if (store.runs.isEmpty()) {
                println("Прогонов пока нет.")
            } else {
                store.runs.sortedBy { it.startedAt }.forEach { r ->
                    val chat = r.chatId.takeIf { it.isNotBlank() }
                    println("${r.id}  ${r.label()}  файлов=${r.changedFiles.size}${chat?.let { "  чат=$it" } ?: ""}  ${r.goal.take(60)}")
                }
            }
        }
        // Diff прогона — то, что по заданию должно быть «изменениями или diff».
        "agent-diff" -> {
            val id = flag(args, "--run") ?: core.agent.RunStore().latest()?.id
                ?: fail("нет прогонов — сначала agent-run")
            val r = core.agent.RunStore().get(id) ?: fail("прогон $id не найден")
            printRun(r)
        }
        // Откат к состоянию до прогона: файлы восстанавливаются из снапшотов,
        // созданных до первой записи.
        "agent-revert" -> {
            val id = flag(args, "--run") ?: fail("нужен --run <id>")
            val store = core.agent.RunStore()
            val r = store.get(id) ?: fail("прогон $id не найден")
            val projectName = r.project
            val dir = svc.projectDir(projectName) ?: fail("папка проекта пропала: $projectName")
            val n = store.revert(r, core.agent.Workspace(dir))
            println("Откачено файлов: $n (прогон $id)")
        }
        // Чистый оффлайн-тест слоя агента: схемы тулов, редьюсер, клетка путей,
        // unified diff, MCP-эндпоинт. Без сети, без БД и без LLM.
        "verify-agent" -> verifyAgent()
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

// Тикет, обсуждаемый в диалоге: продолжение разговора не теряет контекст.
private val localSessions = core.support.SupportSessions()

private fun localSvc(): RagService =
    localSvcInstance ?: RagService().also { localSvcInstance = it }

private suspend fun askSupport(svc: RagService, c: SupportCase, server: String?): SupportReply =
    if (server != null) {
        val payload = buildJsonObject {
            put("project", c.project.trim())
            put("chatId", c.chatId)
            put("message", c.message)
        }
        reviewOutJson.decodeFromString(SupportReply.serializer(), postJson("$server/support/ask", payload.toString()))
    } else {
        SupportService.ask(
            rag = svc, project = c.project, chatId = c.chatId, message = c.message,
            apiKey = llmKey(), crm = localCrmFor(c.project),
            sessionTicketId = localSessions.ticketOf(c.chatId),
            onTicketFound = { id -> localSessions.remember(c.chatId, id) },
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

// ===== Task 4: помощники CLI агента =====

// Подтверждение правки в терминале. Диф печатается ДО вопроса —
// пользователь решает по сути изменения, а не по описанию.
// y — один раз, a — всегда для этого файла, n/прочее — отклонить.
private fun askChange(change: core.agent.PendingChange): core.agent.PermissionReply {
    println()
    println("─".repeat(60))
    println("АГЕНТ ХОЧЕТ ИЗМЕНИТЬ ФАЙЛ")
    println("  ${change.path}  (${change.action}${if (change.replacements > 1) ", замен: ${change.replacements}" else ""})")
    if (change.summary.isNotBlank()) println("  ${change.summary}")
    println()
    if (change.diff.isBlank()) println("  (содержимое станет пустым)")
    else println(change.diff.take(DIFF_SHOWN))
    if (change.diff.length > DIFF_SHOWN) println("  … диф обрезан")
    print("Разрешить? [y] один раз  [a] всегда для этого файла  [n] отклонить > ")
    val answer = (readlnOrNull() ?: "").trim().lowercase()
    // Ответ терминала печатается на той же строке, что и приглашение — без
    // перевода строки журнал прогона приклеивается к нему.
    println()
    return when {
        answer == "a" || answer == "в" -> core.agent.PermissionReply.Always
        answer == "y" || answer == "д" || answer.isEmpty() -> core.agent.PermissionReply.Once
        else -> core.agent.PermissionReply.Reject
    }
}

private const val DIFF_SHOWN = 4000

// Итог прогона: цель, состояние, что менял и diff. Читается человеком и
// попадает в --out для последующего разбора.
private fun printRun(r: core.agent.AgentRun) {
    println("Прогон: ${r.id}")
    println("Состояние: ${r.label()}  ·  шагов: ${r.steps}  ·  вызовов: ${r.invocations.size}")
    if (r.summary.isNotBlank()) println("\nИтог:\n${r.summary}")
    if (r.error.isNotBlank() && r.state !is core.agent.RunState.Failed) println("Замечание: ${r.error}")
    println()
    if (r.invocations.isEmpty()) {
        println("Тулы не вызывались.")
    } else {
        println("Шаги агента:")
        r.invocations.forEach { inv ->
            val icon = when (val st = inv.state) {
                is core.agent.ToolState.Done -> "✅"
                is core.agent.ToolState.Failed -> "❌ ${st.error.take(80)}"
                is core.agent.ToolState.Running -> "🔧"
                core.agent.ToolState.Pending -> "⏳"
            }
            val arg = inv.args.take(90).replace('\n', ' ')
            println("  $icon ${inv.tool}($arg)")
        }
    }
    // Показываем ВСЕГДА, включая ноль: иначе модель может написать «добавил
    // раздел в README», ничего не изменив, и это выглядит как успех.
    println()
    if (r.changedFiles.isEmpty()) {
        println("Изменено файлов: 0 (ничего на диске не поменялось)")
    } else {
        println("Изменено файлов: ${r.changedFiles.size} — ${r.changedFiles.joinToString(", ")}")
    }
    if (r.diff.isNotBlank()) {
        println()
        println("Diff:")
        println(r.diff.take(DIFF_SHOWN))
        if (r.diff.length > DIFF_SHOWN) println("… диф обрезан (полный — в agent_runs.json)")
    }
}

// Оффлайн-проверка слоя агента. Ни сети, ни БД, ни LLM: только чистые
// функции и временный каталог. Такой же приём, как verify-review/verify-support.
private fun verifyAgent() {
    val tmp = java.nio.file.Files.createTempDirectory("week6-agent-verify").toFile()
    try {
        tmp.resolve("src").mkdirs()
        tmp.resolve("src/App.kt").writeText("fun main() {\n    println(\"привет\")\n}\n", Charsets.UTF_8)
        tmp.resolve("docs").mkdirs()
        tmp.resolve("docs/README.md").writeText("# Проект\n\nСтарая строка документации.\n", Charsets.UTF_8)
        tmp.resolve(".git").mkdirs()
        tmp.resolve(".git/config").writeText("secret\n", Charsets.UTF_8)

        val ws = core.agent.Workspace(tmp)
        val tools = core.agent.FileTools(ws)

        // --- 1. Схемы тулов ---
        val specs = tools.specs()
        check(specs.size >= 8) { "мало тулов: ${specs.size}" }
        check(specs.map { it.name }.distinct().size == specs.size) { "дублируются имена тулов" }
        specs.forEach { s ->
            check(s.name.matches(Regex("[a-z][a-z0-9_]*"))) { "плохое имя тула: ${s.name}" }
            check(s.description.isNotBlank()) { "тул без описания: ${s.name}" }
            check((s.parameters["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "object") {
                "схема не object: ${s.name}"
            }
        }
        check(specs.any { it.name == "read_file" } && specs.any { it.name == "write_file" }) { "нет базовых тулов" }
        // Запись обязана быть помечена мутирующей — от этого зависит гейт.
        val mutating = tools.definitions().filter { it.mutating }.map { it.name }.toSet()
        check(mutating.containsAll(setOf("write_file", "edit_file"))) { "пишущие тулы не помечены: $mutating" }
        check("read_file" !in mutating && "grep" !in mutating) { "читающий тул помечен мутирующим" }
        println("OK схемы тулов: ${specs.size} (мутирующих: ${mutating.size})")

        // --- 2. Разбор аргументов: битый JSON не должен ронять цикл ---
        check(core.llm.parseToolArgs("{\"path\":\"a.kt\"}") != null) { "нормальный JSON не разобран" }
        check(core.llm.parseToolArgs("{путь a.kt}") == null) { "битый JSON принят как валидный" }
        check(core.llm.parseToolArgs("")!!.isEmpty()) { "пустые аргументы должны быть пустым объектом" }
        val cleaned = core.llm.parseToolArgs("вот json: {\"path\":\"a.kt\"} — держись")
        check(cleaned != null && cleaned.strOf("path") == "a.kt") { "мусор вокруг JSON не вычищен: $cleaned" }
        // Модели пишут числа вместо строк — принимаем.
        val numeric = core.llm.parseToolArgs("{\"limit\":20}")
        check(numeric!!.intOf("limit", 5) == 20) { "числовой аргумент не прочитан" }
        val readSpec = specs.first { it.name == "read_file" }
        check(core.llm.parseToolArgs("{}")!!.missingRequired(readSpec) == listOf("path")) {
            "обязательный path не найден"
        }
        check(core.llm.parseToolArgs("{\"path\":\"a.kt\"}")!!.missingRequired(readSpec).isEmpty()) {
            "path есть, а проверка требует"
        }
        println("OK разбор аргументов")

        // --- 3. Клетка путей ---
        listOf("../etc/passwd", "/etc/passwd", "a/../../etc/passwd", "C:/Windows/system32").forEach { bad ->
            val threw = try {
                ws.resolve(bad); false
            } catch (_: core.agent.WorkspaceDenied) {
                true
            } catch (_: Exception) {
                true
            }
            check(threw) { "клетка пропустила путь: $bad" }
        }
        val jailed = try {
            ws.resolve(".git/config"); false
        } catch (_: core.agent.WorkspaceDenied) {
            true
        }
        check(jailed) { "агент добрался до .git/config" }
        check(ws.norm("src/App.kt") == "src/App.kt") { "norm вернул ${ws.norm("src/App.kt")}" }
        check(ws.listFiles("src").contains("src/App.kt")) { "listFiles по префиксу не нашёл файл" }
        println("OK клетка путей")

        // --- 4. Чтение и поиск ---
        val read = ws.readText("docs/README.md")
        check(read.contains("Старая строка")) { "read_file вернул не то: $read" }
        check(ws.readText("docs/README.md", 1, 1).contains("# Проект")) { "диапазон строк не работает" }
        val grep = ws.grep("Старая строка")
        check(grep.any { it.path == "docs/README.md" }) { "grep не нашёл строку: $grep" }
        val refs = ws.findReferences("main")
        check(refs.any { it.path == "src/App.kt" && it.declaration }) { "find_references не нашёл объявление: $refs" }
        println("OK чтение и поиск (grep=${grep.size}, refs=${refs.size})")

        // --- 5. unified diff ---
        check(core.agent.unifiedDiff("a.md", "одно и то же", "одно и то же").isEmpty()) { "диф без изменений не пуст" }
        val d = core.agent.unifiedDiff("a.md", "alpha\nbeta\ngamma", "alpha\nBETA\ngamma")
        check(d.startsWith("--- a/a.md") && d.contains("+++ b/a.md")) { "нет заголовков дифа: $d" }
        check(d.contains("-beta") && d.contains("+BETA")) { "нет пары -/+ в дифе: $d" }
        check(d.contains("@@")) { "нет хунка в дифе: $d" }
        println("OK unified diff")

        // --- 6. plan/apply: диф ДО записи, запись после разрешения ---
        val plan = ws.planEdit("docs/README.md", "Старая строка документации.", "Новая строка документации.")
        check(plan.diff.contains("-Старая") && plan.diff.contains("+Новая")) { "диф плана неверен: ${plan.diff}" }
        check(!ws.readText("docs/README.md").contains("Новая")) { "planEdit записал на диск до подтверждения" }
        val bad = try {
            ws.planEdit("docs/README.md", "несуществующий фрагмент", "x"); false
        } catch (_: Exception) {
            true
        }
        check(bad) { "planEdit принял old_string, которого нет в файле" }
        ws.apply(plan)
        check(ws.readText("docs/README.md").contains("Новая")) { "apply не записал" }
        // Создание нового файла.
        val create = ws.planWrite("docs/ADR.md", "# Решение\n")
        check(create.action == "create") { "новый файл должен быть create, а не ${create.action}" }
        ws.apply(create)
        check(ws.exists("docs/ADR.md")) { "новый файл не создан" }
        println("OK plan/apply (диф до записи)")

        // --- 6b. Снапшоты и откат ---
        // Откат не имеет права удалять файл, снапшота которого нет: раньше
        // «нет снапшота» читалось как «прогон создал файл», и revert сносил
        // живой README. Проверяем оба направления явно.
        val snapDir = java.io.File(tmp, "snaps")
        ws.runIdForSnapshot = "run-test"
        ws.snapshotDir = snapDir
        ws.apply(ws.planEdit("docs/README.md", "Новая строка документации.", "Ещё новее."))
        check(ws.readText("docs/README.md").contains("Ещё новее")) { "вторая правка не записана" }
        check(ws.revert("docs/README.md", "run-test", snapDir)) { "откат существующего файла не отработал" }
        // Откат возвращает состояние на момент СНАПШОТА, а не HEAD: к этому
        // моменту в файле уже была предыдущая правка из блока plan/apply.
        check(
            ws.readText("docs/README.md").contains("Новая строка документации"),
        ) { "откат вернул не состояние на момент снапшота: ${ws.readText("docs/README.md")}" }
        // Откат НЕ трогает файл, чьего нет в снапшотах (файл создан прогоном).
        val outside = ws.planWrite("docs/Новый_файл.md", "# новый\n")
        ws.apply(outside)
        check(ws.exists("docs/Новый_файл.md")) { "новый файл не создан" }
        check(
            !ws.revert("docs/ADR.md", "другой-прогон", snapDir),
        ) { "откат чужого файла не должен считаться успешным" }
        check(ws.exists("docs/ADR.md")) { "откат удалил файл, которого не касался прогон" }
        // Файл, созданный прогоном, снапшот помечает — и только его можно удалить.
        check(ws.wasNewInRun("docs/Новый_файл.md", "run-test", snapDir)) { "созданный файл не помечен" }
        check(!ws.wasNewInRun("docs/README.md", "run-test", snapDir)) { "существующий файл помечен как новый" }
        check(ws.revert("docs/Новый_файл.md", "run-test", snapDir)) { "откат созданного файла не отработал" }
        check(!ws.exists("docs/Новый_файл.md")) { "созданный прогоном файл не удалён при откате" }
        check(ws.exists("docs/ADR.md")) { "откат задел чужой файл" }
        println("OK снапшоты и откат (чужое и пропавший снапшот не трогают файлы)")

        // --- 6c. Разбор вызовов из текста (модель без native tools) ---
        val mcpShape = core.agent.parseToolCallsFromText(
            "{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.kt\"}}",
        )
        check(mcpShape?.single()?.name == "read_file") { "плоская форма вызова не разобрана: $mcpShape" }
        check(mcpShape.single().arguments.strOf("path") == "a.kt") { "аргументы не разобраны: $mcpShape" }
        val openAiShape = core.agent.parseToolCallsFromText(
            """{"function":{"name":"grep","arguments":"{\"pattern\":\"X\"}"}}""",
        )
        check(openAiShape?.single()?.name == "grep") { "форма OpenAI не разобрана: $openAiShape" }
        check(openAiShape.single().arguments.strOf("pattern") == "X") { "аргументы-строка не разобраны" }
        // Несколько объектов подряд — частый вывод локальных моделей.
        val many = core.agent.parseToolCallsFromText(
            """{"name":"read_file","arguments":{"path":"a.kt"}}""" + "\n" +
                """{"name":"read_file","arguments":{"path":"b.kt"}}""",
        )
        check(many?.size == 2) { "несколько вызовов подряд не разобраны: $many" }
        // Обычный ответ с фигурными скобками НЕ должен считаться вызовом.
        check(
            core.agent.parseToolCallsFromText("Список изменений: {\"файлы\": [\"README.md\"]}") == null,
        ) { "чужой JSON принят за вызов тула" }
        check(core.agent.parseToolCallsFromText("Готово, всё нашёл.") == null) { "обычный текст принят за вызов" }
        println("OK разбор вызовов из текста (MCP, OpenAI, несколько подряд, ложные срабатывания)")

        // --- 7. Редьюсер ---
        var run = core.agent.AgentMachine.newRun("demo", "найти X")
        val cfg = core.agent.AgentConfig(maxSteps = 3, maxToolCalls = 5, maxFilesChanged = 2)
        fun step(e: core.agent.AgentEvent) = core.agent.AgentMachine.reduce(run, e, cfg).also { run = it.run }

        val g = step(core.agent.AgentEvent.GoalAccepted("найти X"))
        check(g.commands.contains(core.agent.AgentCommand.CallLlm)) { "GoalAccepted должен звать LLM" }
        check(run.goal == "найти X") { "цель не сохранилась" }
        step(core.agent.AgentEvent.StepStarted(1))
        check(run.state is core.agent.RunState.Thinking) { "состояние не Thinking" }
        step(core.agent.AgentEvent.ToolsProposed("read_file", "c1", "{\"path\":\"a\"}"))
        step(core.agent.AgentEvent.ToolStarted("c1"))
        check(run.byId("c1")?.state is core.agent.ToolState.Running) { "вызов не в Running" }
        step(core.agent.AgentEvent.ToolSucceeded("c1", "прочитано", ""))
        check(run.byId("c1")?.state is core.agent.ToolState.Done) { "вызов не в Done" }
        // Гарг opencode: терминальное событие из терминального состояния игнорируется.
        step(core.agent.AgentEvent.ToolFailed("c1", "опоздно"))
        check(run.byId("c1")?.state is core.agent.ToolState.Done) { "реплей перетирает результат вызова" }
        val fin = step(core.agent.AgentEvent.Finished("сделано"))
        check(fin.terminal && fin.commands.contains(core.agent.AgentCommand.Finish)) { "Finished не терминален" }
        // После терминала события игнорируются.
        step(core.agent.AgentEvent.StepStarted(9))
        check(run.steps == 1) { "события после терминала меняют прогон" }
        println("OK редьюсер: переходы и гарг терминальных событий")

        // --- 8. Отказ по разрешению обрывает прогон и не крутит цикл ---
        var r2 = core.agent.AgentMachine.newRun("demo", "обновить доки")
        val cfg2 = core.agent.AgentConfig()
        fun step2(e: core.agent.AgentEvent) = core.agent.AgentMachine.reduce(r2, e, cfg2).also { r2 = it.run }
        step2(core.agent.AgentEvent.GoalAccepted("обновить доки"))
        step2(core.agent.AgentEvent.StepStarted(1))
        step2(core.agent.AgentEvent.ToolsProposed("write_file", "w1", "{\"path\":\"x.md\"}"))
        step2(
            core.agent.AgentEvent.ApprovalRequested(
                core.agent.PendingChange("x.md", "create", "@@ +a @@", 1, "создать x.md"),
            ),
        )
        check(r2.state is core.agent.RunState.AwaitingApproval) { "перед правкой не ждём подтверждения" }
        val denied = step2(core.agent.AgentEvent.ApprovalDenied("x.md"))
        check(denied.terminal) { "отказ должен быть терминален" }
        check(denied.commands.contains(core.agent.AgentCommand.AbortStep)) { "отказ должен обрывать шаг" }
        check(r2.state is core.agent.RunState.Failed) { "после отказа прогон не провален: ${r2.state}" }
        println("OK отказ по разрешению обрывает прогон")

        // --- 9. Бюджет ---
        var r3 = core.agent.AgentMachine.newRun("demo", "цель")
        val cfg3 = core.agent.AgentConfig(maxSteps = 1, maxToolCalls = 99, maxFilesChanged = 99)
        core.agent.AgentMachine.reduce(r3, core.agent.AgentEvent.GoalAccepted("цель"), cfg3).also { r3 = it.run }
        core.agent.AgentMachine.reduce(r3, core.agent.AgentEvent.StepStarted(1), cfg3).also { r3 = it.run }
        check(!core.agent.AgentMachine.needsContinuation(r3, cfg3)) { "после последнего шага продолжать нельзя" }
        check(core.agent.AgentMachine.budgetReason(r3, cfg3) == "лимит шагов (1)") {
            "не названа причина остановки: ${core.agent.AgentMachine.budgetReason(r3, cfg3)}"
        }
        println("OK бюджет шагов")

        // --- 10. Правила разрешений ---
        val perms = core.agent.PermissionStore(tmp)
        check(perms.evaluate("demo", "edit", "docs/a.md") == core.agent.PermissionEffect.Ask) {
            "по умолчанию должно быть ask, а не auto-allow"
        }
        perms.remember("demo", core.agent.PermissionRule("edit", "docs/*", core.agent.PermissionEffect.Allow.name))
        check(perms.evaluate("demo", "edit", "docs/a.md") == core.agent.PermissionEffect.Allow) { "правило не сработало" }
        check(perms.evaluate("demo", "edit", "src/a.md") == core.agent.PermissionEffect.Ask) { "маска слишком широкая" }
        perms.remember("demo", core.agent.PermissionRule("edit", "docs/*", core.agent.PermissionEffect.Deny.name))
        check(perms.evaluate("demo", "edit", "docs/a.md") == core.agent.PermissionEffect.Deny) { "последнее правило должно побеждать" }
        check(core.agent.globMatch("*", "любой/путь")) { "глоб * не совпал" }
        check(core.agent.globMatch("docs/*", "docs/a/b.md")) { "глоб docs/* не совпал с вложенным" }
        check(!core.agent.globMatch("docs/*", "src/a.md")) { "глоб docs/* совпал с чужим" }
        println("OK правила разрешений")

        // --- 11. Промпт агента ---
        val sys = core.rag.buildAgentSystem("demo", specs.map { it.name }, "demo")
        check(sys.contains("read_file") && sys.contains("write_file")) { "в промпте нет имён тулов" }
        check(sys.contains("edit_file")) { "в промпте нет предпочтения edit_file" }
        check(sys.length < 4000) { "системный промпт раздулся: ${sys.length}" }
        val usr = core.rag.buildAgentUser("обнови документацию", "Проект: demo. Ветка: main.")
        check(usr.contains("обнови документацию") && usr.indexOf("ЦЕЛЬ") > 0) { "цель не попала в промпт: $usr" }
        println("OK промпты агента")

        // --- 12. MCP-слой: те же тулы отдаются по JSON-RPC ---
        val server = core.support.McpServer(tools.mcpRegistry("demo"))
        val list = kotlinx.coroutines.runBlocking { server.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }
        check(list.contains("read_file") && list.contains("edit_file")) { "MCP tools/list не показал тулы агента" }
        val call = kotlinx.coroutines.runBlocking {
            server.handle(
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"grep","arguments":{"pattern":"привет"}}}""",
            )
        }
        check(call.contains("src/App.kt")) { "MCP tools/call grep не отработал: $call" }
        val unknown = kotlinx.coroutines.runBlocking {
            server.handle(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"rm_rf","arguments":{}}}""",
            )
        }
        check(unknown.contains("isError") || unknown.contains("Ошибка")) { "неизвестный тул должен дать ошибку: $unknown" }
        println("OK MCP-слой агента (tools/list + tools/call)")

        // --- 13. Полировка аргументов тулов ---
        val writeSpec = specs.first { it.name == "write_file" }
        check(core.llm.parseToolArgs("{\"path\":\"a.md\"}")!!.missingRequired(writeSpec) == listOf("content")) {
            "write_file без content не отловлен"
        }
        println("OK обязательные аргументы")

        // --- 14. Разделение чатов: информационные и агентные ---
        // Ключевая проверка разделения: у агентного чата свой список прогонов,
        // а удаление чата не стирает историю, а отвязывает её.
        val chatStore = ChatStore(tmp, "chats_split.json")
        val infoChat = RagChat(id = "c1", name = "вопросы")
        val agentChat = RagChat(id = "a1", name = "правки", ragEnabled = false, kind = core.rag.CHAT_KIND_AGENT)
        chatStore.upsertChat(infoChat)
        chatStore.upsertChat(agentChat)
        check(!infoChat.isAgent && agentChat.isAgent) { "вид чата определён неверно" }
        check(infoChat.kind == core.rag.CHAT_KIND_RAG) { "чат по умолчанию должен быть информационным" }
        check(chatStore.state.ragChats().map { it.id } == listOf("c1")) { "информационные чаты отфильтрованы неверно" }
        check(chatStore.state.agentChats().map { it.id } == listOf("a1")) { "агентные чаты отфильтрованы неверно" }
        val runStore = core.agent.RunStore(tmp)
        val rA = core.agent.AgentMachine.newRun("demo", "найти X", chatId = "a1")
        val rB = core.agent.AgentMachine.newRun("demo", "найти Y", chatId = "a1")
        val rC = core.agent.AgentMachine.newRun("demo", "headless")
        listOf(rA, rB, rC).forEach { runStore.save(it) }
        check(runStore.forChat("a1").map { it.id } == listOf(rA.id, rB.id)) {
            "прогоны агентного чата не те, что нужно: ${runStore.forChat("a1").map { it.id }}"
        }
        check(runStore.latestForChat("a1")?.id == rB.id) { "последний прогон чата выбран неверно" }
        check(runStore.forChat("c1").isEmpty()) { "у информационного чата появились прогоны" }
        check(runStore.forChat("a1").none { it.chatId == "" }) { "отвязанный прогон попал в чат" }
        runStore.detachChat("a1")
        check(runStore.forChat("a1").isEmpty()) { "после удаления чата прогоны остались в нём" }
        check(runStore.runs.size == 3) { "отвязка чата удалила прогоны из истории" }
        check(runStore.get(rC.id)?.chatId == null || runStore.get(rC.id)?.chatId == "") { "headless-прогон привязался к чату" }
        println("OK разделение чатов и история прогонов")

        println("VERIFY-AGENT PASSED")
    } finally {
        try {
            tmp.deleteRecursively()
        } catch (_: Exception) {
        }
    }
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
    println("week6 support-ticket --list | --projects | --id <тикет> [--status open|in-progress|needs-info|resolved|closed]")
    println("      [--assignee <ник>] [--comment <текст>] [--escalate <причина>]")
    println("  Task 3: контекст CRM и правки тикета (правка сразу меняет ответы бота).")
    println("week6 mcp-tools [--server <URL>]")
    println("  Task 3: tools/list CRM-сервера по MCP.")
    println("week6 support-eval --cases <файл.json> [--project <имя>] [--server <URL>]")
    println("  Task 3: прогон кейсов качества (тикет сработал / фразы есть / запрещённого нет).")
    println("week6 verify-support")
    println("  Task 3: оффлайн-проверка слоя поддержки (фронтматтер, MCP, промпт, CRM).")
    println()
    println("week6 agent-run --goal \"цель\" [--project <имя>] [--chat <имя>] [--auto-approve] [--max-steps 8] [--max-files 10] [--out run.json]")
    println("  Task 4: агент с доступом к файлам. Цель ставит человек, файлы выбирает агент.")
    println("  Без --auto-approve каждое изменение спрашивает подтверждение (показывается diff).")
    println("week6 agent-tools [--project <имя>]")
    println("  Task 4: схемы тулов агента — ровно то, что видит модель и MCP-клиент.")
    println("week6 agent-runs | agent-diff --run <id> | agent-revert --run <id>")
    println("  Task 4: список прогонов / diff прогона / откат по снапшотам.")
    println("week6 verify-agent")
    println("  Task 4: оффлайн-проверка слоя агента (редьюсер, клетка путей, diff, MCP) без сети и БД.")
    println("  Task 3: оффлайн-проверка слоя поддержки (фронтматтер, MCP, промпт, CRM).")
}

// Task 5: чат по имени для CLI — тот же ChatStore, что у desktop.
private fun findOrCreateChat(chats: ChatStore, name: String): String {
    chats.state.chats.firstOrNull { it.name == name }?.let { return it.id }
    val chat = RagChat(id = newChatId(), name = name)
    chats.upsertChat(chat)
    return chat.id
}

// Task 4: агентный чат по имени — прогон из CLI попадает в его историю,
// и в desktop он виден в секции «Агентные чаты» вместе с diff.
private fun findOrCreateAgentChat(chats: ChatStore, name: String): String {
    chats.state.chats.firstOrNull { it.isAgent && it.name == name }?.let { return it.id }
    val chat = RagChat(id = newChatId(), name = name, ragEnabled = false, kind = core.rag.CHAT_KIND_AGENT)
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

// Флаги без значения: значение после них читать нельзя, иначе флаг «съедает»
// следующий токен (--auto-approve --goal X терял --goal). Заодно чинит
// --dry-run у review из Task 2 — там был ровно тот же случай.
private val BOOL_FLAGS = setOf("--auto-approve", "--dry-run", "--local")

// Позиционные аргументы (пути/названия статей) отдельно от --флагов.
private fun splitPaths(args: Array<String>): Pair<List<String>, Array<String>> {
    val pos = mutableListOf<String>()
    val rest = mutableListOf<String>()
    var i = 1
    while (i < args.size) {
        val a = args[i]
        when {
            a in BOOL_FLAGS -> {
                rest += a
                i++
            }
            a.startsWith("--") && i + 1 < args.size -> {
                rest += a
                rest += args[i + 1]
                i += 2
            }
            else -> {
                pos += a
                i++
            }
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
