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
import core.rag.newChatId
import kotlinx.coroutines.runBlocking

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
