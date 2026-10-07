package cli

import core.llm.ChatMessage
import core.llm.LlmResult
import core.llm.QwenClient
import core.llm.QwenModel
import kotlinx.coroutines.runBlocking

// Доступ к локальной Qwen через Ollama, та же команда на Mac и ПК.
//
//   week5 status [--model qwen2.5-7b] [--base-url http://localhost:11434]
//   week5 ask <текст...> [--model ...] [--temperature 0.7] [--system "..."]
//   week5 chat [--model ...] [--temperature 0.7] [--system "..."]
//   week5 demo [--model ...]   (3 запроса разной сложности для видео Task 1)
fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty()) {
        usage()
        return@runBlocking
    }
    try {
        runCommand(args)
    } catch (e: IllegalArgumentException) {
        println("ОШИБКА: ${e.message}")
    } catch (e: Exception) {
        println("ОШИБКА: ${e.message}")
    }
}

private suspend fun runCommand(args: Array<String>) {
    when (args[0]) {
        "-h", "--help", "help" -> usage()
        "status" -> {
            val rest = args.drop(1).toTypedArray()
            val model = QwenModel.resolve(flag(rest, "--model"))
            val baseUrl = flag(rest, "--base-url") ?: "http://localhost:11434"
            val client = QwenClient(model = model.apiId, baseUrl = baseUrl)
            try {
                val installed = client.listModels()
                if (installed.isEmpty()) {
                    fail("Ollama недоступна на $baseUrl. Запусти: `ollama serve` (или Ollama.app)")
                }
                println("Ollama: $baseUrl — OK")
                println("Установлено (${installed.size}):")
                installed.forEach { println("  - $it") }
                val hit = installed.any { it.startsWith(model.apiId) || it.startsWith(model.apiId.substringBefore(":")) }
                if (hit) println("Модель ${model.apiId} — на месте, можно: week5 ask \"привет\" --model ${model.id}")
                else println("Модели ${model.apiId} нет — скачай: `ollama pull ${model.apiId}`")
            } finally {
                client.close()
            }
        }
        "ask" -> {
            val (query, rest) = splitQuery(args)
            val model = QwenModel.resolve(flag(rest, "--model"))
            val baseUrl = flag(rest, "--base-url") ?: "http://localhost:11434"
            val temperature = flag(rest, "--temperature")?.toDoubleOrNull() ?: 0.7
            val system = flag(rest, "--system")
                ?: "You are a helpful AI assistant. Always reply in the same language the user writes in (Russian for Russian messages)."
            val client = QwenClient(model = model.apiId, temperature = temperature, baseUrl = baseUrl)
            try {
                println(answerOnce(client, system, query))
            } finally {
                client.close()
            }
        }
        "chat" -> {
            val rest = args.drop(1).toTypedArray()
            val model = QwenModel.resolve(flag(rest, "--model"))
            val baseUrl = flag(rest, "--base-url") ?: "http://localhost:11434"
            var temperature = flag(rest, "--temperature")?.toDoubleOrNull() ?: 0.7
            var system = flag(rest, "--system")
                ?: "You are a helpful AI assistant. Always reply in the same language the user writes in (Russian for Russian messages)."
            var client = QwenClient(model = model.apiId, temperature = temperature, baseUrl = baseUrl)
            val history = mutableListOf(ChatMessage("system", system))
            println("Чат: ${model.label} (${model.apiId}, ctx=${model.contextLimit}) @ $baseUrl")
            println("Команды: /clear /temp <n> /system <текст> /exit")
            try {
                while (true) {
                    print("вы> ")
                    val line = readlnOrNull()?.trim() ?: break
                    if (line.isEmpty()) continue
                    when {
                        line == "/exit" || line == "/quit" || line == "/q" -> break
                        line == "/clear" -> {
                            history.clear()
                            history += ChatMessage("system", system)
                            println("(история очищена)")
                            continue
                        }
                        line.startsWith("/temp ") -> {
                            val t = line.removePrefix("/temp ").trim().toDoubleOrNull()
                            if (t == null) {
                                println("Нужно число, например /temp 0.3")
                                continue
                            }
                            temperature = t
                            client.close()
                            client = QwenClient(model = model.apiId, temperature = temperature, baseUrl = baseUrl)
                            println("(temperature=$temperature)")
                            continue
                        }
                        line.startsWith("/system ") -> {
                            system = line.removePrefix("/system ").trim()
                            history.clear()
                            history += ChatMessage("system", system)
                            println("(system заменён, история очищена)")
                            continue
                        }
                        line.startsWith("/") -> {
                            println("Неизвестная команда. /clear /temp /system /exit")
                            continue
                        }
                    }
                    println(answerOnce(client, null, line, history))
                }
            } finally {
                client.close()
            }
            println("bye")
        }
        // Три запроса разной сложности одним прогоном — результат для видео Task 1.
        "demo" -> {
            val rest = args.drop(1).toTypedArray()
            val model = QwenModel.resolve(flag(rest, "--model"))
            val baseUrl = flag(rest, "--base-url") ?: "http://localhost:11434"
            val temperature = flag(rest, "--temperature")?.toDoubleOrNull() ?: 0.7
            val system = flag(rest, "--system")
                ?: "You are a helpful AI assistant. Always reply in the same language the user writes in (Russian for Russian messages)."
            val prompts = listOf(
                "1 (простой): Ответь одним предложением: что такое Ollama?",
                "2 (средний): Объясни за 5-7 предложений, чем sliding window отличается от хранения фактов (sticky facts) при управлении контекстом диалога. Приведи пример потери факта.",
                "3 (сложный): Составь мини-ТЗ из 5 пунктов для CLI к локальной LLM (команды status/ask/chat), затем самокритично укажи 2 слабых места этого ТЗ.",
            )
            val client = QwenClient(model = model.apiId, temperature = temperature, baseUrl = baseUrl)
            try {
                println("Демо: ${model.label} (${model.apiId}) @ $baseUrl\n")
                for (p in prompts) {
                    println("### Запрос: $p")
                    println(answerOnce(client, system, p))
                    println()
                }
            } finally {
                client.close()
            }
        }
        else -> usage()
    }
}

