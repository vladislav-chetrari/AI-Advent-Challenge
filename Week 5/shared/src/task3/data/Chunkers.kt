package task3.data

// Порт чанкера из Недели 4 (core.rag.Chunkers): чистый Kotlin, без зависимостей.
// Стратегия одна — structure (на телефоне держим один индекс вместо двух).
//
// Схема: статья -> разделы -> параграфы с указанием раздела -> вектора.
// Статичной нарезки фиксированным окном здесь нет: длинные абзацы делятся
// только по семантическим границам (предложения, в крайнем случае — слова).

data class RawDoc(
    val source: String,
    val title: String,
    val text: String,
)

data class Chunk(
    val id: String,
    val source: String,
    val title: String,
    val section: String,
    val ord: Int,
    val text: String,
)

fun chunkId(doc: RawDoc, ord: Int): String = "${doc.source}#$ord"

private fun toChunk(doc: RawDoc, section: String, ord: Int, text: String): Chunk {
    return Chunk(
        id = chunkId(doc, ord),
        source = doc.source,
        title = doc.title,
        section = section,
        ord = ord,
        text = text.trim(),
    )
}

/** Разделы без собственного имени — префикс секции к параграфу не добавляем. */
private val GENERIC_SECTIONS = setOf("", "(начало)", "(весь файл)")

class StructureChunker(
    val maxChars: Int = 1000,
    val minChars: Int = 50,
) {
    fun chunk(doc: RawDoc): List<Chunk> {
        val sections = split(doc)
        val out = mutableListOf<Chunk>()
        var ord = 0
        for ((section, body) in sections) {
            val paragraphs = splitSectionToParagraphs(body)
            for (para in paragraphs) {
                if (para.length <= maxChars) {
                    out += toChunk(doc, section, ord++, withSection(section, para))
                } else {
                    // Длинный абзац: только семантические границы —
                    // предложения, в крайнем случае слова. Без окон и перекрытий.
                    splitLongParagraph(para, maxChars).forEach { piece ->
                        val clean = piece.trim()
                        if (clean.length >= minChars) {
                            out += toChunk(doc, section, ord++, withSection(section, clean))
                        }
                    }
                }
            }
        }
        return out
    }

    private fun split(doc: RawDoc): List<Pair<String, String>> {
        val text = doc.text.replace("\r\n", "\n")
        if (text.isBlank()) return emptyList()
        return splitMarkdown(text)
    }

    private fun splitMarkdown(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var section = "(начало)"
        var headings = 0
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) out += section to buf.toString()
            buf.clear()
        }
        for (line in text.lines()) {
            val md = Regex("^(#{1,4})\\s+(.*)").find(line)
            val wiki = Regex("^={2,4}\\s*(.+?)\\s*={2,4}\\s*$").find(line)
            val title = (md?.groupValues?.getOrNull(2)?.trim()
                ?: wiki?.groupValues?.getOrNull(1)?.trim()?.take(80))
            if (title != null && title.isNotBlank() && buf.length > 200) {
                flush()
                section = title
                headings++
            }
            buf.appendLine(line)
        }
        flush()
        if (headings > 0) return out.ifEmpty { listOf("(весь файл)" to text) }
        val pseudo = splitPseudoHeadings(text)
        if (pseudo.size > 1) return pseudo
        return splitSectionToParagraphs(text).map { "(начало)" to it }
            .ifEmpty { listOf("(весь файл)" to text) }
    }

    private fun splitPseudoHeadings(text: String): List<Pair<String, String>> {
        val blocks = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.size < 3) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        var section = "(начало)"
        val buf = StringBuilder()
        fun flush() {
            if (buf.toString().trim().length >= minChars) out += section to buf.toString()
            buf.clear()
        }
        for ((i, b) in blocks.withIndex()) {
            val singleLine = "\n" !in b
            val nextLong = i + 1 < blocks.size && blocks[i + 1].length > 150
            if (singleLine && b.length in 2..80 && nextLong && !b.endsWith(".") && i > 0) {
                flush()
                section = b
                buf.appendLine(b)
            } else {
                buf.appendLine(b).appendLine()
            }
        }
        flush()
        return out
    }

    /** Тело раздела -> параграфы по пустым строкам, мусор короче minChars отбрасываем. */
    private fun splitSectionToParagraphs(body: String): List<String> {
        return body.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.length >= minChars }
    }

    /**
     * Параграф снабжаем указанием раздела, чтобы чанк оставался
     * самодостаточным и в векторе, и в промпте.
     * Если заголовок уже первая строка параграфа — не дублируем.
     */
    private fun withSection(section: String, paragraph: String): String {
        val p = paragraph.trim()
        if (section.isBlank() || section in GENERIC_SECTIONS) return p
        if (p.startsWith(section)) return p
        val firstLine = p.lines().firstOrNull()?.trim().orEmpty()
        val stripped = firstLine.trimStart('#', '=', ' ', '\t').trimEnd('#', '=', ' ', '\t').trim()
        if (stripped == section) return p
        return "$section\n$p"
    }

    /**
     * Длинный абзац -> куски <= maxChars по границам предложений.
     * Предложения вылавливаем жадным паттерном без lookbehind
     * (портативность Regex в common-коде), упаковываем плотно.
     */
    private fun splitLongParagraph(para: String, maxChars: Int): List<String> {
        val lines = para.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val sentRe = Regex(".+?[.!?…]+\\s*|.+")
        val sentences = mutableListOf<String>()
        for (line in lines) {
            val found = sentRe.findAll(line).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
            if (found.isEmpty()) sentences += line else sentences += found
        }
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) out += buf.toString().trim()
            buf.clear()
        }
        for (s in sentences) {
            if (s.length > maxChars) {
                flush()
                out += splitByWords(s, maxChars)
                continue
            }
            if (buf.isEmpty()) {
                buf.append(s)
            } else if (buf.length + 1 + s.length <= maxChars) {
                buf.append(" ").append(s)
            } else {
                flush()
                buf.append(s)
            }
        }
        flush()
        return out.ifEmpty { listOf(para) }
    }

    /** Крайний fallback для бессоюзных простыней без пунктуации: делим по словам. */
    private fun splitByWords(text: String, maxChars: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) out += buf.toString()
            buf.clear()
        }
        for (w in words) {
            if (buf.isEmpty()) {
                buf.append(w)
            } else if (buf.length + 1 + w.length <= maxChars) {
                buf.append(" ").append(w)
            } else {
                flush()
                buf.append(w)
            }
        }
        flush()
        return out.ifEmpty { listOf(text) }
    }
}
