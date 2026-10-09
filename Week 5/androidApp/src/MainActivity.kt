package com.aiadvent.task2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import task3.db.RagDatabase
import task3.di.Task3Container
import task3.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val modelsDir = File(filesDir, "models").apply { mkdirs() }.absolutePath
        val ragBuilder = Room.databaseBuilder<RagDatabase>(
            context = applicationContext,
            name = File(modelsDir, "rag.db").absolutePath,
        ).setDriver(BundledSQLiteDriver())
        val container = Task3Container(modelsDir, ragBuilder)
        // Системный назад на внутренних экранах (LLM/эмбеддинги) —
        // возврат в настройки, а не выход из приложения.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val ui = container.viewModel.ui.value
                when {
                    ui.showLlmScreen -> container.viewModel.sendIntent(task3.presentation.Task3Intent.LlmScreenClosed)
                    ui.showEmbedScreen -> container.viewModel.sendIntent(task3.presentation.Task3Intent.EmbedScreenClosed)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        setContent {
            App(container.viewModel)
        }
    }
}
