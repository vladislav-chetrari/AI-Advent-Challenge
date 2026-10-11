package cli

import core.api.ChatMessageDto
import core.api.Task5Client
import core.api.Task5Exception
import java.io.File
import kotlinx.coroutines.runBlocking

// CLI к Task 5 серверу: чат + загрузка документов. Справка — в cli/README.md.
fun main(args: Array<String>) = runBlocking {
    ensureUtf8Console()
    if (args.isEmpty()) {
        usage()
        return@runBlocking
    }
    try {
        runCommand(args)
    } catch (e: Task5Exception) {
        val where = if (e.httpStatus >= 0) "HTTP ${e.httpStatus} [${e.code}]" else "[${e.code}]"
        println("ОШИБКА $where: ${e.message}")
    } catch (e: IllegalArgumentException) {
        println("ОШИБКА: ${e.message}")
    } catch (e: Exception) {
        println("ОШИБКА: ${e.message}")
    }
}

private data class Common(
    val baseUrl: String,
    val key: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val system: String?,
)

private suspend fun runCommand(args: Array<String>) {
    when (args[0]) {
        "-h", "--help", "help" -> usage()
        "health" -> {
            val c = common(args)
            val h = Task5Client(c.baseUrl, c.key).use { it.health() }
            println("status=${h.status} llm=${h.llm} embed=${h.embed} ollama_ok=${h.ollama_ok} docs=${h.rag_docs} chunks=${h.rag_chunks}")
        }
        "models" -> {
            val c = common(args)
            val m = Task5Client(c.baseUrl, c.key).use { it.models() }
            println("Модели:")
            m.data.forEach { println("  - ${it.id}") }
        }
        "ask" -> {
            val (query, rest) = splitQuery(args)
            val c = common(args, rest)
            val messages = mutableListOf<ChatMessageDto>()
            if (c.system != null) messages += ChatMessageDto("system", c.system)
            messages += ChatMessageDto("user", query)
            val r = Task5Client(c.baseUrl, c.key).use { it.chat(messages, c.model, c.temperature, c.maxTokens) }
            println(r.choices.firstOrNull()?.message?.content.orEmpty())
            val u = r.usage
            if (u.total_tokens > 0) println("[in=${u.prompt_tokens} out=${u.completion_tokens} total=${u.total_tokens}]")
        }
        "chat" -> {
            val rest = args.drop(1).toTypedArray()
            val c = common(args, rest)
            var temperature = c.temperature
            var system = c.system
            val client = Task5Client(c.baseUrl, c.key)
            val history = mutableListOf<ChatMessageDto>()
            println("Чат: ${c.model.ifEmpty { "(дефолт сервера)" }} @ ${c.baseUrl}")
            println("Команды: /clear /temp <n> /system <текст> /exit")
            try {
                while (true) {
                    print("вы> ")
                    System.out.flush()
                    val line = readLineUtf8()?.trim() ?: break
                    if (line.isEmpty()) continue
                    when {
                        line == "/exit" || line == "/quit" || line == "/q" -> break
                        line == "/clear" -> {
                            history.clear()
                            println("(история очищена)")
                            continue
                        }
                        line.startsWith("/temp ") -> {
                            val t = line.removePrefix("/temp ").trim().toDoubleOrNull()
                            if (t == null) {
                                println("Нужно число, например /temp 0.3")
                                continue
                            }
                            temperature = t.coerceIn(0.0, 2.0)
                            println("(temperature=$temperature)")
                            continue
                        }
                        line.startsWith("/system ") -> {
                            system = line.removePrefix("/system ").trim()
                            history.clear()
                            println("(system заменён, история очищена)")
                            continue
                        }
                        line.startsWith("/") -> {
                            println("Неизвестная команда. /clear /temp /system /exit")
                            continue
                        }
                    }
                    val messages = mutableListOf<ChatMessageDto>()
                    if (system != null) messages += ChatMessageDto("system", system)
                    messages += history
                    messages += ChatMessageDto("user", line)
                    try {
                        val r = client.chat(messages, c.model, temperature, c.maxTokens)
                        val text = r.choices.firstOrNull()?.message?.content.orEmpty()
                        println(text)
                        history += ChatMessageDto("user", line)
                        history += ChatMessageDto("assistant", text)
                    } catch (e: Task5Exception) {
                        println("ОШИБКА HTTP ${e.httpStatus} [${e.code}]: ${e.message}")
                    }
                }
            } finally {
                client.close()
            }
            println("bye")
        }
        "docs" -> {
            val rest = args.drop(1)
            when (rest.firstOrNull()) {
                "list" -> {
                    val c = common(args)
                    val docs = Task5Client(c.baseUrl, c.key).use { it.docsList() }
                    if (docs.isEmpty()) println("(документов нет — добавь: task5 docs add --type wiki <название>)")
                    docs.forEach { println("- ${it.id} | ${it.title} | chunks=${it.chunks} chars=${it.chars}") }
                }
                "delete" -> {
                    val c = common(args)
                    val id = rest.drop(1).joinToString(" ").trim()
                    if (id.isEmpty()) fail("нужен id: task5 docs delete <id> (см. task5 docs list)")
                    val deleted = Task5Client(c.baseUrl, c.key).use { it.docsDelete(id) }
                    println("удалён: $deleted")
                }
                "add" -> {
                    val flags = rest.drop(1).toTypedArray()
                    val c = common(args, flags)
                    val type = flag(flags, "--type") ?: "text"
                    if (type != "text" && type != "wiki") fail("type: text | wiki")
                    val title = flag(flags, "--title").orEmpty()
                    val file = flag(flags, "--file")
                    val input = when {
                        file != null -> File(file).takeIf { it.isFile }?.readText(Charsets.UTF_8)
                            ?: fail("файл не найден: $file")
                        flag(flags, "--input") != null -> flag(flags, "--input")!!
                        else -> positional(flags).joinToString(" ").trim()
                            .ifEmpty { fail("нужен текст: task5 docs add --type text <текст...> | --file path | для wiki: task5 docs add --type wiki <название>") }
                    }
                    val r = Task5Client(c.baseUrl, c.key).use { it.docsAdd(type, title, input) }
                    println("проиндексирован: ${r.id} | ${r.title} | chunks=${r.chunks}")
                }
                else -> usage()
            }
        }
        "embed" -> {
            val (text, rest) = splitQuery(args)
            val c = common(args, rest)
            val model = flag(rest, "--model")?.takeIf { it.isNotBlank() } ?: "embeddinggemma:300m"
            val r = Task5Client(c.baseUrl, c.key).use { it.embed(model, listOf(text)) }
            val v = r.data.firstOrNull()?.embedding.orEmpty()
            println("model=$model dim=${v.size}")
            println("первые 8: ${v.take(8).joinToString { "%.4f".format(it) }}")
        }
        else -> usage()
    }
}

