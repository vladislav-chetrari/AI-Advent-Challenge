package com.aiadvent.task2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import task2.db.ChatDatabase
import task2.di.AppContainer
import task2.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Рисуем под системными шторками (прозрачные статус-бар и навбар),
        // отступы забирает Compose: safeDrawing на корне + imePadding у поля ввода.
        enableEdgeToEdge()
        // Наша тема светлая (MaterialTheme по умолчанию), поэтому иконки
        // статус-бара и навбара делаем тёмными, иначе их не видно на светлом фоне.
        // Если заведём тёмную тему — брать флаг из colorScheme.isLight().
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val modelsDir = File(filesDir, "models").apply { mkdirs() }.absolutePath
        val dbBuilder = Room.databaseBuilder<ChatDatabase>(
            context = applicationContext,
            name = File(modelsDir, "chat.db").absolutePath,
        ).setDriver(BundledSQLiteDriver())
        val container = AppContainer(modelsDir, dbBuilder)
        setContent {
            App(container.chatViewModel)
        }
    }
}
