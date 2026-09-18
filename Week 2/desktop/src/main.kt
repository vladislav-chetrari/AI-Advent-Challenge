package desktop

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import core.domain.Chat
import core.domain.MemoryDoc
import core.domain.Scope
import desktop.ui.AppViewModel
import desktop.ui.CreateKind
import desktop.ui.Selection

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
                // General chats
                item {
                    TreeHeader(" general")
                    st.chats.filter { it.scope == Scope.GENERAL }.forEach { c ->
                        ChatRow(c.name, selected = st.selection == Selection.ChatSel(c.id)) { vm.select(Selection.ChatSel(c.id)) }
                    }
                    st.docs.filter { it.scope == Scope.GENERAL }.forEach { d ->
                        DocRow(d, st.docContents[d.id].orEmpty(), st.selection is Selection.DocSel && (st.selection as Selection.DocSel).docId == d.id,
                            onOpen = { vm.select(Selection.DocSel(d.id)) }, onToggle = { vm.toggleDoc(d.id) })
                    }
                }
                // Projects
                items(st.projects, key = { "p${it.id}" }) { p ->
                    TreeHeader("▾ \uD83D\uDCC1 ${p.name}")
                    // project docs + chats
                    st.docs.filter { it.scope == Scope.PROJECT && it.parentId == p.id }.forEach { d ->
                        DocRow(d, null, st.selection is Selection.DocSel && (st.selection as Selection.DocSel).docId == d.id,
                            onOpen = { vm.select(Selection.DocSel(d.id)) }, onToggle = { vm.toggleDoc(d.id) })
                    }
                    st.chats.filter { it.scope == Scope.PROJECT && it.parentId == p.id }.forEach { c ->
                        ChatRow("  ☰ ${c.name}", selected = st.selection == Selection.ChatSel(c.id)) { vm.select(Selection.ChatSel(c.id)) }
                    }
                    // tasks
                    st.tasks.filter { it.projectId == p.id }.forEach { t ->
                        TreeHeader("    ▾ \uD83D\uDCC1 ${t.name}")
                        st.docs.filter { it.scope == Scope.TASK && it.parentId == t.id }.forEach { d ->
                            DocRow(d, null, st.selection is Selection.DocSel && (st.selection as Selection.DocSel).docId == d.id,
                                onOpen = { vm.select(Selection.DocSel(d.id)) }, onToggle = { vm.toggleDoc(d.id) })
                        }
                        st.chats.filter { it.scope == Scope.TASK && it.parentId == t.id }.forEach { c ->
                            ChatRow("      ☰ ${c.name}", selected = st.selection == Selection.ChatSel(c.id)) { vm.select(Selection.ChatSel(c.id)) }
                        }
                    }
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
}

@Composable
fun TreeHeader(t: String) {
    Text(t, color = Color(0xFFBBBBBB), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
fun ChatRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Color(0xFF7DD87D) else Color(0xFF9CCC9C),
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) Color(0xFF2D2D2D) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
fun DocRow(d: MemoryDoc, preview: String?, selected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen)
            .background(if (selected) Color(0xFF2D2D2D) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (d.active) "M↓ " else "M✕ ", color = Color(0xFF64B5F6), fontSize = 13.sp)
        Text(d.title + ".md", color = Color(0xFF7FC97F), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Checkbox(checked = d.active, onCheckedChange = { onToggle() })
    }
}

