package app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown

// Тупая вьюха: рисует state, события уходят наверх. Никакой логики сети/БД.
@Composable
fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        try {
            listState.animateScrollToItem(maxOf(0, state.messages.size - 1))
        } catch (_: Exception) {
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("История сохраняется в SQLite и переживает рестарт", color = Color.Gray)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClear, enabled = !state.busy) { Text("Очистить") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.messages) { m ->
                val bg = if (m.role == "user") Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
                Box(Modifier.fillMaxWidth().background(bg).padding(10.dp)) {
                    Column {
                        Text(if (m.role == "user") "Вы" else "Агент", color = Color.Gray)
                        if (m.role == "assistant") {
                            Markdown(m.text)
                        } else {
                            Text(m.text)
                        }
                    }
                }
            }
        }
        state.status?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color.Red)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                label = { Text("Запрос агенту… (Enter — отправить, Shift+Enter — перенос)") },
                modifier = Modifier.weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    },
                enabled = !state.busy,
                singleLine = false,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Spacer(Modifier.width(8.dp))
            if (state.busy) {
                CircularProgressIndicator(Modifier.padding(8.dp))
            } else {
                Button(onClick = onSend) { Text("Отправить") }
            }
        }
    }
}
