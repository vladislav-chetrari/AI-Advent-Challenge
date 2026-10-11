package core.rag

import java.sql.DriverManager

/**
 * Хранилище RAG-индекса (SQLite через plain JDBC, без Room/KSP).
 * Две таблицы: documents + chunks (вектор — BLOB float32 LE).
 * Все методы синхронные и synchronized (одно соединение);
 * вызывающий оборачивает в withContext(Dispatchers.IO).
 */
class RagStore(dbPath: String) {
    private val conn = run {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$dbPath").also {
            it.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute(
                    "CREATE TABLE IF NOT EXISTS documents(" +
                        "source TEXT PRIMARY KEY, title TEXT NOT NULL, " +
                        "active INTEGER NOT NULL DEFAULT 1, updatedAt INTEGER NOT NULL)"
                )
                st.execute(
                    "CREATE TABLE IF NOT EXISTS chunks(" +
                        "id TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL, " +
                        "section TEXT NOT NULL DEFAULT '', ord INTEGER NOT NULL, " +
                        "text TEXT NOT NULL, dim INTEGER NOT NULL, embedding BLOB NOT NULL)"
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_chunks_source ON chunks(source)")
            }
        }
    }

    @Synchronized
    fun upsertDoc(source: String, title: String, active: Boolean = true) {
        conn.prepareStatement(
            "INSERT INTO documents(source,title,active,updatedAt) VALUES(?,?,?,?) " +
                "ON CONFLICT(source) DO UPDATE SET title=excluded.title, updatedAt=excluded.updatedAt"
        ).use { ps ->
            ps.setString(1, source)
            ps.setString(2, title)
            ps.setInt(3, if (active) 1 else 0)
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun allDocs(): List<StoredDoc> {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT source,title,active FROM documents ORDER BY title ASC").use { rs ->
                val out = mutableListOf<StoredDoc>()
                while (rs.next()) {
                    out += StoredDoc(rs.getString(1), rs.getString(2), rs.getInt(3) != 0)
                }
                return out
            }
        }
    }

    @Synchronized
    fun deleteDoc(source: String) {
        conn.prepareStatement("DELETE FROM chunks WHERE source=?").use {
            it.setString(1, source)
            it.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM documents WHERE source=?").use {
            it.setString(1, source)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun replaceChunks(source: String, items: List<StoredChunk>) {
        conn.prepareStatement("DELETE FROM chunks WHERE source=?").use {
            it.setString(1, source)
            it.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT OR REPLACE INTO chunks(id,source,title,section,ord,text,dim,embedding) " +
                "VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            for (c in items) {
                ps.setString(1, c.id)
                ps.setString(2, c.source)
                ps.setString(3, c.title)
                ps.setString(4, c.section)
                ps.setInt(5, c.ord)
                ps.setString(6, c.text)
                ps.setInt(7, c.dim)
                ps.setBytes(8, c.embedding.toBlob())
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    @Synchronized
    fun activeChunks(dim: Int): List<StoredChunk> {
        conn.prepareStatement(
            "SELECT c.id,c.source,c.title,c.section,c.ord,c.text,c.dim,c.embedding " +
                "FROM chunks c JOIN documents d ON d.source=c.source " +
                "WHERE d.active=1 AND c.dim=? ORDER BY c.source ASC, c.ord ASC"
        ).use { ps ->
            ps.setInt(1, dim)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<StoredChunk>()
                while (rs.next()) {
                    out += StoredChunk(
                        id = rs.getString(1), source = rs.getString(2),
                        title = rs.getString(3), section = rs.getString(4),
                        ord = rs.getInt(5), text = rs.getString(6),
                        dim = rs.getInt(7), embedding = rs.getBytes(8).toFloatArray(),
                    )
                }
                return out
            }
        }
    }

    /** Переиспользование векторов неизменившихся текстов (экономим вызовы embed). */
    @Synchronized
    fun findVectorByText(text: String, dim: Int): FloatArray? {
        conn.prepareStatement("SELECT embedding,dim FROM chunks WHERE text=? LIMIT 1").use { ps ->
            ps.setString(1, text)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                if (rs.getInt(2) != dim) return null
                return rs.getBytes(1).toFloatArray()
            }
        }
    }

    @Synchronized
    fun docStats(source: String): Pair<Int, Long> {
        conn.prepareStatement("SELECT COUNT(*), COALESCE(SUM(LENGTH(text)),0) FROM chunks WHERE source=?").use { ps ->
            ps.setString(1, source)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1) to rs.getLong(2)
            }
        }
    }

    @Synchronized
    fun close() = conn.close()
}
