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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mikepenz.markdown.m3.Markdown
import core.api.DocListItem
import desktop.ui.AppViewModel
import desktop.ui.LocalChat
import desktop.ui.LocalMessage
import desktop.ui.chatMarkdownTypography

fun main() = application {
    val windowState = rememberWindowState(width = 1100.dp, height = 780.dp)
    Window(onCloseRequest = ::exitApplication, title = "Task 5 — приватный AI-сервис (localhost)", state = windowState) {
        MaterialTheme { Root() }
    }
}

@Composable
fun Root() {
    val scope = rememberCoroutineScope()
    val vm = remember(scope) { AppViewModel(scope = scope) }
    val st by vm.state.collectAsState()

    Row(Modifier.fillMaxSize()) {
        // Слева: чаты + база знаний (сервер на localhost).
        Column(
            Modifier.width(340.dp).fillMaxHeight().background(Color(0xFF1E1E1E)).padding(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Чаты", color = Color.White, fontWeight = FontWeight.Bold)
                Button(onClick = vm::openCreateChat) { Text("+") }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(st.chats, key = { "c${it.id}" }) { c ->
                    ChatRow(
                        c, selected = c.id == st.activeChatId,
                        onOpen = { vm.selectChat(c.id) },
                        onDelete = { vm.deleteChat(c.id) },
                    )
                }
                if (st.chats.isEmpty()) {
                    item { Text("Нет чатов — создай через +", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("База знаний", color = Color.White, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::refreshDocs, enabled = !st.docsBusy) {
                        Text(if (st.docsBusy) "…" else "⟳", color = Color(0xFFBBBBBB), fontSize = 14.sp)
                    }
                    TextButton(onClick = vm::openSettings) { Text("⚙", color = Color(0xFFBBBBBB), fontSize = 15.sp) }
                    Spacer(Modifier.width(2.dp))
                    Button(onClick = vm::openAddDoc) { Text("+") }
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(st.docs, key = { "d${it.id}" }) { d ->
                    DocRow(d, onDelete = { vm.deleteDoc(d.id) })
                }
                if (st.docs.isEmpty()) {
                    item { Text("Пусто — нажми + и добавь wiki-статью или свой текст", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("⛁ ${st.baseUrl}", color = Color(0xFF666666), fontSize = 10.sp, maxLines = 1)
            Text(st.healthLine, color = Color(0xFF888888), fontSize = 11.sp, maxLines = 2)
            Text(st.docsSummary, color = Color(0xFF666666), fontSize = 10.sp, maxLines = 1)
        }
        // Справа: чат к localhost-серверу.
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val chat = st.chats.firstOrNull { it.id == st.activeChatId }
            if (chat == null) {
                Text("Выбери чат слева или создай новый (+)", modifier = Modifier.padding(12.dp))
            } else {
                ChatPane(vm, chat)
            }
            st.status?.let {
                Text(it, color = Color(0xFFB00020), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }

    if (st.showCreateChat) CreateChatDialog(vm)
    if (st.showAddDoc) AddDocDialog(vm)
    if (st.showSettings) SettingsDialog(vm)
}

@Composable
fun ChatRow(c: LocalChat, selected: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF2D2D2D) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("💬", fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
        Text(
            c.name,
            color = if (selected) Color(0xFF7DD87D) else Color(0xFF9CCC9C),
            fontSize = 14.sp, modifier = Modifier.weight(1f).clickable(onClick = onOpen), maxLines = 1,
        )
        Text("×", color = Color(0xFF777777), fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDelete).padding(start = 4.dp))
    }
}

@Composable
fun DocRow(d: DocListItem, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📄", color = Color(0xFF64B5F6), fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                d.title.ifBlank { d.id }, color = Color(0xFF9CCC9C),
                fontSize = 13.sp, maxLines = 1,
            )
            Text(
                "${d.chunks} чанков · ~${d.chars} симв",
                color = Color(0xFF777777), fontSize = 10.sp, maxLines = 1,
            )
            Text(d.id, color = Color(0xFF555555), fontSize = 10.sp, maxLines = 1)
        }
        Text("×", color = Color(0xFF777777), fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDelete).padding(start = 4.dp))
    }
}

// Enter с основной клавиатуры и с NumPad — подтверждение (отправка/создание/загрузка).
private fun isConfirmKey(e: KeyEvent): Boolean =
    (e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyDown

@Composable
fun ChatPane(vm: AppViewModel, chat: LocalChat) {
    val st by vm.state.collectAsState()
    Column(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(chat.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                st.model.ifBlank { "default" } + " · ${st.temperature} · ${st.maxTokens}",
                fontSize = 11.sp, color = Color.Gray, maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = vm::openSettings) { Text("⚙", fontSize = 13.sp) }
            TextButton(onClick = vm::clearChat) { Text("очистить", fontSize = 12.sp) }
        }
        val messages = st.messages
        val listState = rememberLazyListState()
        LaunchedEffect(chat.id, messages.size, st.busy) {
            if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 20.dp)) {
                items(messages, key = { it.id }) { m -> MessageBubble(m) }
                if (st.busy) {
                    item(key = "typing") {
                        Text("Печатает…", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp))
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
                    // Enter / NumPadEnter — отправить, Shift+Enter — новая строка.
                    if (isConfirmKey(e) && !e.isShiftPressed) {
                        vm.send()
                        true
                    } else false
                },
                placeholder = { Text("вопрос к localhost-серверу…") },
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
fun MessageBubble(m: LocalMessage) {
    val bg = if (m.role == "user") Color(0xFFE3F2FD) else Color(0xFFF1F1F1)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(bg).padding(8.dp)) {
        Text(m.role, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        SelectionContainer {
            if (m.role == "user") {
                Text(m.content, fontSize = 14.sp)
            } else {
                Markdown(m.content, typography = chatMarkdownTypography(), modifier = Modifier.fillMaxWidth())
            }
        }
        if (m.meta.isNotBlank()) {
            Text(m.meta, fontSize = 10.sp, color = Color(0xFF888888))
        }
    }
}

@Composable
fun CreateChatDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = vm::closeCreateChat,
        title = { Text("Новый чат") },
        text = {
            OutlinedTextField(
                value = st.createName, onValueChange = vm::setCreateName,
                label = { Text("Название") }, singleLine = true,
                modifier = Modifier.onPreviewKeyEvent { e ->
                    if (isConfirmKey(e)) {
                        vm.commitCreateChat()
                        true
                    } else false
                },
            )
        },
        confirmButton = { Button(onClick = vm::commitCreateChat) { Text("Создать") } },
        dismissButton = { TextButton(onClick = vm::closeCreateChat) { Text("Отмена") } },
    )
}

@Composable
fun AddDocDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = vm::closeAddDoc,
        title = { Text("Документ в базу (localhost)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = st.docType == "wiki",
                        onClick = { vm.setDocType("wiki") },
                        label = { Text("Wiki-статья", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = st.docType == "text",
                        onClick = { vm.setDocType("text") },
                        label = { Text("Свой текст", fontSize = 12.sp) },
                    )
                }
                if (st.docType == "text") {
                    OutlinedTextField(
                        value = st.docTitle, onValueChange = vm::setDocTitle,
                        label = { Text("Заголовок (необязательно)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = st.docInput, onValueChange = vm::setDocInput,
                        label = { Text("Текст (минимум 50 символов)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10,
                    )
                } else {
                    OutlinedTextField(
                        value = st.docInput, onValueChange = vm::setDocInput,
                        label = { Text("Название или URL статьи") },
                        placeholder = { Text("Ворон") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                            if (isConfirmKey(e) && st.docInput.isNotBlank()) {
                                vm.commitAddDoc()
                                true
                            } else false
                        },
                    )
                    Text(
                        "Сервер сам скачает статью из Википедии и проиндексирует.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = vm::commitAddDoc, enabled = st.docInput.isNotBlank() && !st.docsBusy) {
                Text("Загрузить")
            }
        },
        dismissButton = { TextButton(onClick = vm::closeAddDoc) { Text("Отмена") } },
    )
}

@Composable
fun SettingsDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = vm::closeSettings,
        title = { Text("Подключение к серверу") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Все запросы идут на свой сервер (дефолт localhost:8080).", fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(
                    value = st.settingsBaseUrl, onValueChange = vm::setSettingsBaseUrl,
                    label = { Text("Base URL") }, placeholder = { Text("http://localhost:8080") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.settingsBusy,
                )
                OutlinedTextField(
                    value = st.settingsKey, onValueChange = vm::setSettingsKey,
                    label = { Text("API-ключ (пусто = без auth)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.settingsBusy,
                )
                OutlinedTextField(
                    value = st.settingsModel, onValueChange = vm::setSettingsModel,
                    label = { Text("Модель (пусто = дефолт сервера)") }, placeholder = { Text("qwen2.5:7b") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.settingsBusy,
                )
                if (st.models.isNotEmpty()) {
                    Text("Модели сервера: ${st.models.joinToString()}", fontSize = 11.sp, color = Color.Gray)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = st.settingsTemp, onValueChange = vm::setSettingsTemp,
                        label = { Text("Температура") }, singleLine = true,
                        modifier = Modifier.weight(1f), enabled = !st.settingsBusy,
                    )
                    OutlinedTextField(
                        value = st.settingsMaxTokens, onValueChange = vm::setSettingsMaxTokens,
                        label = { Text("Max tokens") }, singleLine = true,
                        modifier = Modifier.weight(1f), enabled = !st.settingsBusy,
                    )
                }
                OutlinedTextField(
                    value = st.settingsSystem, onValueChange = vm::setSettingsSystem,
                    label = { Text("System-промпт (необязательно)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    enabled = !st.settingsBusy,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedButton(onClick = vm::testSettings, enabled = !st.settingsBusy) {
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
        confirmButton = { Button(onClick = vm::saveSettings, enabled = !st.settingsBusy) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = vm::closeSettings) { Text("Отмена") } },
    )
}
