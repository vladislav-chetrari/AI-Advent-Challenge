package desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mikepenz.markdown.m3.Markdown
import kotlin.math.roundToInt
import core.domain.Chat
import core.domain.MemoryDoc
import core.domain.Scope
import core.domain.TaskStage
import core.domain.TaskStatus
import desktop.ui.AppViewModel
import desktop.ui.CreateKind
import desktop.ui.Selection
import desktop.ui.chatMarkdownTypography

fun main() = application {
    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
    Window(onCloseRequest = ::exitApplication, title = "Week2 Memory Agent", state = windowState) {
        MaterialTheme { Root() }
    }
}

@Composable
fun Root() {
    val scope = rememberCoroutineScope()
    val vm = remember(scope) { AppViewModel(scope = scope) }
    val st by vm.state.collectAsState()

    Row(Modifier.fillMaxSize()) {
        // Слева: дерево
        Column(
            Modifier.width(340.dp).fillMaxHeight().background(Color(0xFF1E1E1E)).padding(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Чаты и память", color = Color.White, fontWeight = FontWeight.Bold)
                Button(onClick = vm::openCreate) { Text("+") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                // General: без ветки — чаты и доки лежат прямо в корне древа
                item {
                    st.chats.filter { it.scope == Scope.GENERAL }.forEach { c ->
                        ChatRow(c.name, selected = st.selection == Selection.ChatSel(c.id)) { vm.select(Selection.ChatSel(c.id)) }
                    }
                    st.docs.filter { it.scope == Scope.GENERAL }.forEach { d ->
                        DocRow(d, st.selection is Selection.DocSel && (st.selection as Selection.DocSel).docId == d.id,
                            onOpen = { vm.select(Selection.DocSel(d.id)) }, onToggle = { vm.toggleDoc(d.id) })
                    }
                }
                // Projects
                items(st.projects, key = { "p${it.id}" }) { p ->
                    val pCollapsed = p.id !in st.expandedProjects
                    TreeHeader(
                        p.name,
                        icon = "📁",
                        collapsed = pCollapsed,
                        onClick = { vm.toggleProject(p.id) },
                    )
                    if (!pCollapsed) {
                        // project docs + chats
                        st.docs.filter { it.scope == Scope.PROJECT && it.parentId == p.id }.forEach { d ->
                            DocRow(d, st.selection is Selection.DocSel && (st.selection as Selection.DocSel).docId == d.id,
                                onOpen = { vm.select(Selection.DocSel(d.id)) }, onToggle = { vm.toggleDoc(d.id) }, indent = 12)
                        }
                        st.chats.filter { it.scope == Scope.PROJECT && it.parentId == p.id }.forEach { c ->
                            ChatRow(c.name, selected = st.selection == Selection.ChatSel(c.id), indent = 12) { vm.select(Selection.ChatSel(c.id)) }
                        }
                        // tasks — Task 3: бейдж состояния напротив каждой задачи
                        st.tasks.filter { it.projectId == p.id }.forEach { t ->
                            val tCollapsed = t.id !in st.expandedTasks
                            val ts = st.taskStates[t.id]
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.weight(1f)) {
                                    TreeHeader(
                                        t.name,
                                        icon = "📋",
                                        indent = 0,
                                        collapsed = tCollapsed,
                                        onClick = { vm.toggleTask(t.id) },
                                    )
                                }
                                if (ts != null) {
                                    val stageShort = when (ts.stage) {
                                        TaskStage.PLANNING -> "PLN"
                                        TaskStage.EXECUTION -> "EXE"
                                        TaskStage.VALIDATION -> "VAL"
                                        TaskStage.DONE -> "DONE"
                                    }
                                    val bg = when {
                                        ts.status == TaskStatus.PAUSED -> Color(0xFFFFA726)
                                        ts.stage == TaskStage.DONE -> Color(0xFF66BB6A)
                                        ts.stage == TaskStage.PLANNING -> Color(0xFF42A5F5)
                                        ts.stage == TaskStage.EXECUTION -> Color(0xFFAB47BC)
                                        else -> Color(0xFF78909C)
                                    }
                                    Box(
                                        Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            if (ts.status == TaskStatus.PAUSED) "⏸ $stageShort" else stageShort,
                                            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    if (ts.steps.isNotEmpty() && ts.stage != TaskStage.DONE) {
                                        Text(
                                            "${ts.stepIndex + 1}/${ts.steps.size}",
                                            color = Color(0xFFBBBBBB), fontSize = 10.sp
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                }
                            }
                            if (!tCollapsed) {
                                st.docs.filter { it.scope == Scope.TASK && it.parentId == t.id }.forEach { d ->
                                    DocRow(d, st.selection is Selection.DocSel && (st.selection as Selection.DocSel).docId == d.id,
                                        onOpen = { vm.select(Selection.DocSel(d.id)) }, onToggle = { vm.toggleDoc(d.id) }, indent = 24)
                                }
                                st.chats.filter { it.scope == Scope.TASK && it.parentId == t.id }.forEach { c ->
                                    ChatRow(c.name, selected = st.selection == Selection.ChatSel(c.id), indent = 24) { vm.select(Selection.ChatSel(c.id)) }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Низ древа: круглая кнопка профиля (Аноним по умолчанию, без инжекта в промпт)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val active = st.profiles.firstOrNull { it.id == st.activeProfileId }
                val label = active?.name?.trim()?.firstOrNull()?.uppercase() ?: "?"
                Button(
                    onClick = vm::openProfile,
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(active?.name ?: "Аноним", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        if (active == null) "без персонализации" else "профиль инжектится",
                        color = Color(0xFF888888), fontSize = 11.sp, maxLines = 1,
                    )
                }
            }
        }
        // Справа: чат или память
        Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
            val sel = st.selection
            when (sel) {
                is Selection.ChatSel -> {
                    val chat = st.chats.firstOrNull { it.id == sel.chatId }
                    if (chat == null) Text("Выбери чат слева или создай новый (+)")
                    else ChatPane(vm, chat)
                }
                is Selection.DocSel -> {
                    val doc = st.docs.firstOrNull { it.id == sel.docId }
                    if (doc == null) Text("Документ удалён")
                    else MemoryPane(vm, doc, st.docContents[doc.id].orEmpty())
                }
                null -> Text("Выбери чат слева или создай новый (+)")
            }
            st.status?.let { Text(it, color = Color(0xFFB00020), fontSize = 12.sp) }
        }
    }

    if (st.showCreate) CreateDialog(vm)
    st.saveDialog?.let { SaveDialogView(vm) }
    st.profileDialog?.let { ProfileDialogView(vm) }
}

@Composable
fun TreeHeader(name: String, icon: String = "📁", indent: Int = 0, collapsed: Boolean = true, onClick: (() -> Unit)? = null) {
    val t = "${if (collapsed) "▸" else "▾"} $icon $name"
    val mod = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
        .then(if (indent > 0) Modifier.padding(start = indent.dp) else Modifier)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    Text(t, color = Color(0xFFBBBBBB), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = mod)
}

@Composable
fun ChatRow(label: String, selected: Boolean, indent: Int = 0, onClick: () -> Unit) {
    TreeRow(icon = "☰", iconColor = Color(0xFF9CCC9C), label = label, indent = indent, selected = selected, onClick = onClick)
}

@Composable
fun DocRow(d: MemoryDoc, selected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit, indent: Int = 0) {
    TreeRow(
        icon = if (d.active) "M↓" else "M✕",
        iconColor = if (d.active) Color(0xFF64B5F6) else Color(0xFF777777),
        label = d.title + ".md",
        indent = indent,
        selected = selected,
        onClick = onOpen,
        onIconClick = onToggle,
    )
}

// Общая строка древа: одинаковые отступы, шрифт и подсветка; различаются только иконки.
// У дока иконка кликабельна (вкл/выкл в промпте), клик по названию открывает документ.
@Composable
private fun TreeRow(
    icon: String,
    iconColor: Color,
    label: String,
    indent: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onIconClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF2D2D2D) else Color.Transparent)
            .then(if (indent > 0) Modifier.padding(start = indent.dp) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            icon,
            color = iconColor,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 6.dp)
                .then(if (onIconClick != null) Modifier.clickable(onClick = onIconClick) else Modifier)
        )
        Text(
            label,
            color = if (selected) Color(0xFF7DD87D) else Color(0xFF9CCC9C),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f).clickable(onClick = onClick)
        )
    }
}

@Composable
fun ChatPane(vm: AppViewModel, chat: Chat) {
    val st by vm.state.collectAsState()
    Column(Modifier.fillMaxHeight()) {
    // Task 3: бар состояния задачи — виден только для TASK-чатов
    val tid = chat.parentId
    if (chat.scope == Scope.TASK && tid != null) {
        TaskStateBar(vm, tid)
        Spacer(Modifier.height(6.dp))
    }
    // Шапка: системный промпт collapse/extend
    // Заголовок — хлебные крошки по уровню: general — имя, project — "проект / имя", task — "проект / задача / имя"
    val title = when (chat.scope) {
        Scope.GENERAL -> chat.name
        Scope.PROJECT -> "${st.projects.firstOrNull { it.id == chat.parentId }?.name ?: "?"} / ${chat.name}"
        Scope.TASK -> {
            val task = st.tasks.firstOrNull { it.id == chat.parentId }
            val project = st.projects.firstOrNull { it.id == task?.projectId }
            "${project?.name ?: "?"} / ${task?.name ?: "?"} / ${chat.name}"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::toggleSystem) { Text(if (st.systemExpanded) "▲ system prompt" else "▼ system prompt") }
        TextButton(onClick = vm::clearChat) { Text("🗑", fontSize = 16.sp) }
    }
    if (st.systemExpanded) {
        // Развёрнутый промпт: максимум пол окна, внутри скролл
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val half = maxHeight / 2
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(max = half)
                    .verticalScroll(rememberScrollState())
                    .background(Color(0xFFF5F5F5)).padding(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        st.systemPrompt.ifBlank { "(память пуста, только base prompt)" },
                        fontSize = 11.sp, color = Color.Gray,
                    )
                }
            }
        }
        Text("~${st.systemPrompt.length / 4} tok (оценка) · last prompt_tokens=${st.promptTokens}", fontSize = 11.sp, color = Color.Gray)
    }
    Spacer(Modifier.height(6.dp))
    // Сообщения — прямо из стейта (обновляются каждым refresh), без remember:
    // иначе лента не перерисовывалась до смены чата и обратно
    val messages = st.messages[chat.id].orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(chat.id, messages.size, st.busy) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
        items(messages, key = { it.hashCode().toString() + it.content.take(20) }) { m ->
            MessageBubble(vm, chat, m.role, m.content)
        }
        if (st.busy) {
            item(key = "typing") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(Color(0xFFF1F1F1)).padding(8.dp)
                ) {
                    Text("Печатает…", fontSize = 14.sp, color = Color.Gray)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = st.input, onValueChange = vm::onInput,
            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                // Enter / NumpadEnter без Shift — отправка, Shift+Enter — новая строка
                if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                    if (event.isShiftPressed) false
                    else {
                        if (event.type == KeyEventType.KeyDown) vm.send()
                        true
                    }
                } else false
            }, placeholder = { Text("сообщение...") },
            enabled = !st.busy, maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = vm::send, enabled = !st.busy && st.input.isNotBlank()) {
            Text(if (st.busy) "..." else "➤")
        }
    }
    }
}