private suspend fun <T> Task5Client.use(block: suspend (Task5Client) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}

private fun fail(msg: String): Nothing = throw IllegalArgumentException(msg)

private fun common(args: Array<String>, rest: Array<String> = args.drop(1).toTypedArray()): Common {
    return Common(
        baseUrl = flag(rest, "--base-url") ?: System.getenv("TASK5_BASE_URL") ?: "http://localhost:8080",
        key = flag(rest, "--key") ?: System.getenv("TASK5_API_KEY").orEmpty(),
        model = flag(rest, "--model").orEmpty(),
        temperature = flag(rest, "--temperature")?.toDoubleOrNull() ?: 0.7,
        maxTokens = flag(rest, "--max-tokens")?.toIntOrNull() ?: 512,
        system = flag(rest, "--system"),
    )
}

private fun usage() {
    println("task5 health|models [--base-url ...] [--key ...]")
    println("  проверка сервера и список моделей")
    println("task5 ask <текст...> [--model ...] [--temperature 0.7] [--max-tokens 512] [--system \"...\"]")
    println("  разовый запрос в чат")
    println("task5 chat [--model ...] [--temperature 0.7] [--max-tokens 512] [--system \"...\"]")
    println("  REPL-чат с историей; команды: /clear /temp <n> /system <текст> /exit")
    println("task5 docs add --type text|wiki [--title \"...\"] [--file path | --input ... | <текст...>]")
    println("  индексация документа (text — свой текст/файл, wiki — название или URL статьи)")
    println("task5 docs list")
    println("task5 docs delete <id>")
    println("task5 embed <текст...> [--model embeddinggemma:300m]")
    println("  вектор текста: размерность + первые значения")
}

private fun ensureUtf8Console() {
    if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
        runCatching {
            ProcessBuilder("cmd", "/c", "chcp", "65001")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    runCatching {
        System.setOut(java.io.PrintStream(java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.FileDescriptor.out)), true, Charsets.UTF_8))
    }
    runCatching {
        System.setErr(java.io.PrintStream(java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.FileDescriptor.err)), true, Charsets.UTF_8))
    }
}

private val stdinUtf8: java.io.BufferedReader by lazy {
    java.io.BufferedReader(java.io.InputStreamReader(System.`in`, Charsets.UTF_8))
}

private fun readLineUtf8(): String? = runCatching { stdinUtf8.readLine() }.getOrNull()

private fun flag(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

// Позиционные слова без значений --флагов (значения пропускаем вместе с флагом).
private fun positional(args: Array<String>): List<String> {
    val pos = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        if (args[i].startsWith("--") && i + 1 < args.size) {
            i += 2
        } else if (args[i].startsWith("--")) {
            i += 1
        } else {
            pos += args[i]
            i++
        }
    }
    return pos
}

// Позиционные слова (запрос) отдельно от --флагов.
private fun splitQuery(args: Array<String>): Pair<String, Array<String>> {
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
    if (pos.isEmpty()) throw IllegalArgumentException("нужен текст запроса")
    return pos.joinToString(" ") to rest.toTypedArray()
}
