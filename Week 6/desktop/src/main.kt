package desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import com.mikepenz.markdown.m3.Markdown
import core.llm.LlmSettings
import core.project.ProjectEntry
import core.agent.isTerminal
import core.rag.CHAT_KIND_AGENT
import core.rag.CHAT_KIND_RAG
import core.rag.DocInfo
import core.rag.RagChat
import core.rag.RagMessage
import desktop.ui.AppViewModel
import desktop.ui.UiState
import desktop.ui.chatMarkdownTypography

fun main() = application {
    val windowState = rememberWindowState(width = 1100.dp, height = 780.dp)
    Window(onCloseRequest = ::exitApplication, title = "Week6 Dev Assistant — ассистент разработчика", state = windowState) {
        MaterialTheme { Root() }
    }
}

@Composable
fun Root() {
    val scope = rememberCoroutineScope()
    val vm = remember(scope) { AppViewModel(scope = scope) }
    val st by vm.state.collectAsState()

    Row(Modifier.fillMaxSize()) {
        // Слева: две секции чатов — информационные и агентные
        Column(
            Modifier.width(340.dp).fillMaxHeight().background(Color(0xFF1E1E1E)).padding(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Чаты", color = Color.White, fontWeight = FontWeight.Bold)
                Button(onClick = { vm.openCreateChat(CHAT_KIND_RAG) }) { Text("+") }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                items(st.ragChats, key = { "c${it.id}" }) { c ->
                    ChatRow(c, selected = c.id == st.activeChatId,
                        onOpen = { vm.selectChat(c.id) },
                        onToggleRag = { vm.toggleRag(c.id) },
                        onDelete = { vm.deleteChat(c.id) })
                }
                if (st.ragChats.isEmpty()) {
                    item { Text("Нет чатов — создай через +", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Агентные чаты — отдельной секцией: у них своя история прогонов
            // и им не нужны вопросы по базе знаний.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🎯 Агентные чаты", color = Color(0xFF9CCC9C), fontWeight = FontWeight.Bold)
                Button(onClick = { vm.openCreateChat(CHAT_KIND_AGENT) }) { Text("+") }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                items(st.agentChats, key = { "a${it.id}" }) { c ->
                    ChatRow(c, selected = c.id == st.activeChatId,
                        onOpen = { vm.selectChat(c.id) },
                        onToggleRag = {},
                        onDelete = { vm.deleteChat(c.id) })
                }
                if (st.agentChats.isEmpty()) {
                    item { Text("Агент работает с файлами проекта — заведи чат через +", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Проекты", color = Color.White, fontWeight = FontWeight.Bold)
                Button(onClick = vm::openAddProject) { Text("+") }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
                items(st.projects, key = { "p${it.name}" }) { p ->
                    ProjectRow(
                        p, selected = p.name == st.selectedProject,
                        status = st.projectStatus[p.name],
                        onSelect = { vm.selectProject(p.name) },
                        onUpdate = { vm.updateProject(p.name) },
                        onDelete = { vm.removeProject(p.name) },
                    )
                }
                if (st.projects.isEmpty()) {
                    item { Text("Нет проектов — нажми + (git-URL или локальный путь)", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Файлы проекта", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(st.docs, key = { "d${it.source}" }) { d ->
                    DocRow(d, progress = st.indexProgress[d.source],
                        selected = st.viewingDoc == d.source,
                        onOpen = { vm.viewDoc(d.source) },
                        onToggle = { vm.toggleDoc(d.source) },
                        onDelete = { vm.removeDoc(d.source) })
                }
                if (st.docs.isEmpty()) {
                    item { Text(if (st.projects.isEmpty()) "Добавь первый проект кнопкой +" else "Файлы появятся после индексации", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("🤖 ${st.llmLabel}", color = Color(0xFF666666), fontSize = 10.sp, maxLines = 1)
            Text("⚙ ${st.embedLabel}", color = Color(0xFF666666), fontSize = 10.sp, maxLines = 1)
            Text(st.indexSummary, color = Color(0xFF888888), fontSize = 11.sp, maxLines = 2)
            if (st.indexLog.isNotEmpty()) {
                Text(st.indexLog.last(), color = Color(0xFF666666), fontSize = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            // Внизу сайдбара — настройки LLM и эмбеддингов (как Week 5: выбор моделей).
            OutlinedButton(onClick = vm::openSettings, modifier = Modifier.fillMaxWidth()) {
                Text("⚙ Настройки", fontSize = 12.sp)
            }
        }
        // Справа: чат или документ
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val selDoc = st.viewingDoc
            if (selDoc != null) {
                val doc = st.docs.firstOrNull { it.source == selDoc }
                DocPane(vm, selDoc, doc, st.viewingText)
            } else {
                val chat = st.chats.firstOrNull { it.id == st.activeChatId }
                if (chat == null) {
                    Text("Выбери чат слева или создай новый (+)", modifier = Modifier.padding(12.dp))
                } else if (chat.isAgent) {
                    AgentChatPane(vm, chat)
                } else {
                    ChatPane(vm, chat)
                }
            }
            st.status?.let {
                Text(it, color = Color(0xFFB00020), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }

    if (st.showCreateChat) CreateChatDialog(vm)
    if (st.showWiki) WikiDialog(vm)
    if (st.showAddProject) AddProjectDialog(vm)
    if (st.showSettings) SettingsDialog(vm)
    if (st.showRagSettings) RagSettingsDialog(vm)
    // Task 4: разрешение на запись показывается карточкой в ленте агентного
    // чата (ApprovalCard), а не отдельным окном — см. AgentRunCard.
}

@Composable
fun ChatRow(c: RagChat, selected: Boolean, onOpen: () -> Unit, onToggleRag: () -> Unit, onDelete: () -> Unit) {
    val agent = c.isAgent
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF2D2D2D) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // У агентного чата нет переключателя RAG — значок просто метка вида.
        Text(
            if (agent) "🎯" else "💬", fontSize = 13.sp,
            modifier = if (agent) Modifier.padding(end = 6.dp) else Modifier.padding(end = 6.dp).clickable(onClick = onToggleRag),
            color = when {
                agent -> Color(0xFF1E88E5)
                c.ragEnabled -> Color(0xFF7DD87D)
                else -> Color(0xFF777777)
            },
        )
        Text(
            c.name + if (!agent && !c.ragEnabled) "  (без RAG)" else "",
            color = if (selected) {
                if (agent) Color(0xFF90CAF9) else Color(0xFF7DD87D)
            } else if (agent) {
                Color(0xFF64B5F6)
            } else {
                Color(0xFF9CCC9C)
            },
            fontSize = 14.sp, modifier = Modifier.weight(1f).clickable(onClick = onOpen), maxLines = 1,
        )
        Text("×", color = Color(0xFF777777), fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDelete).padding(start = 4.dp))
    }
}

@Composable
fun ProjectRow(
    p: ProjectEntry,
    selected: Boolean,
    status: String?,
    onSelect: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF2D2D2D) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "📦●" else "📦○",
            color = if (selected) Color(0xFF7DD87D) else Color(0xFF777777),
            fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp),
        )
        Column(Modifier.weight(1f).clickable(onClick = onSelect)) {
            Text(
                p.name, color = if (selected) Color(0xFF7DD87D) else Color(0xFF9CCC9C),
                fontSize = 13.sp, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                status ?: p.localPath.takeLast(60),
                color = Color(0xFF777777), fontSize = 10.sp, maxLines = 1,
            )
        }
        Text("⟳", color = Color(0xFF64B5F6), fontSize = 13.sp, modifier = Modifier.clickable(onClick = onUpdate).padding(horizontal = 4.dp))
        Text("×", color = Color(0xFF777777), fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDelete).padding(start = 4.dp))
    }
}

@Composable
fun DocRow(d: DocInfo, progress: Float?, selected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    // Индексирующийся документ: строка некликабельна, вместо действий — прогрессбар.
    if (d.indexing) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📄⏳", color = Color(0xFF777777), fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, color = Color(0xFF777777), fontSize = 13.sp, maxLines = 1)
                Text(
                    "индексируется… · ${d.strategyLabel.ifBlank { "…" }}",
                    color = Color(0xFF777777), fontSize = 10.sp, maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF2D2D2D) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (d.active) "📄↓" else "📄✕",
            color = if (d.active) Color(0xFF64B5F6) else Color(0xFF777777),
            fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp).clickable(onClick = onToggle),
        )
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(
                d.title, color = if (selected) Color(0xFF7DD87D) else Color(0xFF9CCC9C),
                fontSize = 13.sp, maxLines = 1,
            )
            Text(
                "${d.chunks} чанков · ${d.strategyLabel.ifBlank { "…" }}",
                color = Color(0xFF777777), fontSize = 10.sp, maxLines = 1,
            )
            d.error?.let {
                Text("⚠ $it", color = Color(0xFFCF6679), fontSize = 10.sp, maxLines = 1)
            }
        }
        Text("×", color = Color(0xFF777777), fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDelete).padding(start = 4.dp))
    }
}

// Enter с основной клавиатуры и с NumPad — подтверждение (отправка/создание/загрузка).
private fun isConfirmKey(e: KeyEvent): Boolean =
    (e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyDown

@Composable
fun ChatPane(vm: AppViewModel, chat: RagChat) {
    val st by vm.state.collectAsState()
    Column(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(chat.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
            FilterChip(
                selected = chat.ragEnabled,
                onClick = { vm.toggleRag(chat.id) },
                label = { Text(if (chat.ragEnabled) "RAG вкл" else "RAG выкл") },
            )
            if (chat.ragEnabled) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = vm::openRagSettings) { Text("RAG ⚙", fontSize = 12.sp) }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { vm.clearChat(chat.id) }) { Text("очистить", fontSize = 12.sp) }
        }
        if (!chat.ragEnabled) {
            Text(
                "RAG выключен — отвечаю без базы знаний",
                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        // Task 5: память задачи — факты чата вместо полного лога в промпте.
        val mem = st.taskMemory
        if (mem != null && !mem.isEmpty()) {
            var memExpanded by remember(chat.id, mem.updatedAt) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().background(Color(0xFFF0F7F0)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "🧠 ${if (mem.goal.isNotBlank()) mem.goal.take(80) else "фактов: ${mem.facts.size}"}",
                        fontSize = 11.sp, color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f).clickable { memExpanded = !memExpanded }, maxLines = 1,
                    )
                    TextButton(onClick = { memExpanded = !memExpanded }) {
                        Text(if (memExpanded) "▲" else "▼ фактов: ${mem.facts.size}", fontSize = 11.sp)
                    }
                    TextButton(onClick = vm::clearTaskMemory) { Text("забыть", fontSize = 11.sp) }
                }
                if (memExpanded) {
                    val memScroll = rememberScrollState()
                    Box(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                        Column(Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(memScroll)) {
                            if (mem.goal.isNotBlank()) Text("Цель: ${mem.goal}", fontSize = 11.sp, color = Color(0xFF333333))
                            mem.facts.forEach { f -> Text("• $f", fontSize = 11.sp, color = Color(0xFF555555)) }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(memScroll),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        val messages = st.messages
        val listState = rememberLazyListState()
        LaunchedEffect(chat.id, messages.size, st.busy) {
            if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Контейнер выделения на всю ленту: текст можно тянуть через
            // несколько сообщений, Ctrl+C копирует выделенное.
            SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 20.dp)) {
                items(messages, key = { it.id }) { m -> MessageBubble(m) }
                if (st.busy) {
                    item(key = "typing") {
                        Text("Печатает…", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = st.input, onValueChange = vm::setInput,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // Enter / NumPadEnter — отправить, Shift+Enter — новая строка (дефолт текстового поля).
                    if (isConfirmKey(e) && !e.isShiftPressed) {
                        vm.send()
                        true
                    } else false
                },
                placeholder = { Text(if (chat.ragEnabled) "вопрос по проекту…" else "сообщение…") },
                enabled = !st.busy, maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = vm::send, enabled = !st.busy && st.input.isNotBlank()) {
                Text(if (st.busy) "..." else "➤")
            }
        }
    }
}

// Агентный чат. Лента = список прогонов этого чата: сообщение пользователя
// (цель) и карточка прогона, где видно ход, diff и — если агент ждёт —
// разрешение на запись. Отдельного окна подтверждения нет: решение видно
// там же, где идёт работа.
@Composable
fun AgentChatPane(vm: AppViewModel, chat: RagChat) {
    val st by vm.state.collectAsState()
    Column(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFEAF1FB)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🎯 ${chat.name}", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                st.selectedProject?.let { "проект: $it" } ?: "проект не выбран",
                fontSize = 12.sp, color = if (st.selectedProject != null) Color.Gray else Color(0xFFB00020),
                maxLines = 1,
            )
            if (st.busy) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = vm::cancelAgent) { Text("⏸ стоп", fontSize = 12.sp, color = Color(0xFFB00020)) }
            }
        }
        val runs = st.chatRuns
        val listState = rememberLazyListState()
        // На новый прогон и на каждом ходе прокручиваем к последней карточке —
        // иначе длинный trace уезжает за экран и агент выглядит «зависшим».
        LaunchedEffect(chat.id, runs.size, st.agentRun?.steps, st.pendingChange) {
            if (runs.isNotEmpty()) listState.animateScrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Контейнер выделения на всю ленту: текст можно тянуть через несколько
            // сообщений и прогонов, а Ctrl+C копирует выделенное.
            SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 24.dp)) {
                if (runs.isEmpty() && !st.busy) {
                    item(key = "agent-hint") {
                        Text(
                            "Агентный чат: пиши цель — он сам выберет файлы.\n" +
                                "Например: «найди все места, где используется RagTools» или\n" +
                                "«обнови README под текущий код».\n" +
                                "Каждое изменение файлов спросит разрешения здесь же, в чате.",
                            fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                itemsIndexed(runs, key = { _, r -> "r${r.id}" }) { _, r ->
                    Column {
                        // Сообщение пользователя — сама цель.
                        Column(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp)
                                .background(Color(0xFFE3F2FD)).padding(8.dp),
                        ) {
                            Text("🎯 цель", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Text(r.goal, fontSize = 14.sp)
                        }
                        AgentRunCard(vm, r, st)
                    }
                }
                if (st.busy && runs.isEmpty()) {
                    item(key = "agent-thinking") {
                        Text("Агент думает…", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp),
            )
        }
        // Ввод снизу — как в обычном чате: текст уходит агенту как цель.
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = st.input, onValueChange = vm::setInput,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // Enter / NumPadEnter — отправить, Shift+Enter — новая строка.
                    if (isConfirmKey(e) && !e.isShiftPressed) {
                        vm.sendAgent()
                        true
                    } else false
                },
                placeholder = { Text("цель для агента: что сделать с файлами проекта…") },
                enabled = !st.busy && st.selectedProject != null, maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = vm::sendAgent, enabled = !st.busy && st.input.isNotBlank()) {
                Text(if (st.busy) "..." else "➤")
            }
        }
    }
}

// Итог прогона текстом: то, что показываем в карточке и то, что копируется.
private fun runSummaryText(run: core.agent.AgentRun): String = when (val s = run.state) {
    is core.agent.RunState.Done -> s.summary.ifBlank { run.summary }
    is core.agent.RunState.Failed -> "Прогон прерван: ${s.error}"
    is core.agent.RunState.Cancelled -> "Прогон отменён."
    else -> run.summary
}.trim()

// Карточка прогона в ленте: состояние, ход тулов, итог, изменённые файлы,
// diff, откат — и запрос разрешения, когда агент хочет что-то записать.
@Composable
fun AgentRunCard(vm: AppViewModel, run: core.agent.AgentRun, st: UiState) {
    val color = STATE_COLORS[stateKey(run)] ?: Color.Gray
    val log = if (run.id == st.agentRun?.id) st.agentLog else emptyList()
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .background(Color(0xFFF7F4EC)).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(run.label(), fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "шагов ${run.steps} · вызовов ${run.invocations.size} · ${run.id}",
                fontSize = 10.sp, color = Color.Gray, modifier = Modifier.weight(1f), maxLines = 1,
            )
            // Продолжение: прогон на паузе (⏹ или приложение закрылось), упал или был
            // отменён. Кнопка возвращает агента в тот же разговор и ту же работу.
            if (run.resumable && !(run.id == st.agentRun?.id && st.busy)) {
                TextButton(
                    onClick = { vm.resumeRun(run.id) },
                    enabled = !st.busy,
                ) { Text("▶ продолжить", fontSize = 11.sp, color = Color(0xFF1E88E5)) }
            }
            if (!run.state.isTerminal) {
                Text("●", fontSize = 11.sp, color = color)
            }
        }
        // Запрос разрешения: агент хочет записать файл. Решение — здесь.
        if (st.pendingChange != null && run.state is core.agent.RunState.AwaitingApproval) {
            ApprovalCard(vm, st.pendingChange)
        }
        // Итог прогона.
        val summary = runSummaryText(run)
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Markdown(summary, typography = chatMarkdownTypography(), modifier = Modifier.fillMaxWidth())
        }
        if (run.error.isNotBlank() && run.state !is core.agent.RunState.Failed) {
            Text(run.error, fontSize = 11.sp, color = Color(0xFFB00020))
        }
        // Ход: вызовы тулов и журнал — под раскрывашкой, чтобы не давило текстом.
        if (run.invocations.isNotEmpty() || log.isNotEmpty()) {
            var open by remember(run.id, run.invocations.size) { mutableStateOf(run.invocations.size <= 6) }
            TextButton(onClick = { open = !open }) {
                Text(
                    if (open) "▲ ход: ${run.invocations.size} вызовов" else "▼ ход: ${run.invocations.size} вызовов",
                    fontSize = 11.sp,
                )
            }
            if (open) {
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    run.invocations.forEach { inv ->
                        val (icon, c, note) = when (val s = inv.state) {
                            is core.agent.ToolState.Done -> Triple("✅", Color(0xFF2E7D32), s.summary.take(80))
                            is core.agent.ToolState.Failed -> Triple("❌", Color(0xFFB00020), s.error.take(80))
                            is core.agent.ToolState.Running -> Triple("🔧", Color(0xFF00838F), "выполняется")
                            core.agent.ToolState.Pending -> Triple("⏳", Color.Gray, "ждёт")
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(icon, fontSize = 11.sp, modifier = Modifier.width(18.dp))
                            Text(
                                inv.tool, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c,
                                modifier = Modifier.width(96.dp), maxLines = 1,
                            )
                            Text(
                                inv.args.replace(Regex("\\s+"), " ").take(70),
                                fontSize = 11.sp, color = Color.DarkGray,
                                modifier = Modifier.weight(1f), maxLines = 1,
                            )
                            Text(note, fontSize = 10.sp, color = Color.Gray, maxLines = 1, modifier = Modifier.width(120.dp))
                        }
                    }
                    log.forEach { Text("· $it", fontSize = 10.sp, color = Color.Gray, maxLines = 2) }
                }
            }
        }
        // Изменённые файлы + diff + откат: результат по заданию должен быть на руках.
        if (run.changedFiles.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "изменено файлов: ${run.changedFiles.size} — ${run.changedFiles.joinToString(", ").take(90)}",
                    fontSize = 11.sp, color = Color(0xFF2E7D32), modifier = Modifier.weight(1f), maxLines = 2,
                )
                TextButton(onClick = { vm.revertRun(run.id) }) { Text("откатить", fontSize = 11.sp) }
            }
            var showDiff by remember(run.id, run.updatedAt) { mutableStateOf(false) }
            TextButton(onClick = { showDiff = !showDiff }) {
                Text(if (showDiff) "скрыть diff" else "показать diff", fontSize = 11.sp)
            }
            if (showDiff) {
                Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Color(0xFFFAFAFA)).padding(6.dp)) {
                    Text(
                        run.diff.take(DIFF_PREVIEW_CHARS),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    )
                }
                if (run.diff.length > DIFF_PREVIEW_CHARS) {
                    Text("… diff обрезан; полный — в agent_runs.json или `week6 agent-diff`", fontSize = 10.sp, color = Color.Gray)
                }
            }
        }
    }
}

