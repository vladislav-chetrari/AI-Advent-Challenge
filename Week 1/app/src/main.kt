package app

import agent.Agent
import agent.AgentResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch
import java.util.UUID

data class UiMessage(val role: String, val text: String)

// Модель сессий оставлена заделом под память/старые чаты; сайдбар пока спрятан.
class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    val agent: Agent = Agent(),
    val messages: MutableList<UiMessage> = mutableStateListOf(),
)

fun main() = application {
    val windowState = rememberWindowState(width = 1100.dp, height = 750.dp)
    Window(onCloseRequest = ::exitApplication, title = "Первый агент — DeepSeek", state = windowState) {
        MaterialTheme {
            AgentApp()
        }
    }
}

@Composable
fun AgentApp() {
    val scope = rememberCoroutineScope()
    val sessions = remember { mutableStateListOf(ChatSession(title = "Чат 1")) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    val current = sessions.first()
    val listState = rememberLazyListState()

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        input = ""
        status = null
        current.messages += UiMessage("user", q)
        busy = true
        scope.launch {
            try {
                when (val r = current.agent.ask(q)) {
                    is AgentResult.Success -> current.messages += UiMessage("assistant", r.text)
                    is AgentResult.Failure -> status = r.error.userMessage
                }
            } finally {
                busy = false
            }
            try {
                listState.animateScrollToItem(maxOf(0, current.messages.size - 1))
            } catch (_: Exception) {
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // Ключ: только прокидываем в модуль агента, читает и хранит его сам агент.
        OutlinedTextField(
            value = keyInput,
            onValueChange = {
                keyInput = it
                Agent.apiKeyOverride = it.ifBlank { null }
            },
            label = { Text("DEEPSEEK_API_KEY (опционально, иначе env/.env)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        // Переписка
        Column(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(current.messages) { m ->
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
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color.Red)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Запрос агенту… (Enter — отправить, Shift+Enter — перенос)") },
                    modifier = Modifier.weight(1f)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                                send()
                                true
                            } else {
                                false
                            }
                        },
                    enabled = !busy,
                    singleLine = false,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Spacer(Modifier.width(8.dp))
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                } else {
                    Button(onClick = { send() }) { Text("Отправить") }
                }
            }
        }
    }
}
