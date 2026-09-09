package app

import agent.Agent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.ui.ChatScreen
import app.ui.ChatViewModel

fun main() = application {
    val windowState = rememberWindowState(width = 1100.dp, height = 750.dp)
    Window(onCloseRequest = ::exitApplication, title = "Первый агент — DeepSeek + SQLite", state = windowState) {
        MaterialTheme {
            AgentApp()
        }
    }
}

// Тонкий composition root: создаёт Agent (SQLite+Flyway внутри) и ViewModel, дальше только Screen.
@Composable
fun AgentApp() {
    val scope = rememberCoroutineScope()
    // Agent сам открывает ~/.ai-advent-week1/chat.db и прогоняет Flyway-миграции.
    val agent = remember { Agent() }
    val vm = remember(agent, scope) { ChatViewModel(agent, scope) }
    var keyInput by remember { mutableStateOf("") }
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
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
        ChatScreen(
            state = state,
            onInputChange = vm::onInputChange,
            onSend = vm::send,
            onClear = vm::clearHistory,
        )
    }
}