// Один запрос. Если передан history (чат), дописывает туда user/assistant;
// иначе шлёт разовый system+user. Ошибки — текстом, без исключений.
private suspend fun answerOnce(
    client: QwenClient,
    system: String?,
    query: String,
    history: MutableList<ChatMessage>? = null,
): String {
    val convo = history ?: mutableListOf()
    if (history == null && system != null) convo += ChatMessage("system", system)
    convo += ChatMessage("user", query)
    return when (val r = client.complete(convo.toList())) {
        is LlmResult.Ok -> {
            convo += ChatMessage("assistant", r.text)
            buildString {
                append(r.text)
                val u = r.usage
                if (u.totalTokens > 0) append("\n[in=${u.promptTokens} out=${u.completionTokens} total=${u.totalTokens}]")
            }
        }
        is LlmResult.HttpError -> {
            if (convo.lastOrNull()?.role == "user") convo.removeLastOrNull()
            if (r.code == 404 && r.detail.contains("not found", ignoreCase = true))
                "ОШИБКА HTTP 404: модель ${client.modelName} не скачана — выполни `ollama pull ${client.modelName}`"
            else "ОШИБКА HTTP ${r.code}${if (r.detail.isNotBlank()) ": ${r.detail}" else ""}"
        }
        is LlmResult.NetworkError ->
            "ОШИБКА СЕТИ: ${r.detail} (проверь `ollama serve` и `ollama pull ${client.modelName}`)".also {
                if (convo.lastOrNull()?.role == "user") convo.removeLastOrNull()
            }
        LlmResult.Empty -> {
            if (convo.lastOrNull()?.role == "user") convo.removeLastOrNull()
            "ОШИБКА: пустой ответ модели"
        }
    }
}

private fun fail(msg: String): Nothing {
    throw IllegalArgumentException(msg)
}

private fun usage() {
    println("week5 status [--model qwen2.5-7b] [--base-url http://localhost:11434]")
    println("  проверка: сервер доступен + какие модели скачаны (`ollama list` через API)")
    println("week5 ask <текст...> [--model qwen2.5-7b|qwen3-8b] [--temperature 0.7] [--system \"...\"] [--base-url ...]")
    println("  разовый запрос, ответ + [in/out/total токены]")
    println("week5 chat [--model ...] [--temperature 0.7] [--system \"...\"]")
    println("  REPL-чат с историей; команды: /clear /temp <n> /system <текст> /exit")
    println("week5 demo [--model ...]")
    println("  3 запроса разной сложности одним прогоном (простой/средний/сложный) для видео Task 1")
    println("Модели: " + QwenModel.ALL.joinToString { "${it.id} (${it.apiId}, ctx=${it.contextLimit})" })
    println("Сначала: `ollama serve` + `ollama pull qwen2.5:7b` (дефолт, ~4.7 ГБ — влезает в RTX 3060 12GB и летает на M4 Pro)")
}

private fun flag(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
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
    if (pos.isEmpty()) throw IllegalArgumentException("нужен текст запроса: week5 ask <текст...>")
    return pos.joinToString(" ") to rest.toTypedArray()
}
