package task2.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import task3.db.RagDatabase
import task3.di.Task3Container
import task3.presentation.Task3Intent
import task3.ui.App

fun main() {
    val modelsDir = File("models").apply { mkdirs() }.absolutePath
    val ragBuilder = Room.databaseBuilder<RagDatabase>(
        name = File(modelsDir, "rag.db").absolutePath,
    ).setDriver(BundledSQLiteDriver())
    val container = Task3Container(modelsDir, ragBuilder)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "AI Advent — Offline RAG (desktop preview)",
            state = rememberWindowState(),
            // Десктоп-аналог системного назад: Esc на внутренних экранах.
            onKeyEvent = {
                if (it.key == Key.Escape) {
                    val ui = container.viewModel.ui.value
                    when {
                        ui.showLlmScreen -> {
                            container.viewModel.sendIntent(Task3Intent.LlmScreenClosed)
                            true
                        }
                        ui.showEmbedScreen -> {
                            container.viewModel.sendIntent(Task3Intent.EmbedScreenClosed)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        ) {
            App(container.viewModel)
        }
    }
}
