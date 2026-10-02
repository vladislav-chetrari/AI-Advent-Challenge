package core.rag

import java.io.File

// Загрузка корпуса из явно выбранных пользователем корней:
// каждый корень — файл или папка. Привязки к коду проекта нет:
// книга (PDF), статьи (.md/.txt), сохранённые веб-страницы.
//
// Текстовые форматы читаются напрямую, PDF — через PDFBox.
// Бинарник/скан без текстового слоя тихо уходит в skipped.
object DocumentLoader {
    private val TEXT_EXT = setOf(
        "md", "markdown", "txt", "kt", "java", "py", "js", "ts",
        "yaml", "yml", "toml", "gradle", "kts", "json", "xml", "css", "html",
    )
    private const val PDF_EXT = "pdf"

    // Для фильтра в диалоге выбора файлов.
    val PICK_EXTENSIONS: Set<String> = TEXT_EXT + PDF_EXT

    private val SKIP_DIRS = setOf(
        "build", "out", "dist", "target", ".git", ".idea", ".gradle", "node_modules",
        "__pycache__", ".venv", "venv", ".tox", ".mypy_cache", ".pytest_cache", ".kotlin",
    )

    data class LoadResult(val docs: List<RawDoc>, val skipped: Int)

    // Главный вход: смешанный список файлов и папок (пути из UI/CLI).
    fun loadRoots(roots: List<File>, base: File? = null, maxFileChars: Int = 200_000): LoadResult {
        val docs = mutableListOf<RawDoc>()
        var skipped = 0
        for (root in roots) {
            if (!root.exists()) {
                skipped++
                continue
            }
            if (root.isFile) {
                val r = loadOneFile(root, base ?: root.parentFile ?: root, maxFileChars)
                if (r != null) docs += r else skipped++
            } else {
                val r = loadDir(root, maxFileChars)
                docs += r.docs
                skipped += r.skipped
            }
        }
        return LoadResult(docs.sortedBy { it.source }, skipped)
    }

    fun loadDir(root: File, maxFileChars: Int = 200_000): LoadResult {
        val docs = mutableListOf<RawDoc>()
        var skipped = 0
        root.walkTopDown()
            // скрытые папки (.venv внутри видимой тоже ловим по имени), зависимости и артефакты сборки — не корпус
            .onEnter { dir -> dir.name !in SKIP_DIRS && !dir.name.startsWith(".") }
            .filter { it.isFile }
            .forEach { f ->
                val r = loadOneFile(f, root, maxFileChars)
                if (r != null) docs += r else skipped++
            }
        return LoadResult(docs.sortedBy { it.source }, skipped)
    }

    private fun loadOneFile(f: File, base: File, maxFileChars: Int): RawDoc? {
        return try {
            val ext = f.extension.lowercase()
            val raw = when (ext) {
                in TEXT_EXT -> f.readText(Charsets.UTF_8)
                PDF_EXT -> extractPdf(f) ?: return null
                else -> return null
            }
            var text = raw.replace("\r\n", "\n").trim()
            if (text.length < 100) return null // пустые/мусорные файлы индексу не нужны
            if (text.length > maxFileChars) text = text.take(maxFileChars)
            val rel = try {
                base.toURI().relativize(f.toURI()).path.trimEnd('/')
            } catch (_: Exception) {
                f.name
            }
            RawDoc(source = rel.ifBlank { f.name }, title = f.name, text = text)
        } catch (_: Exception) {
            null
        }
    }

    // PDF -> текст. Скан без текстового слоя (пустой результат) — тоже null.
    fun extractPdf(f: File): String? {
        return try {
            org.apache.pdfbox.Loader.loadPDF(f).use { doc ->
                val stripper = org.apache.pdfbox.text.PDFTextStripper()
                stripper.sortByPosition = true
                stripper.getText(doc).replace("\r\n", "\n").trim().ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }
}
