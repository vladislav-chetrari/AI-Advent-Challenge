package task3.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import task3.data.EmbedCatalog
import task3.presentation.Task3Intent
import task3.presentation.Task3UiState

@Composable
fun SettingsTab(ui: Task3UiState, send: (Task3Intent) -> Unit, modifier: Modifier = Modifier) {
    val embedLabel = EmbedCatalog.resolve(ui.embedId)?.label ?: "Модель не выбрана"
    Column(
        modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Модель языка", style = MaterialTheme.typography.titleSmall)
        Card {
            SettingsRow(
                title = "LLM",
                subtitle = ui.llmLabel,
                onClick = { send(Task3Intent.LlmScreenOpen) },
            )
        }

        Text("Модель для эмбеддингов", style = MaterialTheme.typography.titleSmall)
        Card {
            SettingsRow(
                title = "Эмбеддинги",
                subtitle = embedLabel,
                onClick = { send(Task3Intent.EmbedScreenOpen) },
            )
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall)
    }
}
