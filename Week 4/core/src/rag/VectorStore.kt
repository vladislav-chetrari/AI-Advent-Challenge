package core.rag

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// Хранилище чанков + векторов в SQLite.
//
// Почему SQLite, а не FAISS:
// - FAISS — нативная C++-библиотека без Kotlin API, ломает КМП и читаемость;
// - при наших объёмах (тысячи чанков × 384 dim) exact-поиск косинуса
//   brute-force занимает миллисекунды и совпадает с FAISS FlatIP один в один;
// - вектора лежат как BLOB float32 LE (компактно, ~1.5 КБ на чанк),
//   а для поиска поднимаются в непрерывный in-memory снапшот FloatArray.
//
// Задел на масштаб: заменить search-внутрянку на HNSW/IVF/sqlite-vec можно
// не трогая интерфейс и RagService.
object ChunkTable : Table("chunk") {
    val id = text("id")
    val strategy = text("strategy")
    // имя свойства src: ColumnSet уже имеет член source
    val src = text("source")
    val title = text("title")
    val section = text("section").default("")
    val ord = integer("ord")
    val text = text("text")
    val tokens = integer("tokens")
    val embedding = binary("embedding")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

fun FloatArray.toBlob(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (x in this) buf.putFloat(x)
    return buf.array()
}

fun ByteArray.toFloatArray(): FloatArray {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(size / 4)
    for (i in out.indices) out[i] = buf.float
    return out
}

class SqliteVectorStore(dbFile: File) {
    private val db: Database

    init {
        dbFile.parentFile?.mkdirs()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        db = Database.connect(url, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.createMissingTablesAndColumns(ChunkTable) }
    }

    fun clearStrategy(strategy: String) {
        transaction(db) { ChunkTable.deleteWhere { ChunkTable.strategy eq strategy } }
    }

    // Удаление чанков конкретных документов (убрали файл из базы знаний —
    // без полной переиндексации).
    fun deleteSources(sources: Collection<String>) {
        if (sources.isEmpty()) return
        transaction(db) { ChunkTable.deleteWhere { ChunkTable.src.inList(sources.toList()) } }
    }

    fun clearAll() {
        transaction(db) { ChunkTable.deleteAll() }
    }

    fun upsert(chunks: List<Chunk>, vectors: List<FloatArray>) {
        require(chunks.size == vectors.size)
        transaction(db) {
            chunks.forEachIndexed { i, c ->
                val cId = c.id
                ChunkTable.deleteWhere { ChunkTable.id eq cId }
                ChunkTable.insert {
                    it[id] = c.id
                    it[strategy] = c.strategy
                    it[src] = c.source
                    it[title] = c.title
                    it[section] = c.section
                    it[ord] = c.ord
                    it[text] = c.text
                    it[tokens] = c.tokens
                    it[embedding] = vectors[i].toBlob()
                    it[createdAt] = System.currentTimeMillis()
                }
            }
        }
    }

    fun countByStrategy(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        transaction(db) {
            ChunkTable.selectAll().forEach { row ->
                val s = row[ChunkTable.strategy]
                out[s] = (out[s] ?: 0) + 1
            }
        }
        return out
    }

    fun loadChunks(strategy: String): List<Chunk> {
        return transaction(db) {
            ChunkTable.selectAll().where { ChunkTable.strategy eq strategy }.map { row ->
                Chunk(
                    id = row[ChunkTable.id],
                    strategy = row[ChunkTable.strategy],
                    source = row[ChunkTable.src],
                    title = row[ChunkTable.title],
                    section = row[ChunkTable.section],
                    ord = row[ChunkTable.ord],
                    text = row[ChunkTable.text],
                    tokens = row[ChunkTable.tokens],
                )
            }
        }
    }

    // Карта текст -> вектор для стратегии: инкрементальная индексация
    // переиспользует вектора неизменившихся чанков, не дёргая модель.
    fun loadTextVectors(strategy: String): Map<String, FloatArray> {
        return transaction(db) {
            val out = HashMap<String, FloatArray>()
            ChunkTable.selectAll().where { ChunkTable.strategy eq strategy }.forEach { row ->
                out.putIfAbsent(row[ChunkTable.text], row[ChunkTable.embedding].toFloatArray())
            }
            out
        }
    }

    // Точный top-K по косинусу. Все вектора нормализованы при записи,
    // поэтому косинус = скалярное произведение. Куча O(n log k).
    // onlySources ограничивает поиск активными документами базы знаний.
    suspend fun search(
        strategy: String,
        query: FloatArray,
        topK: Int,
        onlySources: Set<String>? = null,
    ): List<ScoredChunk> =
        withContext(Dispatchers.Default) {
            if (onlySources != null && onlySources.isEmpty()) return@withContext emptyList()
            val rows: List<Pair<Chunk, FloatArray>> = transaction(db) {
                val q = if (onlySources == null) {
                    ChunkTable.selectAll().where { ChunkTable.strategy eq strategy }
                } else {
                    ChunkTable.selectAll().where { (ChunkTable.strategy eq strategy) and (ChunkTable.src.inList(onlySources.toList())) }
                }
                q.map { row ->
                    Chunk(
                        id = row[ChunkTable.id],
                        strategy = row[ChunkTable.strategy],
                        source = row[ChunkTable.src],
                        title = row[ChunkTable.title],
                        section = row[ChunkTable.section],
                        ord = row[ChunkTable.ord],
                        text = row[ChunkTable.text],
                        tokens = row[ChunkTable.tokens],
                    ) to row[ChunkTable.embedding].toFloatArray()
                }
            }
            if (rows.isEmpty()) return@withContext emptyList()
            val heap = PriorityQueue<Pair<Int, Float>>(topK + 1, compareBy { it.second })
            for ((i, Jan) in rows.withIndex()) {
                val v = Jan.second
                var dot = 0f
                for (d in query.indices) dot += query[d] * v[d]
                if (heap.size < topK) {
                    heap += i to dot
                } else if (dot > heap.peek().second) {
                    heap.poll()
                    heap += i to dot
                }
            }
            heap.sortedByDescending { it.second }.map { (i, s) -> ScoredChunk(rows[i].first, s) }
        }
}
