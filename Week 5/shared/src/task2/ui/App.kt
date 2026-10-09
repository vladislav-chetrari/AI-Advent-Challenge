package task2.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import task2.presentation.ChatViewModel

@Composable
fun App(vm: ChatViewModel) {
    MaterialTheme {
        ChatScreen(vm)
    }
}
