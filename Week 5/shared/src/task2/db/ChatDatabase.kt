package task2.db

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Локальная БД чата (Room KMP — то, что рекомендовано для KMP-приложений).
 * Файл: modelsDir/chat.db. Таблица messages переживает перезапуск,
 * facts заменила собой memory.json.
 */
@Entity(tableName = "messages")
data class MessageEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	/** "USER" | "ASSISTANT" */
	val role: String,
	val text: String,
	val createdAt: Long,
)

@Entity(tableName = "facts")
data class FactEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val text: String,
	val createdAt: Long,
	/** Откуда факт: "rules" (детерминированные правила) — колонка добавлена миграцией v1→v2. */
	val source: String = "rules",
)

@Dao
interface ChatDao {
	@Query("SELECT * FROM messages ORDER BY id ASC")
	suspend fun getMessages(): List<MessageEntity>

	@Insert
	suspend fun insertMessage(msg: MessageEntity): Long

	@Query("DELETE FROM messages")
	suspend fun clearMessages()

	@Query("SELECT * FROM facts ORDER BY id ASC")
	suspend fun getFacts(): List<FactEntity>

	@Insert
	suspend fun insertFacts(items: List<FactEntity>)

	@Query("DELETE FROM facts WHERE id NOT IN (SELECT id FROM facts ORDER BY id DESC LIMIT :keep)")
	suspend fun trimFacts(keep: Int)

	@Query("DELETE FROM facts")
	suspend fun clearFacts()
}

@Database(entities = [MessageEntity::class, FactEntity::class], version = 2)
abstract class ChatDatabase : RoomDatabase() {
	abstract fun chatDao(): ChatDao
}

/**
 * Версионная миграция v1 → v2 (аналог Flyway-скрипта V2__add_fact_source.sql:
 * в Flyway для Android нет JDBC-драйвера, поэтому миграции — средствами Room).
 * v1: facts(id, text, createdAt). v2: + source TEXT NOT NULL DEFAULT 'rules'.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
	override fun migrate(connection: SQLiteConnection) {
		connection.execSQL("ALTER TABLE `facts` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'rules'")
	}
}

/** Сборка БД из платформенного билдера + накат миграций. Деструктивных миграций нет. */
fun buildChatDatabase(builder: RoomDatabase.Builder<ChatDatabase>): ChatDatabase =
	builder.addMigrations(MIGRATION_1_2).build()
