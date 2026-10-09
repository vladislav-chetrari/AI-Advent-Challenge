package task3.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import task2.domain.ChatMessage
import task2.domain.EngineStatus
import task3.presentation.Task3Intent
import task3.presentation.Task3UiState

@Composable
fun ChatTab(ui: Task3UiState, send: (Task3Intent) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size - 1)
    }

    Column(
        modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                ui.llmLabel,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(top = 10.dp),
                maxLines = 2,
            )
            IconButton(onClick = { send(Task3Intent.ClearRequested) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Очистить чат")
            }
        }

        when (val s = ui.llmStatus) {
            is EngineStatus.Downloading -> {
                Text("Скачиваю модель: ${s.progress}%")
                LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            EngineStatus.LoadingModel -> {
                Text("Загружаю модель…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            EngineStatus.Generating -> {
                Text("Генерирую…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is EngineStatus.Error -> {
                Card { Text("Ошибка: ${s.message}", modifier = Modifier.padding(8.dp)) }
                Button(onClick = { send(Task3Intent.Retry) }) { Text("Повторить") }
            }
            EngineStatus.Idle, EngineStatus.Ready -> {
                if (!ui.canChat) {
                    Card { Text(ui.chatHint, modifier = Modifier.padding(8.dp)) }
                }
            }
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ui.messages, key = { it.id }) { m ->
                Card {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            if (m.role == ChatMessage.Role.USER) "Вы" else "Собеседник",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(if (m.text.isEmpty()) "▍" else m.text)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().imePadding(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.input,
                onValueChange = { send(Task3Intent.InputChanged(it)) },
                modifier = Modifier.weight(1f),
                enabled = ui.canChat,
                placeholder = { Text(if (ui.canChat) "Спроси что-нибудь…" else ui.chatHint) },
                maxLines = 4,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { send(Task3Intent.Send) },
                    enabled = ui.canChat,
                ) { Text("➤") }
                if (ui.llmStatus is EngineStatus.Generating) {
                    OutlinedButton(onClick = { send(Task3Intent.Cancel) }) { Text("■") }
                }
            }
        }
    }

    if (ui.showClearDialog) {
        AlertDialog(
            onDismissRequest = { send(Task3Intent.ClearDismissed) },
            title = { Text("Очистить чат?") },
            text = { Text("История переписки будет удалена без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = { send(Task3Intent.ClearConfirmed) }) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { send(Task3Intent.ClearDismissed) }) { Text("Отмена") }
            },
        )
    }
}
