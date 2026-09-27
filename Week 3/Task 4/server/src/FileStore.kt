import java.io.File

// Песочница для save_report_to_file: пишем только внутрь reportsDir,
// имя чистим от path-traversal, разрешаем только .md/.txt/.json.
object FileStore {
    private val ALLOWED = setOf("md", "txt", "json")
    private val NAME_RE = Regex("[^A-Za-z0-9._-]+")

    fun defaultOutDir(): File {
        val dir = System.getenv("QUAKE_REPORTS_DIR")?.let { File(it) }
            ?: File("reports")
        return dir.absoluteFile
    }

    fun save(content: String, rawName: String, outDir: File = defaultOutDir()): File {
        require(content.isNotBlank()) { "content is empty, nothing to save" }
        require(content.length <= 500_000) { "content too large (${content.length} chars, max 500000)" }
        var name = NAME_RE.replace(rawName.trim().ifBlank { "report.md" }, "_")
        if ("." !in name) name += ".md"
        val ext = name.substringAfterLast(".", "").lowercase()
        require(ext in ALLOWED) { "bad extension '.$ext', allowed: $ALLOWED" }
        outDir.mkdirs()
        val file = File(outDir, name).absoluteFile.normalize()
        require(file.absolutePath.startsWith(outDir.absoluteFile.normalize().absolutePath)) {
            "path traversal blocked: '$rawName'"
        }
        file.writeText(content, Charsets.UTF_8)
        return file
    }
}
