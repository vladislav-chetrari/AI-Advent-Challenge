package core.project

import core.rag.RawDoc
import java.io.File

// Загрузчик проектной документации для RAG (Week 6 Task 1).
// Вход add: имя + (git-URL | локальный путь).
//  - git-URL клонируется `--depth 1` в <appDir>/repos/<name>, update делает pull;
//  - локальный путь индексируется на месте, без копирования.
// Каждый подходящий файл = один RawDoc(source="proj:<name>:<relpath>").
object ProjectLoader {
    fun prefix(name: String): String = "proj:$name:"

    fun sanitizeName(name: String): String {
        val n = name.trim()
        require(n.isNotEmpty()) { "пустое имя проекта" }
        require(n.matches(Regex("[A-Za-z0-9][A-Za-z0-9-_\\.]{0,60}"))) {
            "имя проекта: латиница/цифры/-_., до 61 символа"
        }
        return n
    }

    fun isGitUrl(input: String): Boolean {
        val t = input.trim()
        return t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("git@") || t.startsWith("ssh://") || t.startsWith("git://") ||
            t.endsWith(".git")
    }

    // Резолвит вход в локальную директорию: клон при git-URL, проверка при пути.
    // Клонирование — только при add; повторный add того же имени запрещён выше.
    fun resolveDir(name: String, input: String, appDir: File): File {
        val clean = sanitizeName(name)
        val t = input.trim()
        require(t.isNotEmpty()) { "пустой путь/URL" }
        if (!isGitUrl(t)) {
            val dir = File(t.replaceFirst("^~".toRegex(), System.getProperty("user.home")))
            require(dir.isDirectory) { "нет такой папки: $t" }
            return dir.canonicalFile
        }
        val dest = File(appDir, "repos/$clean")
        require(!dest.exists()) { "папка repos/$clean уже существует — используй Обновить" }
        clone(t, dest)
        return dest.canonicalFile
    }

    fun pull(dir: File): String {
        val (code, out) = runGit(dir, "pull", "--ff-only")
        if (code != 0) throw IllegalStateException("git pull: ${out.take(300)}")
        return out.take(500)
    }

    // Сканирует директорию в RawDoc-и: ТОЛЬКО документация (Task 1: README + docs).
    // Код (.kt/.java/.py/.js/.ts и т.п.) НЕ индексируется — вектора только по docs,
    // иначе эмбеддится весь проект. Тяжёлое и сгенерированное отсекаем deny-списком и лимитом размера.
    fun scan(name: String, dir: File): List<RawDoc> {
        val clean = sanitizeName(name)
        require(dir.isDirectory) { "нет такой папки: ${dir.absolutePath}" }
        val base = dir.canonicalFile
        val out = mutableListOf<RawDoc>()
        base.walkTopDown()
            .onEnter { d -> d.name !in DENY_DIRS }
            .filter { it.isFile }
            .forEach { f ->
                val rel = base.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                if (rel.split('/').any { it in DENY_DIRS }) return@forEach
                if (f.length() > MAX_FILE_BYTES || out.size >= MAX_FILES) return@forEach
                if (!isIndexable(rel)) return@forEach
                val text = try {
                    f.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    return@forEach
                }
                if (text.contains('\u0000') || text.trim().length < MIN_CHARS) return@forEach
                out += RawDoc(
                    source = prefix(clean) + rel,
                    title = "$clean/$rel",
                    text = text.take(MAX_CHARS_PER_FILE),
                )
            }
        require(out.isNotEmpty()) { "в проекте нет документации для индекса (нужны README/docs/*.md/схемы API)" }
        return out.sortedBy { it.source }
    }

    // Файлы проекта, исчезнувшие с диска после update (для чистки индекса).
    fun staleSources(name: String, dir: File, indexedSources: Collection<String>): List<String> {
        val live = try {
            scanNames(name, dir)
        } catch (_: Exception) {
            return emptyList()
        }
        val p = prefix(sanitizeName(name))
        return indexedSources.filter { it.startsWith(p) && it !in live }
    }

    private fun scanNames(name: String, dir: File): Set<String> {
        val base = dir.canonicalFile
        val p = prefix(sanitizeName(name))
        return base.walkTopDown()
            .onEnter { d -> d.name !in DENY_DIRS }
            .filter { it.isFile && isIndexable(base.toPath().relativize(it.toPath()).toString().replace('\\', '/')) }
            .map { p + base.toPath().relativize(it.toPath()).toString().replace('\\', '/') }
            .toSet()
    }

    private fun isIndexable(rel: String): Boolean {
        val low = rel.lowercase()
        val file = low.substringAfterLast('/')
        // README в корне и на любом уровне — всегда документация.
        if (file.startsWith("readme")) return true
        // Всё внутри docs/ — документация целиком (примеры кода в docs тоже берём).
        if (low == "docs" || low.startsWith("docs/") || low.contains("/docs/")) {
            val ext = file.substringAfterLast('.', "")
            if (ext.isEmpty()) return true
            if (ext in DOCS_EXTS) return true
            // В docs/ разрешаем и код-примеры, и конфиги — это всё равно доки.
            if (ext in DOCS_CODE_EXTS) return true
            return false
        }
        val ext = file.substringAfterLast('.', "")
        // Markdown/txt где угодно — тоже документация.
        if (ext == "md" || ext == "markdown" || ext == "txt") return true
        if (file in EXACT_NAMES) return true
        // Схемы API и SQL — по заданию часть документации.
        if (ext == "sql" || ext == "graphql" || ext == "gql") return true
        if (file.contains("openapi") && (ext == "yaml" || ext == "yml" || ext == "json")) return true
        return false
    }

    private fun clone(url: String, dest: File): String {
        dest.parentFile?.mkdirs()
        val pb = ProcessBuilder("git", "clone", "--depth", "1", url, dest.absolutePath)
            .redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw IllegalStateException("git clone: ${out.trim().take(300)}")
        return out.take(500)
    }

    private fun runGit(dir: File, vararg args: String): Pair<Int, String> {
        return try {
            val proc = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            code to out.trim()
        } catch (e: Exception) {
            1 to (e.message ?: "git error")
        }
    }

    private val DENY_DIRS = setOf(
        ".git", "build", ".gradle", ".idea", "out", "dist",
        "node_modules", "venv", ".venv", "__pycache__", "target", ".amper",
    )
    // Расширения документации: эмбеддим только их (вне docs/ — тем более).
    private val DOCS_EXTS = setOf(
        "md", "markdown", "txt", "yaml", "yml", "json", "toml",
        "sql", "graphql", "gql",
    )
    // Код-примеры и конфиги — только внутри docs/ (снаружи не индексируем).
    private val DOCS_CODE_EXTS = setOf(
        "kt", "kts", "java", "py", "js", "ts", "gradle", "cfg", "ini", "properties",
    )
    private val EXACT_NAMES = setOf(
        "dockerfile", "makefile", "readme",
    )

    private const val MAX_FILE_BYTES = 300_000L
    private const val MAX_CHARS_PER_FILE = 60_000
    private const val MIN_CHARS = 20
    private const val MAX_FILES = 2000
}
