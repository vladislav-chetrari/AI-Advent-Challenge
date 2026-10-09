package task2.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import task2.db.ChatDatabase
import task2.di.AppContainer
import task2.ui.App

fun main() {
    val modelsDir = File("models").apply { mkdirs() }.absolutePath
    val dbBuilder = Room.databaseBuilder<ChatDatabase>(
        name = File(modelsDir, "chat.db").absolutePath,
    ).setDriver(BundledSQLiteDriver())
    val container = AppContainer(modelsDir, dbBuilder)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "AI Advent — Offline LLM (desktop preview)",
            state = rememberWindowState(),
        ) {
            App(container.chatViewModel)
        }
    }
}
