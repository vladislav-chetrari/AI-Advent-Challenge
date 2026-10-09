package task3.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import task3.presentation.Task3Intent
import task3.presentation.Task3UiState

@Composable
fun DocsTab(ui: Task3UiState, send: (Task3Intent) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { send(Task3Intent.WikiOpen) },
                // Нельзя грузить, пока не настроена embedding-модель.
            ) { Icon(Icons.Filled.Add, contentDescription = "Добавить статью") }
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!ui.canAdd) {
                Card { Text(ui.docsHint, modifier = Modifier.padding(8.dp)) }
            }
            if (ui.docs.isEmpty() && ui.indexProgress.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Пока пусто — нажми + внизу и вставь ссылку на статью из Википедии",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ui.docs, key = { it.source }) { d ->
                        val progress = ui.indexProgress[d.source]
                        val busy = d.indexing || progress != null
                        Card {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) { send(Task3Intent.DocView(d.source)) }
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(d.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "чанков: ${d.chunks} · символов: ${d.chars}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                if (busy) {
                                    if (progress != null) {
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                    Text("Индексируется…", style = MaterialTheme.typography.labelSmall)
                                }
                                if (d.error != null) {
                                    Text("Ошибка: ${d.error}", style = MaterialTheme.typography.labelSmall)
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("В поиске", style = MaterialTheme.typography.labelSmall)
                                        Switch(
                                            checked = d.active,
                                            enabled = !busy,
                                            onCheckedChange = {
                                                send(Task3Intent.DocActiveToggled(d.source, it))
                                            },
                                        )
                                    }
                                    IconButton(
                                        onClick = { send(Task3Intent.DocDelete(d.source)) },
                                        enabled = !busy,
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Удалить документ")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (ui.showWikiDialog) {
        AlertDialog(
            onDismissRequest = { send(Task3Intent.WikiDismissed) },
            title = { Text("Статья из Википедии") },
            text = {
                OutlinedTextField(
                    value = ui.wikiInput,
                    onValueChange = { send(Task3Intent.WikiInputChanged(it)) },
                    placeholder = { Text("https://ru.wikipedia.org/wiki/…") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { send(Task3Intent.WikiConfirmed) }) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { send(Task3Intent.WikiDismissed) }) { Text("Отмена") }
            },
        )
    }

    if (ui.viewingSource != null) {
        AlertDialog(
            onDismissRequest = { send(Task3Intent.DocViewDismissed) },
            title = { Text(ui.docs.firstOrNull { it.source == ui.viewingSource }?.title ?: "Документ") },
            text = {
                Text(
                    ui.viewingText,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { send(Task3Intent.DocViewDismissed) }) { Text("Закрыть") }
            },
        )
    }
}