// Разрешение на запись — карточкой в чате, а не модальным окном: видно, какой
// прогон и какой файл ждут ответа, и решение остаётся в истории ленты.
@Composable
fun ApprovalCard(vm: AppViewModel, change: core.agent.PendingChange) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .background(Color(0xFFFFF3E0)).padding(8.dp),
    ) {
        Text("Нужно разрешение на запись", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF6C00))
        Text(
            "${change.action} ${change.path} · замен: ${change.replacements}" +
                if (change.summary.isNotBlank()) "\n${change.summary}" else "",
            fontSize = 12.sp,
        )
        Text("diff изменения", fontSize = 10.sp, color = Color.Gray)
        Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).background(Color.White).padding(6.dp)) {
            Text(change.diff.take(DIFF_PREVIEW_CHARS), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::approveOnce) { Text("Разрешить", fontSize = 12.sp) }
            OutlinedButton(onClick = vm::approveAlways) { Text("Всегда для этого правила", fontSize = 12.sp) }
            TextButton(onClick = vm::rejectAgent) { Text("Отклонить", fontSize = 12.sp, color = Color(0xFFB00020)) }
        }
    }
}

@Composable
fun MessageBubble(m: RagMessage) {
    val bg = if (m.role == "user") Color(0xFFE3F2FD) else Color(0xFFF1F1F1)
    // Контейнер выделения — на ленте целиком (см. список сообщений), поэтому
    // само сообщение остаётся обычным Column без вложенного SelectionContainer.
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(bg).padding(8.dp)) {
        Text(m.role, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        // Task 3: диагностика второго этапа ("найдено 20 → в контекст 4...").
        if (m.info.isNotBlank()) {
            Text(m.info, fontSize = 10.sp, color = Color(0xFF888888))
            Spacer(Modifier.height(2.dp))
        }
        if (m.role == "user") {
            Text(m.content, fontSize = 14.sp)
        } else {
            Markdown(m.content, typography = chatMarkdownTypography(), modifier = Modifier.fillMaxWidth())
        }
        // Task 4: компактно — в ответе только короткие [N], детали скрыты под раскрывашкой.
        val refCount = if (m.refs.isNotEmpty()) m.refs.size else m.sources.size
        if (refCount > 0) {
            var expanded by remember(m.id) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "▲ источники ($refCount)" else "▼ источники ($refCount)", fontSize = 11.sp)
            }
            if (expanded) {
                if (m.refs.isNotEmpty()) {
                    m.refs.forEach { r ->
                        Text(
                            "[${r.index}] ${r.title}" +
                                (if (r.section.isNotBlank()) " / ${r.section}" else "") +
                                " (${"%.3f".format(r.score)})",
                            fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${r.source} · ${r.chunkId.ifBlank { "—" }}",
                            fontSize = 10.sp, color = Color(0xFF888888), maxLines = 1,
                        )
                        if (r.excerpt.isNotBlank()) {
                            Text("“${r.excerpt}”", fontSize = 11.sp, color = Color(0xFF555555))
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                } else {
                    m.sources.forEach { s -> Text(s, fontSize = 11.sp, color = Color.Gray) }
                }
            }
        }
    }
}