@Composable
fun MessageBubble(vm: AppViewModel, chat: Chat, role: String, text: String) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val bg = if (role == "user") Color(0xFFE3F2FD) else Color(0xFFF1F1F1)
    var menu by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf(IntOffset.Zero) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun copy() {
        @Suppress("DEPRECATION")
        clipboard.setText(AnnotatedString(text))
        menu = false
    }
    fun save() {
        menu = false
        vm.startSave(text, chat.scope, chat.parentId)
    }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .background(bg).padding(8.dp)
            .onGloballyPositioned { coords = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Initial-проход: ловим ПКМ раньше SelectionContainer и гасим событие,
                        // иначе всплывает его дефолтное меню "Copy" вместо нашего
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            val local = event.changes.firstOrNull()?.position ?: Offset.Zero
                            val win = coords?.localToWindow(local) ?: local
                            cursor = IntOffset(win.x.roundToInt(), win.y.roundToInt())
                            event.changes.forEach { it.consume() }
                            menu = true
                        }
                    }
                }
            }
    ) {
        Column {
            Text(role, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            SelectionContainer {
                if (role == "user") {
                    Text(text, fontSize = 14.sp)
                } else {
                    // Реплики LLM — markdown: компактная типографика (дефолт displayLarge ~57sp — огромный).
                    // modifier fillMaxWidth вместо дефолтного fillMaxSize, иначе пузырь растягивается.
                    Markdown(text, typography = chatMarkdownTypography(), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    // Своё меню строго в позиции курсора (PopupPositionProvider возвращает window-координаты клика)
    if (menu) {
        val positionProvider = remember(cursor) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = cursor
            }
        }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = { menu = false },
        ) {
            Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 8.dp) {
                Column(Modifier.width(IntrinsicSize.Max)) {
                    DropdownMenuItem(text = { Text("Копировать") }, onClick = ::copy)
                    DropdownMenuItem(text = { Text("Сохранить в память") }, onClick = ::save)
                }
            }
        }
    }
}

@Composable
fun MemoryPane(vm: AppViewModel, doc: MemoryDoc, content: String) {
    // Task 3: если док привязан к задаче — показать бар состояния
    val docTaskId = doc.parentId
    if (doc.scope == Scope.TASK && docTaskId != null) {
        TaskStateBar(vm, docTaskId)
        Spacer(Modifier.height(6.dp))
    }
    Text(doc.title + ".md", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Text("${doc.scope} ${doc.parentId ?: ""} · ${if (doc.active) "active (инжектится)" else "выключен"}", fontSize = 12.sp, color = Color.Gray)
    Row { TextButton(onClick = { vm.toggleDoc(doc.id) }) { Text(if (doc.active) "выключить" else "включить") }
        TextButton(onClick = { vm.deleteDoc(doc.id) }) { Text("удалить") } }
    Spacer(Modifier.height(6.dp))
    SelectionContainer {
        if (content.isBlank()) {
            Text("(пусто)", fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Color(0xFFF9F9F9)).padding(10.dp))
        } else {
            // MemoryDoc — .md файлы: рендерим markdown той же компактной типографикой, что и реплики
            Markdown(
                content,
                typography = chatMarkdownTypography(),
                modifier = Modifier.fillMaxWidth().background(Color(0xFFF9F9F9)).padding(10.dp)
            )
        }
    }
}

@Composable
fun TaskStateBar(vm: AppViewModel, taskId: String) {
    val st by vm.state.collectAsState()
    val ts = st.taskStates[taskId]
    if (ts == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Состояние: нет данных", fontSize = 11.sp, color = Color.Gray)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.service.ensureTaskState(taskId); vm.refresh() }) { Text("Инициализировать", fontSize = 11.sp) }
        }
        return
    }
    val taskName = st.tasks.firstOrNull { it.id == taskId }?.name ?: taskId.take(8)
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFF5F5F5), RoundedCornerShape(6.dp)).padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("▸ $taskName", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            val stageLabel = "${ts.stage.name} · ${ts.stage.label}"
            val statusColor = when {
                ts.status == TaskStatus.PAUSED -> Color(0xFFFFA726)
                ts.stage == TaskStage.DONE -> Color(0xFF2E7D32)
                else -> Color(0xFF555555)
            }
            Text(stageLabel, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
            if (ts.status == TaskStatus.PAUSED) {
                Spacer(Modifier.width(6.dp))
                Text("⏸ пауза", fontSize = 11.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        // шаг и nextAction
        if (ts.steps.isNotEmpty()) {
            val curStep = ts.steps.getOrNull(ts.stepIndex)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Шаг ${ts.stepIndex + 1}/${ts.steps.size}: ${curStep?.title ?: ""}",
                    fontSize = 12.sp, color = Color(0xFF333333), modifier = Modifier.weight(1f)
                )
                if (curStep?.done == true) Text(" ✓", color = Color(0xFF2E7D32), fontSize = 12.sp)
            }
            // прогресс шагов точками
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                ts.steps.forEachIndexed { idx, s ->
                    val c = when {
                        s.done -> Color(0xFF66BB6A)
                        idx == ts.stepIndex -> Color(0xFF42A5F5)
                        else -> Color(0xFFCCCCCC)
                    }
                    Box(Modifier.size(8.dp).background(c, CircleShape))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Ожидается: ${ts.nextAction}", fontSize = 11.sp, color = Color(0xFF616161))
        // План-док: виден если planning→execution уже сохранил план
        val planDoc = st.docs.firstOrNull {
            it.scope == Scope.TASK && it.parentId == taskId && it.title == "план"
        }
        if (planDoc != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📄 план: ${planDoc.title}.md", fontSize = 11.sp, color = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.select(Selection.DocSel(planDoc.id)) }) { Text("открыть", fontSize = 11.sp) }
            }
        } else if (ts.stage == TaskStage.PLANNING) {
            Text("План ещё не зафиксирован — нажми «→ Execution» чтобы сохранить в план.md", fontSize = 10.sp, color = Color.Gray)
        }
        if (ts.stage == TaskStage.EXECUTION && ts.status == TaskStatus.ACTIVE) {
            Text("🤖 EXECUTION автоматический: код выдаётся шаг за шагом через API", fontSize = 11.sp, color = Color(0xFF1565C0))
        }
        if (ts.stage == TaskStage.EXECUTION && ts.status == TaskStatus.PAUSED) {
            val cur = ts.steps.getOrNull(ts.stepIndex)?.title.orEmpty()
            Text("⏸ Остановлено на невыполненном шаге ${ts.stepIndex + 1}/${ts.steps.size}: $cur", fontSize = 11.sp, color = Color(0xFFE65100), fontWeight = FontWeight.SemiBold)
        }
        if (ts.stage == TaskStage.VALIDATION) {
            Text("🔍 Валидация: проверь результат, продолжи чат по исправлениям или подтверди ниже", fontSize = 11.sp, color = Color(0xFF6A1B9A), fontWeight = FontWeight.SemiBold)
        }
        if (st.busy) {
            Text("⚙️ ИИ работает через API…", fontSize = 11.sp, color = Color(0xFF1565C0), fontWeight = FontWeight.SemiBold)
        }
        if (ts.history.isNotEmpty()) {
            Text(
                "История: " + ts.history.takeLast(4).joinToString(" → ") { "${it.from.name}→${it.to.name}" },
                fontSize = 10.sp, color = Color.Gray
            )
        }
        Spacer(Modifier.height(6.dp))
        // Управление — FlowRow чтобы влезало на узких окнах
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // EXECUTION автоматический: только Пауза / Продолжить, ручных шагов нет
            if (ts.stage == TaskStage.EXECUTION) {
                if (ts.status == TaskStatus.ACTIVE) {
                    OutlinedButton(onClick = { vm.pauseTask(taskId) }) { Text("⏸ Пауза авто", fontSize = 12.sp) }
                }
                if (ts.status == TaskStatus.PAUSED) {
                    Button(onClick = { vm.resumeTask(taskId) }) { Text("▶ Продолжить авто", fontSize = 12.sp) }
                }
                // Досрочный выход в валидацию (остаток шагов сгорит)
                Button(
                    onClick = { vm.advanceTask(taskId) },
                    enabled = ts.status == TaskStatus.ACTIVE && !st.busy
                ) { Text("→ Validation", fontSize = 12.sp) }
            } else {
                // PLANNING / VALIDATION / DONE — ручное управление
                if (ts.status == TaskStatus.ACTIVE && ts.stage != TaskStage.DONE) {
                    OutlinedButton(onClick = { vm.pauseTask(taskId) }) { Text("⏸ Пауза", fontSize = 12.sp) }
                }
                if (ts.status == TaskStatus.PAUSED) {
                    Button(onClick = { vm.resumeTask(taskId) }) { Text("▶ Продолжить", fontSize = 12.sp) }
                }
                if (ts.stage == TaskStage.PLANNING) {
                    OutlinedButton(
                        onClick = { vm.prevStep(taskId) },
                        enabled = ts.status == TaskStatus.ACTIVE && ts.stepIndex > 0 && !st.busy
                    ) { Text("‹ Шаг", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { vm.completeStep(taskId) },
                        enabled = ts.status == TaskStatus.ACTIVE && ts.steps.getOrNull(ts.stepIndex)?.done == false && !st.busy
                    ) { Text("✓ Готов", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { vm.nextStep(taskId) },
                        enabled = ts.status == TaskStatus.ACTIVE && ts.stepIndex < ts.steps.size - 1 && !st.busy
                    ) { Text("Шаг ›", fontSize = 12.sp) }
                }
                // Этапы — planning → execution (авто) → validation (ждём юзера) → done
                val canAdvance = ts.status == TaskStatus.ACTIVE && ts.stage != TaskStage.DONE && !st.busy
                Button(
                    onClick = { vm.advanceTask(taskId) },
                    enabled = canAdvance
                ) {
                    val label = when (ts.stage) {
                        TaskStage.PLANNING -> "→ Execution (план → авто-код)"
                        TaskStage.EXECUTION -> "→ Validation"
                        TaskStage.VALIDATION -> "✓ Подтверждаю → Done"
                        TaskStage.DONE -> "Done"
                    }
                    Text(label, fontSize = 12.sp)
                }
            }
        }
        // Быстрый выбор этапа (для демо)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            TaskStage.entries.forEach { s ->
                FilterChip(
                    selected = ts.stage == s,
                    onClick = { if (ts.stage != s) vm.setTaskStage(taskId, s) },
                    label = { Text(s.name.lowercase(), fontSize = 10.sp) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    val needParent = st.createKind == CreateKind.PROJECT_CHAT || st.createKind == CreateKind.TASK || st.createKind == CreateKind.TASK_CHAT
    val canConfirm = st.createName.isNotBlank() && (!needParent || st.createParentId != null)
    val parentOptions: List<Pair<String, String>> = when (st.createKind) {
        CreateKind.PROJECT_CHAT, CreateKind.TASK -> st.projects.map { it.id to "📁 ${it.name}" }
        CreateKind.TASK_CHAT -> st.tasks.map { it.id to "📋 ${st.projects.firstOrNull { p -> p.id == it.projectId }?.name ?: "?"} / ${it.name}" }
        else -> emptyList()
    }
    AlertDialog(
        onDismissRequest = vm::closeCreate,
        title = { Text("Создать") },
        text = {
            Column {
                // Тип сущности — одиночный выбор чипами: клик сразу переключает (setCreateKind сбрасывает parent)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CreateKind.entries.forEach { k ->
                        FilterChip(
                            selected = st.createKind == k,
                            onClick = { vm.setCreateKind(k) },
                            label = { Text(k.title) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = st.createName,
                    onValueChange = vm::setCreateName,
                    label = { Text("Имя") },
                    singleLine = true,
                    modifier = Modifier.onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                            if (event.type == KeyEventType.KeyDown && canConfirm) vm.commitCreate()
                            true
                        } else false
                    },
                )
                if (needParent) {
                    Spacer(Modifier.height(8.dp))
                    Text("Родитель:", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    if (parentOptions.isEmpty()) {
                        Text("Нет доступных родителей — сначала создай проект/задачу", fontSize = 12.sp, color = Color.Gray)
                    } else {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            parentOptions.forEach { (id, label) ->
                                FilterChip(
                                    selected = st.createParentId == id,
                                    onClick = { vm.setCreateParent(id) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = vm::commitCreate,
                enabled = canConfirm
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = vm::closeCreate) { Text("Отмена") } },
    )
}

@Composable
fun SaveDialogView(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    val d = st.saveDialog ?: return
    var scopeExpanded by remember { mutableStateOf(false) }
    // доки-кандидаты под выбранный scope+parent
    val candidateDocs = st.docs.filter {
        it.scope == d.targetScope && (d.targetScope == Scope.GENERAL || it.parentId == d.targetParentId)
    }
    val parentOptions: List<Pair<String, String>> = when (d.targetScope) {
        Scope.GENERAL -> emptyList()
        Scope.PROJECT -> st.projects.map { it.id to "📁 ${it.name}" }
        Scope.TASK -> st.tasks.map { it.id to "📁 ${st.projects.firstOrNull { p -> p.id == it.projectId }?.name ?: "?"} / ${it.name}" }
    }
    AlertDialog(
        onDismissRequest = vm::closeSave,
        title = { Text("Сохранить в память") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (d.busy) {
                    Text("Дистиллирую факт через DeepSeek...")
                } else {
                    Text("Сырец:", fontSize = 11.sp, color = Color.Gray)
                    Text(d.rawText.take(300), fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    if (d.candidates.size > 1) {
                        Text("Варианты дистилляции:", fontSize = 11.sp)
                        d.candidates.forEachIndexed { i, c ->
                            TextButton(onClick = { vm.pickCandidate(i) }) { Text("• ${c.take(120)}", fontSize = 12.sp) }
                        }
                    }
                    OutlinedTextField(value = d.editedFact, onValueChange = vm::setSaveEdited, label = { Text("Факт (редактируемый)") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Box {
                        OutlinedButton(onClick = { scopeExpanded = true }) { Text(d.targetScope.name) }
                        DropdownMenu(scopeExpanded, onDismissRequest = { scopeExpanded = false }) {
                            Scope.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.setSaveScope(s); scopeExpanded = false })
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (parentOptions.isNotEmpty()) {
                    var pe by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { pe = true }) {
                            Text(parentOptions.firstOrNull { it.first == d.targetParentId }?.second ?: "Выбери родителя")
                        }
                        DropdownMenu(pe, onDismissRequest = { pe = false }) {
                            parentOptions.forEach { (id, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { vm.setSaveParent(id); pe = false })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = d.useNewDoc, onCheckedChange = { vm.setSaveNewDoc(it) })
                    Text("новый документ", fontSize = 13.sp)
                }
                if (d.useNewDoc) {
                    OutlinedTextField(
                        value = d.newDocTitle, onValueChange = { vm.setSaveNewDoc(true, it) },
                        label = { Text("title нового .md") }, modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    var de by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { de = true }, enabled = candidateDocs.isNotEmpty()) {
                            Text(candidateDocs.firstOrNull { it.id == d.targetDocId }?.title ?: "Выбери .md (${candidateDocs.size})")
                        }
                        DropdownMenu(de, onDismissRequest = { de = false }) {
                            candidateDocs.forEach { doc ->
                                DropdownMenuItem(text = { Text(doc.title) }, onClick = { vm.setSaveDoc(doc.id); de = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = vm::commitSave,
                enabled = !d.busy && d.editedFact.isNotBlank() &&
                    (d.targetScope == Scope.GENERAL || d.targetParentId != null) &&
                    (d.useNewDoc || d.targetDocId != null)
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = vm::closeSave) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileDialogView(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    val d = st.profileDialog ?: return
    AlertDialog(
        onDismissRequest = vm::closeProfile,
        title = { Text("Профиль") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("Выбор пользователя (клик сразу переключает):", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = d.selectedId == null,
                        onClick = { vm.setProfileSelected(null) },
                        label = { Text("Аноним") },
                    )
                    st.profiles.forEach { p ->
                        FilterChip(
                            selected = d.selectedId == p.id,
                            onClick = { vm.setProfileSelected(p.id) },
                            label = { Text(p.name) },
                        )
                    }
                }
                if (d.selectedId == null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Аноним: в system prompt ничего не добавляется.", fontSize = 12.sp, color = Color.Gray)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = d.name, onValueChange = vm::setProfileName,
                    label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = d.style, onValueChange = vm::setProfileStyle,
                    label = { Text("Стиль (напр. кратко, по-русски)") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = d.format, onValueChange = vm::setProfileFormat,
                    label = { Text("Формат (напр. списки, примеры кода)") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = d.constraints, onValueChange = vm::setProfileConstraints,
                    label = { Text("Ограничения (напр. без кода без просьбы)") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::createProfile, enabled = d.name.isNotBlank()) { Text("Создать") }
                    OutlinedButton(onClick = vm::saveProfile, enabled = d.selectedId != null) { Text("Сохранить") }
                    OutlinedButton(onClick = vm::deleteProfile, enabled = d.selectedId != null) { Text("Удалить") }
                }
            }
        },
        confirmButton = { Button(onClick = vm::closeProfile) { Text("Готово") } },
        dismissButton = {},
    )
}
