package core.rag

// Стратегия нарезки. Каждая возвращает чанки с заполненными метаданными.
// Задел на Task 2-5: новый способ нарезки = новый класс + имя стратегии,
// индекс хранится отдельно на стратегию, сравнение бесплатно.
interface Chunker {
    val name: String
    fun chunk(doc: RawDoc): List<Chunk>
}

private fun toChunk(doc: RawDoc, strategy: String, section: String, ord: Int, text: String): Chunk {
    val clean = text.trim()
    return Chunk(
        id = chunkId(doc, ord),
        strategy = strategy,
        source = doc.source,
        title = doc.title,
        section = section,
        ord = ord,
        text = clean,
        tokens = estimateTokens(clean.length),
    )
}

// Стратегия 1: фиксированное окно по символам с перекрытием.
// Режет по границам предложений, если они есть в окне, иначе — жёстко.
class FixedSizeChunker(
    val chunkChars: Int = 1200,
    val overlapChars: Int = 150,
) : Chunker {
    override val name: String = "fixed"

    override fun chunk(doc: RawDoc): List<Chunk> {
        val text = doc.text.replace("\r\n", "\n").trim()
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Chunk>()
        var ord = 0
        var start = 0
        while (start < text.length) {
            var end = (start + chunkChars).coerceAtMost(text.length)
            if (end < text.length) {
                // пробуем оборвать на конце предложения внутри последней трети окна
                val window = text.substring(start, end)
                val cutZone = window.length * 2 / 3
                val candidates = listOf(
                    window.lastIndexOf("\n\n"),
                    window.lastIndexOf(". "),
                    window.lastIndexOf(".\n"),
                    window.lastIndexOf("\n"),
                ).filter { it >= cutZone }
                val best = candidates.maxOrNull() ?: -1
                if (best > 0) end = start + best + 1
            }
            val piece = text.substring(start, end).trim()
            if (piece.length >= 50) {
                out += toChunk(doc, name, section = "", ord = ord++, text = piece)
            }
            if (end >= text.length) break
            start = (end - overlapChars).coerceAtLeast(start + 1)
        }
        return out
    }
}

// Стратегия 2: структурная — сначала делим по структуре документа,
// затем слишком длинные секции добиваем фиксированным окном.
// Markdown: заголовки ##/###, код: class/fun/object, остальное: абзацы.
class StructureChunker(
    val maxChars: Int = 1500,
    val overlapChars: Int = 120,
) : Chunker {
    override val name: String = "structure"
    private val fixed = FixedSizeChunker(maxChars, overlapChars)

    override fun chunk(doc: RawDoc): List<Chunk> {
        val sections = split(doc)
        val out = mutableListOf<Chunk>()
        var ord = 0
        for ((section, body) in sections) {
            val text = body.trim()
            if (text.length < 50) continue
            if (text.length <= maxChars) {
                out += toChunk(doc, name, section, ord++, text)
            } else {
                // длинная секция: режем фиксированно, но секцию сохраняем в метаданных
                // с номером части — иначе десятки чанков получат одинаковый section
                // и в выдаче их будет не различить
                val sub = RawDoc(doc.source, doc.title, text)
                val pieces = fixed.chunk(sub)
                pieces.forEachIndexed { k, c ->
                    val part = if (pieces.size > 1) "$section [${k + 1}/${pieces.size}]" else section
                    out += c.copy(id = chunkId(doc, ord), strategy = name, section = part, ord = ord++)
                }
            }
        }
        return out
    }

    private fun split(doc: RawDoc): List<Pair<String, String>> {
        val text = doc.text.replace("\r\n", "\n")
        if (text.isBlank()) return emptyList()
        return when {
            doc.title.endsWith(".md", ignoreCase = true) -> splitMarkdown(text)
            doc.title.endsWith(".kt", ignoreCase = true) ||
                doc.title.endsWith(".java", ignoreCase = true) -> splitCode(doc.title, text)
            else -> splitParagraphs("", text)
        }
    }

    private fun splitMarkdown(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var section = "(начало)"
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) out += section to buf.toString()
            buf.clear()
        }
        for (line in text.lines()) {
            val h = Regex("^(#{1,4})\\s+(.*)").find(line)
            if (h != null && buf.length > 200) {
                flush()
                section = h.groupValues[2].trim().take(80)
            }
            buf.appendLine(line)
        }
        flush()
        return out.ifEmpty { listOf("(весь файл)" to text) }
    }

    private fun splitCode(fileName: String, text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var section = fileName
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) out += section to buf.toString()
            buf.clear()
        }
        // package/import преамбула — отдельным куском
        for (line in text.lines()) {
            val decl = Regex("^\\s*(class|object|interface|fun\\s+\\w+|data\\s+class|enum\\s+class)\\s+([A-Za-z0-9_]+)").find(line)
            if (decl != null && buf.length > 300) {
                flush()
                section = "${decl.groupValues[1]} ${decl.groupValues[2]}"
            }
            buf.appendLine(line)
        }
        flush()
        return out.ifEmpty { listOf(fileName to text) }
    }

    private fun splitParagraphs(section: String, text: String): List<Pair<String, String>> {
        val parts = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.length >= 50 }
        return parts.map { section to it }.ifEmpty { listOf(section to text) }
    }
}