@Composable
fun DocPane(vm: AppViewModel, source: String, doc: DocInfo?, text: String) {
    Column(Modifier.fillMaxHeight().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(doc?.title ?: source.substringAfterLast("/"), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = vm::closeDoc) { Text("закрыть") }
        }
        if (doc != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${doc.chunks} чанков · ${doc.strategyLabel.ifBlank { "…" }} · ~${doc.chars} симв",
                    fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.toggleDoc(source) }) {
                    Text(if (doc.active) "выключить из RAG" else "включить в RAG", fontSize = 12.sp)
                }
            }
        }
        Text(source, fontSize = 10.sp, color = Color.Gray, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).background(Color(0xFFF9F9F9)).padding(10.dp)) {
            SelectionContainer {
                Markdown(text.take(30_000), typography = chatMarkdownTypography(), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun SettingsDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    val isLocal = st.llmProvider == LlmSettings.PROVIDER_OLLAMA
    AlertDialog(
        onDismissRequest = vm::closeSettings,
        title = { Text("Настройки") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // --- Модель языка (как Week 5: локальные on-device + облако) ---
                Text("Модель языка", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = isLocal,
                        onClick = { vm.setLlmProvider(LlmSettings.PROVIDER_OLLAMA) },
                        label = { Text("Локальная (Ollama)", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = !isLocal,
                        onClick = { vm.setLlmProvider(LlmSettings.PROVIDER_DEEPSEEK) },
                        label = { Text("DeepSeek (облако)", fontSize = 12.sp) },
                    )
                }
                if (isLocal) {
                    Text(
                        "Локальная модель через Ollama (`ollama serve` + `ollama pull <модель>`). Ключ не нужен. Для M3 Pro 64 ГБ рекомендовано qwen3:32b.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                    OutlinedTextField(
                        value = st.llmModel, onValueChange = vm::setLlmModel,
                        label = { Text("Модель") }, placeholder = { Text(LlmSettings.DEFAULT_OLLAMA_MODEL) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.llmBusy,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LlmSettings.LLM_PRESETS.forEach { (id, desc) ->
                            FilterChip(
                                selected = st.llmModel.trim() == id,
                                onClick = { vm.setLlmModel(id) },
                                label = { Text("$id — $desc", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = st.llmUrl, onValueChange = vm::setLlmUrl,
                        label = { Text("Адрес Ollama (хост:порт)") }, placeholder = { Text(LlmSettings.DEFAULT_OLLAMA_URL) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.llmBusy,
                    )
                } else {
                    Text(
                        "Облачный DeepSeek через OpenAI-совместимый API. Нужен DEEPSEEK_API_KEY (env, корневой .env или поле ниже).",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                    OutlinedTextField(
                        value = st.llmModel, onValueChange = vm::setLlmModel,
                        label = { Text("Модель") }, placeholder = { Text(LlmSettings.DEFAULT_DEEPSEEK_MODEL) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.llmBusy,
                    )
                    OutlinedTextField(
                        value = st.llmKeyInput, onValueChange = vm::setLlmKeyInput,
                        label = { Text(if (st.llmHasKey) "Новый ключ (есть сохранённый)" else "API-ключ") },
                        placeholder = { Text("sk-…") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.llmBusy,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::testLlm, enabled = !st.llmBusy) {
                        Text(if (st.llmBusy) "..." else "Проверить LLM")
                    }
                    Spacer(Modifier.width(8.dp))
                    val check = st.llmCheck
                    if (check != null) {
                        Text(
                            check, fontSize = 11.sp,
                            color = if (check.startsWith("ОШИБКА")) Color(0xFFB00020) else Color(0xFF2E7D32),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // --- Эмбеддинг-модель ---
                Text("Эмбеддинг-модель", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "Локальная модель через Ollama. Векторизуется только документация (README/docs/*.md/схемы) — код не эмбеддится. После смены модели нужна переиндексация.",
                    fontSize = 11.sp, color = Color.Gray,
                )
                OutlinedTextField(
                    value = st.settingsModel, onValueChange = vm::setSettingsModel,
                    label = { Text("Модель") }, placeholder = { Text("bge-m3") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.settingsBusy,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LlmSettings.EMBED_PRESETS.forEach { (id, desc) ->
                        FilterChip(
                            selected = st.settingsModel.trim() == id,
                            onClick = { vm.setSettingsModel(id) },
                            label = { Text("$id — $desc", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                OutlinedTextField(
                    value = st.settingsUrl, onValueChange = vm::setSettingsUrl,
                    label = { Text("Адрес Ollama (хост:порт)") }, placeholder = { Text("http://localhost:11434") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.settingsBusy,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::testSettings, enabled = !st.settingsBusy) {
                        Text(if (st.settingsBusy) "..." else "Проверить")
                    }
                    Spacer(Modifier.width(8.dp))
                    val check = st.settingsCheck
                    if (check != null) {
                        Text(
                            check, fontSize = 11.sp,
                            color = if (check.startsWith("ОШИБКА")) Color(0xFFB00020) else Color(0xFF2E7D32),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = vm::saveSettings, enabled = !st.settingsBusy && !st.llmBusy) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = vm::closeSettings) { Text("Отмена") } },
    )
}

// Задание 3: настройки RAG — применяются сразу, без кнопки "Сохранить".
// Структура повторяет пайплайн: top-K до → rewrite → фильтр (температура + top-K после).
@Composable
fun RagSettingsDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = vm::closeRagSettings,
        title = { Text("Настройки RAG") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 1. Top-K до фильтрации.
                Text("Top-K (до фильтрации)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = st.topK.toString(), onValueChange = vm::setTopK,
                    label = { Text("Чанков забрать (1–50)", fontSize = 11.sp) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Сколько лучших чанков взять из индекса. Рекомендуется 10–30 (дефолт 20): " +
                        "меньше 10 — можно пропустить нужное, больше 30 — дольше и шумнее.",
                    fontSize = 11.sp, color = Color.Gray,
                )
                // 2. Rewrite query с помощью LLM.
                Text("Rewrite запроса", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = st.rewriteEnabled,
                        onClick = { vm.setRewriteEnabled(!st.rewriteEnabled) },
                        label = { Text(if (st.rewriteEnabled) "Включён" else "Выключен", fontSize = 12.sp) },
                    )
                }
                Text(
                    "Модель переписывает вопрос в 1–2 поисковых запроса («а при чём тут Косово?» → «сербское кос, чёрный дрозд»). " +
                        "Помогает коротким и разговорным вопросам; стоит +1 запрос к DeepSeek.",
                    fontSize = 11.sp, color = Color.Gray,
                )
                // 3. Фильтрация вкл/выкл.
                Text("Фильтрация", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !st.filterEnabled,
                        onClick = { vm.setFilterEnabled(false) },
                        label = { Text("Выкл", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = st.filterEnabled,
                        onClick = { vm.setFilterEnabled(true) },
                        label = { Text("Вкл", fontSize = 12.sp) },
                    )
                }
                if (!st.filterEnabled) {
                    Text(
                        "Фильтр выключен: первые top-K чанков идут модели как есть (режим Task 2). " +
                            "Быстро, но мусор тоже попадает в контекст.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                } else {
                    Text(
                        "Фильтр включён: сначала отсечение по температуре, затем опциональный top-K после. " +
                            "Если не осталось ничего — ассистент честно скажет, что не нашёл.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                    // Температура — точность ответа, 0..1.
                    Text(
                        "Температура: ${"%.2f".format(st.temperature)}",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                    Slider(
                        value = st.temperature,
                        onValueChange = vm::setTemperatureSlider,
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Порог отсечения по похожести: выше — строже, но можно отрезать и нужное. " +
                            "Стартовая вилка 0.3–0.5: ответы пустые — снижай, в ответ лезет мусор — повышай.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                    // Top-K после фильтрации — опционален, 1..top-K до.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = st.postFilterK != null,
                            onClick = { vm.setPostFilterKEnabled(st.postFilterK == null) },
                            label = { Text("Ограничить top-K после", fontSize = 12.sp) },
                        )
                    }
                    OutlinedTextField(
                        value = st.postFilterK?.toString() ?: "",
                        onValueChange = vm::setPostFilterK,
                        label = { Text("Top-K после (1–${st.topK}, пусто = все)", fontSize = 11.sp) },
                        singleLine = true, enabled = st.postFilterK != null,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("без ограничения", fontSize = 11.sp) },
                    )
                    Text(
                        "Повторная обрезка после фильтра по температуре: в контекст уйдёт не больше N чанков. " +
                            "Пусто — идут все прошедшие порог.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                }
                Text(
                    if (!st.filterEnabled) {
                        "Итого сейчас: забрать ${st.topK} → в контекст ${st.topK} без фильтра."
                    } else if (st.postFilterK != null) {
                        "Итого сейчас: забрать ${st.topK} → фильтр (темп. ${"%.2f".format(st.temperature)}) → в ответ до ${st.postFilterK}."
                    } else {
                        "Итого сейчас: забрать ${st.topK} → фильтр (темп. ${"%.2f".format(st.temperature)}) → в ответ все прошедшие."
                    },
                    fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = { Button(onClick = vm::closeRagSettings) { Text("Готово") } },
        dismissButton = {},
    )
}

@Composable
fun CreateChatDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    val agent = st.createKind == CHAT_KIND_AGENT
    AlertDialog(
        onDismissRequest = vm::closeCreateChat,
        title = { Text(if (agent) "Новый агентный чат" else "Новый чат") },
        text = {
            Column {
                Text(
                    if (agent) "Цель ставишь ты, файлы выбирает агент. Прогоны и diff хранятся в этом чате."
                    else "Переписка с вопросами по базе знаний проекта.",
                    fontSize = 12.sp, color = Color.Gray,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = st.createName, onValueChange = vm::setCreateName,
                    label = { Text("Название") }, singleLine = true,
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        // Название всегда в одну строку — Enter / NumPadEnter сразу создаёт чат.
                        if (isConfirmKey(e)) {
                            vm.commitCreateChat()
                            true
                        } else false
                    },
                )
            }
        },
        confirmButton = { Button(onClick = vm::commitCreateChat) { Text("Создать") } },
        dismissButton = { TextButton(onClick = vm::closeCreateChat) { Text("Отмена") } },
    )
}

@Composable
fun ChunkStrategyPicker(selected: String, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Чанкинг:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selected != "structure",
                onClick = { onToggle("fixed") },
                label = { Text("Фикс. размер", fontSize = 12.sp) },
            )
            FilterChip(
                selected = selected != "fixed",
                onClick = { onToggle("structure") },
                label = { Text("По структуре", fontSize = 12.sp) },
            )
        }
    }
}

@Composable
fun AddProjectDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = vm::closeAddProject,
        title = { Text("Новый проект") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Git-URL клонируется в локальные repos, путь — индексируется на месте. Имя подставится из хвоста пути, можно править.",
                    fontSize = 11.sp, color = Color.Gray,
                )
                OutlinedTextField(
                    value = st.projectInput, onValueChange = vm::setProjectInput,
                    label = { Text("Git-URL или локальный путь") },
                    placeholder = { Text("https://github.com/...git или /path/to/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = st.projectNameInput, onValueChange = vm::setProjectNameInput,
                    label = { Text("Имя (латиница)") },
                    placeholder = { Text("my-service") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        if (isConfirmKey(e) && st.projectNameInput.isNotBlank() && st.projectInput.isNotBlank()) {
                            vm.addProject()
                            true
                        } else false
                    },
                )
                ChunkStrategyPicker(st.addStrategy, vm::toggleChunkPart)
            }
        },
        confirmButton = {
            Button(
                onClick = vm::addProject,
                enabled = st.projectNameInput.isNotBlank() && st.projectInput.isNotBlank(),
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = vm::closeAddProject) { Text("Отмена") } },
    )
}

@Composable
fun WikiDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = vm::closeWiki,
        title = { Text("Статья Википедии") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = st.wikiInput, onValueChange = vm::setWikiInput,
                    label = { Text("Название или URL") },
                    placeholder = { Text("Искусственный интеллект") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        // Enter / NumPadEnter — то же, что кнопка «Загрузить».
                        if (isConfirmKey(e) && st.wikiInput.isNotBlank()) {
                            vm.fetchWiki()
                            true
                        } else false
                    },
                )
                ChunkStrategyPicker(st.addStrategy, vm::toggleChunkPart)
            }
        },
        confirmButton = { Button(onClick = vm::fetchWiki, enabled = st.wikiInput.isNotBlank()) { Text("Загрузить") } },
        dismissButton = { TextButton(onClick = vm::closeWiki) { Text("Отмена") } },
    )
}

// ===== Task 4: панель агента, диалог подтверждения и diff прогона =====

private val STATE_COLORS = mapOf(
    "goal" to Color(0xFF1E88E5),
    "thinking" to Color(0xFF6A1B9A),
    "awaiting" to Color(0xFFEF6C00),
    "applying" to Color(0xFF00838F),
    "paused" to Color(0xFFEF6C00),
    "done" to Color(0xFF2E7D32),
    "failed" to Color(0xFFB00020),
    "idle" to Color(0xFF757575),
    "cancelled" to Color(0xFF9E9E9E),
)

private fun stateKey(run: core.agent.AgentRun): String = when (run.state) {
    is core.agent.RunState.Idle -> "idle"
    is core.agent.RunState.Goal -> "goal"
    is core.agent.RunState.Thinking -> "thinking"
    is core.agent.RunState.AwaitingApproval -> "awaiting"
    is core.agent.RunState.Applying -> "applying"
    is core.agent.RunState.Paused -> "paused"
    is core.agent.RunState.Done -> "done"
    is core.agent.RunState.Failed -> "failed"
    is core.agent.RunState.Cancelled -> "cancelled"
}


private const val DIFF_PREVIEW_CHARS = 20_000
