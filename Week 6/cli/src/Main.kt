package cli

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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

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
