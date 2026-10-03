package cli

import core.rag.DEFAULT_PROBES
import core.rag.RagService
import java.io.File
import kotlinx.coroutines.runBlocking

// Headless-доступ к тому же RagService, что и у desktop-приложения.
// Источники корпуса — явные файлы/папки с диска, привязки к коду проекта нет.
//
//   week4 index <путь...> [--strategy fixed|structure|both]
//   week4 compare <путь...>
//   week4 wiki <название статьи|URL> [...] [--strategy both]
//   week4 search <запрос...> [--strategy structure] [--topK 5]
//   week4 ask <вопрос...> [--strategy structure] [--topK 5]
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
        "index" -> {
            val (paths, rest) = splitPaths(args)
            if (paths.isEmpty()) fail("нужен хотя бы один путь: week4 index <путь...>")
            val strategy = flag(rest, "--strategy") ?: "both"
            val list = if (strategy == "both") listOf("fixed", "structure") else listOf(strategy)
            val roots = paths.map { File(it) }
            for (s in list) {
                val out = svc.reindexRoots(roots, s) { println(it) }
                println("ИТОГ [$s]: ${out.stats.chunks} чанков из ${out.docs} документов " +
                    "(avg ${out.stats.avgChars} симв, dim ${out.stats.dim}, модель ${out.stats.embedModel})")
            }
        }
        "compare" -> {
            val (paths, rest) = splitPaths(args)
            if (paths.isEmpty()) fail("нужен хотя бы один путь: week4 compare <путь...>")
            // Свои пробы про САМ корпус, иначе дефолтные (про агента) дадут
            // одинаковый мусор на обеих стратегиях и разницы не будет видно.
            val probes = flags(rest, "--probe").ifEmpty { DEFAULT_PROBES }
            val topK = flag(rest, "--topK")?.toIntOrNull() ?: 3
            val res = svc.compareRoots(paths.map { File(it) }, probes, { println(it) }, topK)
            for (rep in listOf(res.fixed, res.structure)) {
                val st = rep.stats
                println("\n### ${st.strategy}: ${st.chunks} чанков / ${st.docs} доков / avg ${st.avgChars} симв / ${st.indexMs}мс")
                rep.probes.forEach { (q, hits) ->
                    println("  Q: $q")
                    hits.forEach { println("    - $it") }
                }
            }
        }
        "wiki" -> {
            val (inputs, rest) = splitPaths(args)
            if (inputs.isEmpty()) fail("нужна статья: week4 wiki <название|URL>")
            val strategy = flag(rest, "--strategy") ?: "both"
            val list = if (strategy == "both") listOf("fixed", "structure") else listOf(strategy)
            for (input in inputs) {
                println("Загружаю Википедию: $input ...")
                val f = svc.fetchWikiAndSave(input)
                println("Сохранено: ${f.absolutePath} (${f.length() / 1024} КБ)")
                val (docs, skipped) = core.rag.DocumentLoader.loadRoots(listOf(f))
                println("Документов: ${docs.size}, пропущено: $skipped")
                for (s in list) {
                    val stats = svc.indexDocs(docs, s) { println(it) }
                    println("ИТОГ [$s]: ${stats.chunks} чанков (avg ${stats.avgChars} симв)")
                }
            }
        }
        "search" -> {
            val (query, rest) = splitQuery(args)
            val strategy = flag(rest, "--strategy") ?: "structure"
            val topK = flag(rest, "--topK")?.toIntOrNull() ?: 5
            val hits = svc.search(query, strategy, topK)
            if (hits.isEmpty()) println("Ничего не найдено (индекс пуст?). Сначала: week4 index <путь...>")
            hits.forEachIndexed { i, h ->
                println("[${"%.4f".format(h.score)}] ${h.chunk.title} / ${h.chunk.section.ifBlank { "—" }} (${h.chunk.source})")
                println("  ${h.chunk.text.take(300).replace("\n", " ")}")
                if (i < hits.size - 1) println()
            }
        }
        "ask" -> {
            val (query, rest) = splitQuery(args)
            val strategy = flag(rest, "--strategy") ?: "structure"
            val topK = flag(rest, "--topK")?.toIntOrNull() ?: 5
            val ans = svc.ask(query, strategy, topK)
            println(ans.text)
            println("\nИсточники:")
            ans.sources.forEachIndexed { i, h -> println("  [S${i + 1}] ${h.chunk.title} / ${h.chunk.section.ifBlank { "—" }}") }
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
    println("week4 index <путь...> [--strategy fixed|structure|both]")
    println("week4 compare <путь...> [--probe \"вопрос...\" ...] [--topK 3]")
    println("  пример: week4 compare corpus --probe \"Сколько сольдо за погребение Катерины?\" --probe \"В каком разделе про коршуна и Фрейда?\"")
    println("week4 wiki <название статьи|URL> [...] [--strategy both]")
    println("week4 search <запрос...> [--strategy structure] [--topK 5]")
    println("week4 ask <вопрос...> [--strategy structure] [--topK 5]")
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
