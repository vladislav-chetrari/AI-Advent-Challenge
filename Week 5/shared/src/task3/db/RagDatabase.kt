package task3.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * БД RAG (файл rag.db, отдельно от chat.db задачи 2).
 * chunks: один индекс (стратегия structure), вектор — BLOB float32 LE.
 */
@Entity(tableName = "chunks")
data class ChunkEntity(
    @PrimaryKey val id: String,
    val source: String,
    val title: String,
    val section: String,
    val ord: Int,
    val text: String,
    val dim: Int,
    val embedding: ByteArray,
)

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val source: String,
    val title: String,
    val status: String,
    val error: String = "",
    val active: Boolean = true,
    val updatedAt: Long,
)

@Entity(tableName = "messages")
data class RagMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val createdAt: Long,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface RagDao {
    // --- чанки ---
    @Upsert
    suspend fun upsertChunks(items: List<ChunkEntity>)

    @Query("SELECT * FROM chunks WHERE source = :source ORDER BY ord ASC")
    suspend fun chunksOf(source: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks ORDER BY source ASC, ord ASC")
    suspend fun allChunks(): List<ChunkEntity>

    @Query("SELECT text, embedding, dim FROM chunks WHERE text = :text LIMIT 1")
    suspend fun findVectorByText(text: String): ChunkVectorRow?

    @Query("DELETE FROM chunks WHERE source IN (:sources)")
    suspend fun deleteChunksOf(sources: List<String>)

    @Query("DELETE FROM chunks")
    suspend fun clearChunks()

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun chunkCount(): Int

    // --- документы ---
    @Upsert
    suspend fun upsertDoc(doc: DocumentEntity)

    @Query("SELECT * FROM documents ORDER BY title ASC")
    suspend fun allDocs(): List<DocumentEntity>

    @Query("DELETE FROM documents WHERE source IN (:sources)")
    suspend fun deleteDocs(sources: List<String>)

    // --- история ---
    @Query("SELECT * FROM messages ORDER BY id ASC")
    suspend fun getMessages(): List<RagMessageEntity>

    @Insert
    suspend fun insertMessage(msg: RagMessageEntity): Long

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    // --- настройки ---
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun getSetting(key: String): String?

    @Upsert
    suspend fun setSetting(item: SettingEntity)
}

data class ChunkVectorRow(
    val text: String,
    val embedding: ByteArray,
    val dim: Int,
)

@Database(
    entities = [ChunkEntity::class, DocumentEntity::class, RagMessageEntity::class, SettingEntity::class],
    version = 1,
)
abstract class RagDatabase : RoomDatabase() {
    abstract fun ragDao(): RagDao
}

fun buildRagDatabase(builder: RoomDatabase.Builder<RagDatabase>): RagDatabase = builder.build()
