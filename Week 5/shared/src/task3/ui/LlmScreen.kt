package task3.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import task2.data.ModelCatalog
import task2.domain.EngineStatus
import task3.domain.LlmChoice
import task3.presentation.Task3Intent
import task3.presentation.Task3UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmScreen(ui: Task3UiState, send: (Task3Intent) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Модель языка") },
                navigationIcon = {
                    IconButton(onClick = { send(Task3Intent.LlmScreenClosed) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Локальные (on-device)", style = MaterialTheme.typography.titleSmall)
            ModelCatalog.ALL.forEach { m ->
                val downloaded = m.id in ui.downloadedLlm
                val progress = ui.downloading[m.id]
                val selected = (ui.llmChoice as? LlmChoice.Local)?.id == m.id
                Card {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    if (downloaded) send(Task3Intent.LlmSelected(LlmChoice.Local(m.id)))
                                    else send(Task3Intent.LlmDownload(m))
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(m.label)
                                Text(
                                    "${m.sizeMb} МБ" + if (downloaded) " · скачана" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (progress != null) {
                            val percent = progress.percent
                            if (percent != null) {
                                LinearProgressIndicator(
                                    progress = { percent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("Скачиваю: $percent%", style = MaterialTheme.typography.labelSmall)
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    "Скачиваю: ${progress.doneMb} МБ…",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        } else if (!downloaded) {
                            Button(onClick = { send(Task3Intent.LlmDownload(m)) }) {
                                Text("Скачать")
                            }
                        } else {
                            TextButton(onClick = { send(Task3Intent.LlmDelete(m)) }) {
                                Text("Удалить файл")
                            }
                        }
                        if (selected) {
                            // Аналогично EmbedScreen: чужой Downloading здесь не показываем.
                            val st = when (val s = ui.llmStatus) {
                                EngineStatus.Ready -> "Загружена, готова"
                                EngineStatus.LoadingModel -> "Загружаю…"
                                is EngineStatus.Downloading -> if (progress != null) "Скачиваю: ${s.progress}%" else null
                                is EngineStatus.Error -> "Ошибка: ${s.message}"
                                else -> "Выбрана"
                            }
                            if (st != null) Text(st, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Text("Облачные", style = MaterialTheme.typography.titleSmall)
            Card {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = ui.llmChoice is LlmChoice.DeepSeek,
                            onClick = {
                                send(Task3Intent.LlmSelected(LlmChoice.DeepSeek))
                                if (!ui.hasDeepSeekKey) send(Task3Intent.KeyDialogOpen)
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text("DeepSeek Chat")
                            Text(
                                if (ui.hasDeepSeekKey) "API-ключ задан" else "Нужен API-ключ",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { send(Task3Intent.KeyDialogOpen) }) {
                            Text(if (ui.hasDeepSeekKey) "Сменить ключ" else "Ввести ключ")
                        }
                        if (ui.hasDeepSeekKey) {
                            TextButton(onClick = { send(Task3Intent.KeyDeleted) }) {
                                Text("Убрать ключ")
                            }
                        }
                    }
                }
            }
        }
    }
}
