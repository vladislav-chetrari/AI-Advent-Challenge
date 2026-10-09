package task3.di

import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import task3.data.RagRepository
import task3.db.RagDatabase
import task3.db.buildRagDatabase
import task3.llama.createEmbedBridge
import task3.presentation.Task3ViewModel
import task2.llama.createLlamaBridge

/**
 * DI-контейнер задачи 3. Генеративный бридж общий с задачей 2
 * (тот же LiteRT Engine), эмбеддинг-бридж — EmbeddingEngine.
 */
class Task3Container(
    val modelsDir: String,
    ragBuilder: RoomDatabase.Builder<RagDatabase>,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val genBridge = createLlamaBridge()

    private val ragDb = buildRagDatabase(ragBuilder)

    val repo = RagRepository(
        genBridge = genBridge,
        embBridge = createEmbedBridge(),
        dao = ragDb.ragDao(),
        modelsDir = modelsDir,
        scope = scope,
    )

    val viewModel = Task3ViewModel(repo, scope)
}
