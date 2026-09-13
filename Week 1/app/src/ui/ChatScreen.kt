package app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import agent.AgentError
import agent.domain.Branch
import agent.domain.branchPath
import agent.domain.StrategyType
import agent.domain.formatCostUsd
import ai.advent.week1.resources.Res
import ai.advent.week1.resources.branch_create
import ai.advent.week1.resources.branch_main
import ai.advent.week1.resources.branch_name_hint
import ai.advent.week1.resources.branches_label
import ai.advent.week1.resources.facts_empty
import ai.advent.week1.resources.facts_label
import ai.advent.week1.resources.compression_label
import ai.advent.week1.resources.context_fill
import ai.advent.week1.resources.err_api
import ai.advent.week1.resources.err_empty_prompt
import ai.advent.week1.resources.err_empty_response
import ai.advent.week1.resources.err_missing_key
import ai.advent.week1.resources.err_network
import ai.advent.week1.resources.err_overflow
import ai.advent.week1.resources.err_unauthorized
import ai.advent.week1.resources.input_label
import ai.advent.week1.resources.keep_n_hint_memory
import ai.advent.week1.resources.keep_n_hint_window
import ai.advent.week1.resources.keep_n_label
import ai.advent.week1.resources.message_tokens
import ai.advent.week1.resources.model_label
import ai.advent.week1.resources.role_agent
import ai.advent.week1.resources.role_user
import ai.advent.week1.resources.send
import ai.advent.week1.resources.strategy_branching
import ai.advent.week1.resources.strategy_facts
import ai.advent.week1.resources.strategy_label
import ai.advent.week1.resources.strategy_sliding
import ai.advent.week1.resources.strategy_summary
import ai.advent.week1.resources.summary_label
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
    onToggleCompression: (Boolean) -> Unit = {},
    onKeepNChange: (String) -> Unit = {},
    onSelectStrategy: (StrategyType) -> Unit = {},
    onCreateBranch: (String) -> Unit = {},
    onSwitchBranch: (String?) -> Unit = {},
    onDeleteBranch: (String) -> Unit = {},
    onRemoveFact: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        try {
            listState.animateScrollToItem(maxOf(0, state.messages.size - 1))
        } catch (_: Exception) {
        }
    }

    Column(Modifier.fillMaxSize()) {
        ModelAndTokens(state, onSelectModel, onClear)
        Spacer(Modifier.height(4.dp))
        StrategySettings(state, onSelectStrategy, onToggleCompression, onKeepNChange)
        Spacer(Modifier.height(4.dp))
        FactsPanel(state, onRemoveFact)
        Spacer(Modifier.height(4.dp))
        BranchesPanel(state, onCreateBranch, onSwitchBranch, onDeleteBranch)
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
                        // Enter с основной клавиатуры и с numpad — разные клавиши.
                        if ((event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                            event.type == KeyEventType.KeyDown && !event.isShiftPressed
                        ) {
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
private fun ModelAndTokens(state: ChatUiState, onSelectModel: (String) -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ModelDropdown(state, onSelectModel)
        Spacer(Modifier.weight(1f))
        TokenTexts(state)
        Spacer(Modifier.width(4.dp))
        // Очистка истории — крупная зона клика (44dp) с рисованной урной.
        ClearHistoryButton(enabled = !state.busy, onClear = onClear)
    }
}

@Composable
private fun ClearHistoryButton(enabled: Boolean, onClear: () -> Unit) {
    // Урна рисуется Canvas'ом: material-icons тянет чужую цепочку ui-desktop/skiko
    // и роняет run с NoSuchMethodError (см. историю), поэтому без внешних зависимостей.
    val color = if (enabled) Color(0xFFE57373) else Color.LightGray
    Box(
        Modifier.size(48.dp)
            .clickable(enabled = enabled) { onClear() }
            .padding(10.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val stroke = 0.08f * w
            // Ручка.
            drawLine(color, Offset(0.35f * w, 0.08f * h), Offset(0.65f * w, 0.08f * h), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(0.5f * w, 0.08f * h), Offset(0.5f * w, 0.17f * h), strokeWidth = stroke, cap = StrokeCap.Round)
            // Крышка.
            drawRoundRect(
                color,
                topLeft = Offset(0.16f * w, 0.19f * h),
                size = Size(0.68f * w, 0.1f * h),
                cornerRadius = CornerRadius(0.04f * w),
            )
            // Корпус — сужающаяся трапеция.
            val body = Path().apply {
                moveTo(0.25f * w, 0.33f * h)
                lineTo(0.75f * w, 0.33f * h)
                lineTo(0.66f * w, 0.95f * h)
                lineTo(0.34f * w, 0.95f * h)
                close()
            }
            drawPath(body, color)
        }
    }
}

@Composable
private fun ModelDropdown(state: ChatUiState, onSelectModel: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.models.firstOrNull { it.id == state.selectedModelId } ?: state.models.firstOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
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
private fun TokenTexts(state: ChatUiState) {
    val t = state.tokens
    val sessionQty = t.sessionTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val pct = if (t.contextLimit > 0) t.contextTokens * 100 / t.contextLimit else 0
    val color = if (pct >= 90) Color.Red else Color.Gray
    Column(horizontalAlignment = Alignment.End) {
        Text(
            stringResource(
                Res.string.tokens_spent,
                pluralStringResource(Res.plurals.tokens, sessionQty, sessionQty),
                formatCostUsd(t.sessionCostUsd),
            ),
            color = color,
            textAlign = TextAlign.End,
        )
        // Процент собираем в коде ("%3$s"), а не %% в ресурсах — иначе Compose рендерит "%%".
        Text(
            stringResource(Res.string.context_fill, t.contextTokens, t.contextLimit, "$pct%"),
            color = color,
            textAlign = TextAlign.End,
        )
    }
}

// Стратегия и её настройки — одним FlowRow: на узком окне контролы
// переносятся, а не вылезают за край. Состав зависит от стратегии:
// - N (окно) — Sliding / Facts / Summary-legacy (в Branching окна нет);
// - чекбокс сжатия — только Summary-legacy.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrategySettings(
    state: ChatUiState,
    onSelect: (StrategyType) -> Unit,
    onToggleCompression: (Boolean) -> Unit,
    onNChange: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        StrategyDropdown(state, onSelect)
        if (state.strategy != StrategyType.BRANCHING) {
            WindowNField(state, onNChange)
        }
        if (state.strategy == StrategyType.SUMMARY_LEGACY) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.compressionEnabled,
                    onCheckedChange = onToggleCompression,
                    enabled = !state.busy,
                )
                Text(stringResource(Res.string.compression_label), color = Color.Gray)
            }
        }
    }
    if (state.strategy == StrategyType.SUMMARY_LEGACY && state.summaryText.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        // Схлопнуто при смене саммари. Клик по обрезанному раскрывает полностью со скроллом.
        var expanded by remember(state.summaryText) { mutableStateOf(false) }
        val limit = 300
        val isTruncated = state.summaryText.length > limit
        val shown = if (expanded || !isTruncated) state.summaryText
        else state.summaryText.take(limit).trimEnd() + "..."
        Column(
            Modifier.fillMaxWidth()
                .clickable(enabled = isTruncated) { expanded = !expanded },
        ) {
            Text(
                stringResource(Res.string.summary_label) + " " + shown,
                color = Color.Gray,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                modifier = if (expanded) Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                else Modifier,
            )
        }
    }
}

