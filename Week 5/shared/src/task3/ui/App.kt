package task3.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import task3.presentation.Task3ViewModel

@Composable
fun App(vm: Task3ViewModel) {
    MaterialTheme {
        Root(vm)
    }
}
