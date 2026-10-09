package task2

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import task2.db.ChatDatabase
import task2.db.FactEntity
import task2.db.MIGRATION_1_2
import task2.db.MessageEntity

/**
 * Схема v1 — какой БД была бы до миграции: facts БЕЗ колонки source.
 * Лежит в test@jvm (нужен java.io для tmp-файла), KSP генерирует Impl
 * только для этой тестовой БД — прод-код её не видит.
 */
@Entity(tableName = "messages")
data class MessageV1(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val createdAt: Long,
)

@Entity(tableName = "facts")
data class FactV1(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
)

@Dao
interface V1Dao {
    @Insert
    suspend fun insertMessages(items: List<MessageV1>)

    @Insert
    suspend fun insertFacts(items: List<FactV1>)
}

@Database(entities = [MessageV1::class, FactV1::class], version = 1)
abstract class ChatDatabaseV1 : RoomDatabase() {
    abstract fun dao(): V1Dao
}

class DbTest {
    private fun tmpDb(): File =
        File.createTempFile("chat-test", ".db").also { it.deleteOnExit() }

    @Test
    fun daoCrud() = runBlocking {
        val db = Room.databaseBuilder<ChatDatabase>(name = tmpDb().absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val dao = db.chatDao()
            dao.insertMessage(MessageEntity(role = "USER", text = "hi", createdAt = 1))
            dao.insertMessage(MessageEntity(role = "ASSISTANT", text = "hello", createdAt = 2))
            assertEquals(listOf("hi", "hello"), dao.getMessages().map { it.text })

            dao.insertFacts(
                (1..5).map { FactEntity(text = "fact-$it", createdAt = it.toLong()) },
            )
            dao.trimFacts(3)
            assertEquals(listOf("fact-3", "fact-4", "fact-5"), dao.getFacts().map { it.text })

            dao.clearMessages()
            assertTrue(dao.getMessages().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate1to2KeepsDataAndDefaultsSource() = runBlocking {
        val path = tmpDb().absolutePath
        val v1 = Room.databaseBuilder<ChatDatabaseV1>(name = path)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            v1.dao().insertMessages(
                listOf(MessageV1(role = "USER", text = "hi", createdAt = 1)),
            )
            v1.dao().insertFacts(
                listOf(FactV1(text = "Имя — Влад", createdAt = 2)),
            )
        } finally {
            v1.close()
        }
        val v2 = Room.databaseBuilder<ChatDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_1_2)
            .build()
        try {
            val dao = v2.chatDao()
            assertEquals(listOf("hi"), dao.getMessages().map { it.text })
            val facts = dao.getFacts()
            assertEquals(1, facts.size)
            assertEquals("Имя — Влад", facts[0].text)
            assertEquals("rules", facts[0].source)
        } finally {
            v2.close()
        }
    }
}
