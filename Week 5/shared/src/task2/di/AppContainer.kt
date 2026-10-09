package task2.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.room.RoomDatabase
import task2.data.ChatRepositoryImpl
import task2.data.RoomMemoryStore
import task2.db.ChatDatabase
import task2.db.buildChatDatabase
import task2.domain.ChatRepository
import task2.llama.createLlamaBridge
import task2.presentation.ChatViewModel

/**
 * Ручной DI-контейнер (без Hilt — KISS для одного экрана).
 * БД создаёт платформенный entry-point (ему доступен Context/путь),
 * сюда приезжает уже готовый билдер — дальше всё общее.
 */
class AppContainer(
    val modelsDir: String,
    dbBuilder: RoomDatabase.Builder<ChatDatabase>,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val db = buildChatDatabase(dbBuilder)
    val repo: ChatRepository = ChatRepositoryImpl(
        bridge = createLlamaBridge(),
        memory = RoomMemoryStore(db.chatDao()),
        dao = db.chatDao(),
    )
    val chatViewModel = ChatViewModel(repo, scope, modelsDir)
}
