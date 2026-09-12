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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import agent.AgentError
import agent.domain.formatCostUsd
import ai.advent.week1.resources.Res
import ai.advent.week1.resources.clear
import ai.advent.week1.resources.context_fill
import ai.advent.week1.resources.err_api
import ai.advent.week1.resources.err_empty_prompt
import ai.advent.week1.resources.err_empty_response
import ai.advent.week1.resources.err_missing_key
import ai.advent.week1.resources.err_network
import ai.advent.week1.resources.err_overflow
import ai.advent.week1.resources.err_unauthorized
import ai.advent.week1.resources.history_hint
import ai.advent.week1.resources.input_label
import ai.advent.week1.resources.message_tokens
import ai.advent.week1.resources.model_label
import ai.advent.week1.resources.role_agent
import ai.advent.week1.resources.role_user
import ai.advent.week1.resources.send
import ai.advent.week1.resources.tokens
import ai.advent.week1.resources.tokens_spent
import com.mikepenz.markdown.m3.Markdown
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Тупая вьюха: рисует state, события уходят наверх. Никакой логики сети/БД.
// Лимит контекста берётся из текущей модели (DeepSeek 64k / TinyLlama 2k).

// Тупая вьюха: рисует state, события уходят наверх. Никакой логики сети/БД.
@Composable
fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    onSelectModel: (String) -> Unit,
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
            Text(stringResource(Res.string.history_hint), color = Color.Gray)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClear, enabled = !state.busy) { Text(stringResource(Res.string.clear)) }
        }
        Spacer(Modifier.height(4.dp))
        ModelSelector(state, onSelectModel)
        Spacer(Modifier.height(4.dp))
        TokenPanel(state)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.messages) { m ->
                val isUser = m.role == "user"
                val bg = if (isUser) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
                val textAlign = if (isUser) TextAlign.End else TextAlign.Start
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                ) {
                    Box(Modifier.fillMaxWidth(0.85f).background(bg).padding(10.dp)) {
                        Column {
                            Text(
                                if (isUser) stringResource(Res.string.role_user)
                                else stringResource(Res.string.role_agent),
                                color = Color.Gray,
                                textAlign = textAlign,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (m.role == "assistant") {
                                Markdown(m.text)
                            } else {
                                Text(m.text, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                            }
                            // Токены user появляются только после ответа ассистента; до этого не показываем.
                            if (m.tokens > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(
                                        Res.string.message_tokens,
                                        pluralStringResource(Res.plurals.tokens, m.tokens, m.tokens),
                                        formatCostUsd(m.costUsd),
                                    ),
                                    color = Color.Gray,
                                    textAlign = textAlign,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
        state.status?.let {
            Spacer(Modifier.height(6.dp))
            Text(errorMessage(it), color = Color.Red)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                label = { Text(stringResource(Res.string.input_label)) },
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
                Button(onClick = onSend) { Text(stringResource(Res.string.send)) }
            }
        }
    }
}

@Composable
private fun ModelSelector(state: ChatUiState, onSelectModel: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.models.firstOrNull { it.id == state.selectedModelId } ?: state.models.firstOrNull()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.model_label), color = Color.Gray)
        Spacer(Modifier.width(8.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !state.busy) {
                Text(selected?.label ?: "")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.label) },
                        onClick = {
                            expanded = false
                            onSelectModel(m.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TokenPanel(state: ChatUiState) {
    val t = state.tokens
    val sessionQty = t.sessionTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val pct = if (t.contextLimit > 0) t.contextTokens * 100 / t.contextLimit else 0
    val color = if (pct >= 90) Color.Red else Color.Gray
    Text(
        stringResource(
            Res.string.tokens_spent,
            pluralStringResource(Res.plurals.tokens, sessionQty, sessionQty),
            formatCostUsd(t.sessionCostUsd),
        ),
        color = color,
    )
    Text(
        stringResource(Res.string.context_fill, t.contextTokens, t.contextLimit, pct),
        color = color,
    )
}

@Composable
private fun errorMessage(error: AgentError): String = when (error) {
    AgentError.MissingKey -> stringResource(Res.string.err_missing_key)
    AgentError.Unauthorized -> stringResource(Res.string.err_unauthorized)
    is AgentError.ContextOverflow ->
        if (error.detail.isBlank()) stringResource(Res.string.err_overflow)
        else stringResource(Res.string.err_overflow) + "\n" + error.detail
    AgentError.EmptyResponse -> stringResource(Res.string.err_empty_response)
    AgentError.EmptyPrompt -> stringResource(Res.string.err_empty_prompt)
    is AgentError.Network -> stringResource(Res.string.err_network, error.detail)
    is AgentError.Api -> stringResource(Res.string.err_api, error.detail)
}