@Composable
fun ChatPane(vm: AppViewModel, chat: Chat) {
    val st by vm.state.collectAsState()
    Column(Modifier.fillMaxHeight()) {
    // Шапка: системный промпт collapse/extend
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(chat.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::toggleSystem) { Text(if (st.systemExpanded) "▲ prompt" else "▼ prompt") }
        TextButton(onClick = vm::clearChat) { Text("очистить") }
    }
    if (st.systemExpanded) {
        SelectionContainer {
            Text(
                st.systemPrompt.ifBlank { "(память пуста, только base prompt)" },
                fontSize = 11.sp, color = Color.Gray,
                modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(8.dp)
            )
        }
        Text("~${st.systemPrompt.length / 4} tok (оценка) · last prompt_tokens=${st.promptTokens}", fontSize = 11.sp, color = Color.Gray)
    }
    Spacer(Modifier.height(6.dp))
    val messages = remember(st.chats, st.docs, st.docContents, chat.id) { vm.messagesOf(chat.id) }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false) {
        items(messages, key = { it.hashCode().toString() + it.content.take(20) }) { m ->
            MessageBubble(vm, chat, m.role, m.content)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = st.input, onValueChange = vm::onInput,
            modifier = Modifier.weight(1f), placeholder = { Text("сообщение...") },
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
    var menu by remember { mutableStateOf(false) }
    val bg = if (role == "user") Color(0xFFE3F2FD) else Color(0xFFF1F1F1)
    Box(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .background(bg).padding(8.dp)
    ) {
        Column {
            Text(role, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            SelectionContainer { Text(text, fontSize = 14.sp) }
            Row {
                TextButton(onClick = { menu = true }) { Text("⋯", fontSize = 12.sp) }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Сохранить в память") }, onClick = {
                menu = false
                vm.startSave(text, chat.scope, chat.parentId)
            })
        }
    }
}

@Composable
fun MemoryPane(vm: AppViewModel, doc: MemoryDoc, content: String) {
    Text(doc.title + ".md", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Text("${doc.scope} ${doc.parentId ?: ""} · ${if (doc.active) "active (инжектится)" else "выключен"}", fontSize = 12.sp, color = Color.Gray)
    Row { TextButton(onClick = { vm.toggleDoc(doc.id) }) { Text(if (doc.active) "выключить" else "включить") }
        TextButton(onClick = { vm.deleteDoc(doc.id) }) { Text("удалить") } }
    Spacer(Modifier.height(6.dp))
    SelectionContainer {
        Text(content.ifBlank { "(пусто)" }, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Color(0xFFF9F9F9)).padding(10.dp))
    }
}

@Composable
fun CreateDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    var kindExpanded by remember { mutableStateOf(false) }
    var parentExpanded by remember { mutableStateOf(false) }
    val needParent = st.createKind == CreateKind.PROJECT_CHAT || st.createKind == CreateKind.TASK || st.createKind == CreateKind.TASK_CHAT
    val parentOptions: List<Pair<String, String>> = when (st.createKind) {
        CreateKind.PROJECT_CHAT, CreateKind.TASK -> st.projects.map { it.id to "📁 ${it.name}" }
        CreateKind.TASK_CHAT -> st.tasks.map { it.id to "📁 ${st.projects.firstOrNull { p -> p.id == it.projectId }?.name ?: "?"} / ${it.name}" }
        else -> emptyList()
    }
    AlertDialog(
        onDismissRequest = vm::closeCreate,
        title = { Text("Создать") },
        text = {
            Column {
                // тип
                Box {
                    OutlinedButton(onClick = { kindExpanded = true }) { Text(st.createKind.title) }
                    DropdownMenu(kindExpanded, onDismissRequest = { kindExpanded = false }) {
                        CreateKind.entries.forEach { k ->
                            DropdownMenuItem(text = { Text(k.title) }, onClick = { vm.setCreateKind(k); kindExpanded = false })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = st.createName, onValueChange = vm::setCreateName, label = { Text("Имя") })
                if (needParent) {
                    Spacer(Modifier.height(8.dp))
                    Box {
                        OutlinedButton(onClick = { parentExpanded = true }) {
                            Text(parentOptions.firstOrNull { it.first == st.createParentId }?.second ?: "Выбери родителя")
                        }
                        DropdownMenu(parentExpanded, onDismissRequest = { parentExpanded = false }) {
                            parentOptions.forEach { (id, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { vm.setCreateParent(id); parentExpanded = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = vm::commitCreate,
                enabled = st.createName.isNotBlank() && (!needParent || st.createParentId != null)
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