@Composable
private fun StrategyDropdown(
    state: ChatUiState,
    onSelect: (StrategyType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (state.strategy) {
        StrategyType.SLIDING -> stringResource(Res.string.strategy_sliding)
        StrategyType.FACTS -> stringResource(Res.string.strategy_facts)
        StrategyType.BRANCHING -> stringResource(Res.string.strategy_branching)
        StrategyType.SUMMARY_LEGACY -> stringResource(Res.string.strategy_summary)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.strategy_label), color = Color.Gray)
        Spacer(Modifier.width(8.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !state.busy) {
                Text(label)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.strategy_sliding)) },
                    onClick = { expanded = false; onSelect(StrategyType.SLIDING) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.strategy_facts)) },
                    onClick = { expanded = false; onSelect(StrategyType.FACTS) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.strategy_branching)) },
                    onClick = { expanded = false; onSelect(StrategyType.BRANCHING) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.strategy_summary)) },
                    onClick = { expanded = false; onSelect(StrategyType.SUMMARY_LEGACY) },
                )
            }
        }
    }
}

@Composable
private fun WindowNField(
    state: ChatUiState,
    onNChange: (String) -> Unit,
) {
    // N в терминах агента — размер окна: сколько последних живых сообщений уходит
    // в каждый запрос к модели. В Summary-legacy то же N задаёт память целиком:
    // держатся последние N−1 сообщений + саммари.
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.keep_n_label), color = Color.Gray)
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = state.keepLastN.toString(),
                onValueChange = onNChange,
                // N задаётся на пустой чат; при непустой истории поле залочено.
                enabled = !state.busy && state.messages.isEmpty(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(90.dp),
            )
        }
        Text(
            stringResource(
                if (state.strategy == StrategyType.SUMMARY_LEGACY) Res.string.keep_n_hint_memory
                else Res.string.keep_n_hint_window,
            ),
            color = Color.Gray,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun FactsPanel(
    state: ChatUiState,
    onRemove: (String) -> Unit,
) {
    // Показываем только в режиме FACTS, иначе панель шумит.
    // По умолчанию схлопнуто в одну строку — фактов бывает много.
    if (state.strategy != StrategyType.FACTS) return
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.facts_label) + " (${state.facts.size})",
                color = Color.Gray,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "−" else "+", color = Color.Gray)
        }
        if (!expanded) return@Column
        if (state.facts.isEmpty()) {
            Text(stringResource(Res.string.facts_empty), color = Color.Gray)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
            ) {
                state.facts.toSortedMap().forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("$k: $v", color = Color.Gray, modifier = Modifier.weight(1f))
                        if (!state.busy) {
                            Text(
                                "×",
                                color = Color.Gray,
                                modifier = Modifier.padding(horizontal = 8.dp).clickable { onRemove(k) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BranchesPanel(
    state: ChatUiState,
    onCreate: (String) -> Unit,
    onSwitch: (String?) -> Unit,
    onDelete: (String) -> Unit,
) {
    // Показываем только в режиме BRANCHING.
    if (state.strategy != StrategyType.BRANCHING) return
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.branches_label), color = Color.Gray)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.branch_name_hint)) },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onCreate(name); name = "" },
                enabled = !state.busy,
            ) { Text(stringResource(Res.string.branch_create)) }
        }
        Spacer(Modifier.height(4.dp))
        BranchChips(state, onSwitch, onDelete)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchChips(
    state: ChatUiState,
    onSwitch: (String?) -> Unit,
    onDelete: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        BranchChip(
            text = stringResource(Res.string.branch_main),
            selected = state.activeBranchId == null,
            enabled = !state.busy,
            onClick = { onSwitch(null) },
            onDelete = null,
        )
        state.branches.forEach { b: Branch ->
            BranchChip(
                // Путь в графе через слеш: из ветки можно форкать дальше.
                text = branchPath(state.branches, b),
                selected = state.activeBranchId == b.id,
                enabled = !state.busy,
                onClick = { onSwitch(b.id) },
                onDelete = { onDelete(b.id) },
            )
        }
    }
}

@Composable
private fun BranchChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val bg = if (selected) Color(0xFFBBDEFB) else Color(0xFFF5F5F5)
    Box(
        Modifier.background(bg).clickable(enabled = enabled) { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Color.DarkGray)
            // Крестик удаления — на всех ветках кроме main (у него onDelete=null).
            if (onDelete != null && enabled) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "×",
                    color = Color.DarkGray,
                    modifier = Modifier.clickable { onDelete() },
                )
            }
        }
    }
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
