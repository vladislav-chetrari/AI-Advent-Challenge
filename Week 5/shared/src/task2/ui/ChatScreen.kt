package task2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import task2.data.ModelCatalog
import task2.domain.ChatMessage
import task2.domain.EngineStatus
import task2.presentation.ChatIntent
import task2.presentation.ChatViewModel

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size - 1)
    }

    // Писать можно только когда модель готова (или уже идёт генерация).
    // Иначе очередь запросов упиралась бы в скачивание/загрузку модели.
    val canChat = ui.status is EngineStatus.Ready || ui.status is EngineStatus.Generating

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.weight(1f)) {
                Text(ui.model.label, maxLines = 1)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ModelCatalog.ALL.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.label) },
                        onClick = {
                            menu = false
                            vm.sendIntent(ChatIntent.ModelSelected(m))
                        },
                    )
                }
            }
            OutlinedButton(onClick = { vm.sendIntent(ChatIntent.Clear) }) { Text("Очистить") }
        }

        Text(
            "Offline • LiteRT • ${ui.model.id} • без облака",
            style = MaterialTheme.typography.labelSmall,
        )

        when (val s = ui.status) {
            is EngineStatus.Downloading -> {
                Text("Скачиваю модель ${ui.model.fileName}: ${s.progress}% (~${ui.model.sizeMb} МБ)")
                LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            EngineStatus.LoadingModel -> {
                Text("Загружаю модель в LiteRT…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            EngineStatus.Generating -> {
                Text("Генерирую на устройстве…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is EngineStatus.Error -> {
                Card { Text("Ошибка: ${s.message}", modifier = Modifier.padding(8.dp)) }
                Button(onClick = { vm.sendIntent(ChatIntent.Retry) }) { Text("Повторить") }
            }
            else -> {}
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ui.messages, key = { it.id }) { m ->
                Card {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            if (m.role == ChatMessage.Role.USER) "Вы" else "Собеседник (on-device)",
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
                onValueChange = { vm.sendIntent(ChatIntent.InputChanged(it)) },
                modifier = Modifier.weight(1f),
                enabled = canChat,
                placeholder = { Text(if (canChat) "Спроси что-нибудь…" else "Дождись загрузки модели…") },
                maxLines = 4,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { vm.sendIntent(ChatIntent.Send) },
                    enabled = canChat,
                ) { Text("➤") }
                if (ui.status is EngineStatus.Generating) {
                    OutlinedButton(onClick = { vm.sendIntent(ChatIntent.Cancel) }) { Text("■") }
                }
            }
        }
    }
}
