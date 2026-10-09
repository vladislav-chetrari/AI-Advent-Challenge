package task3.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import task3.presentation.Tab
import task3.presentation.Task3Intent
import task3.presentation.Task3ViewModel

@Composable
fun Root(vm: Task3ViewModel) {
    val ui by vm.ui.collectAsState()
    val send = vm::sendIntent

    if (ui.showLlmScreen) {
        LlmScreen(ui, send)
    } else if (ui.showEmbedScreen) {
        EmbedScreen(ui, send)
    } else {
        // С открытой клавиатурой нижняя навигация только мешает.
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Scaffold(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!imeVisible) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = ui.tab == Tab.CHAT,
                            onClick = { send(Task3Intent.TabSelected(Tab.CHAT)) },
                            icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Чат") },
                            label = { Text("Чат") },
                        )
                        NavigationBarItem(
                            selected = ui.tab == Tab.DOCS,
                            onClick = { send(Task3Intent.TabSelected(Tab.DOCS)) },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Документы") },
                            label = { Text("Документы") },
                        )
                        NavigationBarItem(
                            selected = ui.tab == Tab.SETTINGS,
                            onClick = { send(Task3Intent.TabSelected(Tab.SETTINGS)) },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = "Настройки") },
                            label = { Text("Настройки") },
                        )
                    }
                }
            },
        ) { inner ->
            when (ui.tab) {
                Tab.CHAT -> ChatTab(ui, send, Modifier.padding(inner))
                Tab.DOCS -> DocsTab(ui, send, Modifier.padding(inner))
                Tab.SETTINGS -> SettingsTab(ui, send, Modifier.padding(inner))
            }
        }
    }

    if (ui.showKeyDialog) {
        AlertDialog(
            onDismissRequest = { send(Task3Intent.KeyDialogDismissed) },
            title = { Text("API-ключ DeepSeek") },
            text = {
                OutlinedTextField(
                    value = ui.keyInput,
                    onValueChange = { send(Task3Intent.KeyInputChanged(it)) },
                    placeholder = { Text("sk-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { send(Task3Intent.KeySaved) }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { send(Task3Intent.KeyDialogDismissed) }) { Text("Отмена") }
            },
        )
    }
}
