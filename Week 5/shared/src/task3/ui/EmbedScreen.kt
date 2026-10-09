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
import task3.data.EmbedCatalog
import task3.data.EmbedState
import task3.presentation.Task3Intent
import task3.presentation.Task3UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbedScreen(ui: Task3UiState, send: (Task3Intent) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Модель для эмбеддингов") },
                navigationIcon = {
                    IconButton(onClick = { send(Task3Intent.EmbedScreenClosed) }) {
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
            Text(
                "Смена модели очищает индекс документов — статьи придётся добавить заново.",
                style = MaterialTheme.typography.labelSmall,
            )
            EmbedCatalog.ALL.forEach { m ->
                val downloaded = m.id in ui.downloadedEmbed
                val progress = ui.downloading[m.id]
                val selected = ui.embedId == m.id
                Card {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    if (downloaded) send(Task3Intent.EmbedSelected(m.id))
                                    else send(Task3Intent.EmbedDownload(m))
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(m.label)
                                Text(
                                    "${m.sizeMb} МБ · dim ${m.dim}" +
                                        if (downloaded) " · скачана" else "",
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
                            Button(onClick = { send(Task3Intent.EmbedDownload(m)) }) {
                                Text("Скачать")
                            }
                        } else {
                            TextButton(onClick = { send(Task3Intent.EmbedDelete(m)) }) {
                                Text("Удалить файл")
                            }
                        }
                        if (selected) {
                            // Downloading показываем только если качается именно
                            // эта карточка (progress != null), иначе глобальный
                            // статус чужой докачки светится здесь.
                            val st = when (val e = ui.embedStatus) {
                                EmbedState.Ready -> "Загружена, готова"
                                EmbedState.Loading -> "Загружаю…"
                                is EmbedState.Downloading -> if (progress != null) "Скачиваю: ${e.progress}%" else null
                                is EmbedState.Error -> "Ошибка: ${e.message}"
                                EmbedState.Unset -> "Выбрана"
                            }
                            if (st != null) Text(st, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
