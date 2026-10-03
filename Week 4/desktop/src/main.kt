package desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import core.rag.DocInfo
import core.rag.RagChat
import core.rag.RagMessage
import desktop.ui.AppViewModel
import desktop.ui.chatMarkdownTypography

fun main() = application {
    val windowState = rememberWindowState(width = 1100.dp, height = 780.dp)
    Window(onCloseRequest = ::exitApplication, title = "Week4 RAG — чаты по своим документам", state = windowState) {
        MaterialTheme { Root() }
    }
}

@Composable
fun Root() {
    val scope = rememberCoroutineScope()
    val vm = remember(scope) { AppViewModel(scope = scope) }
    val st by vm.state.collectAsState()

    Row(Modifier.fillMaxSize()) {
        // Слева: дерево как в Week 2
        Column(
            Modifier.width(340.dp).fillMaxHeight().background(Color(0xFF1E1E1E)).padding(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Чаты", color = Color.White, fontWeight = FontWeight.Bold)
                Button(onClick = vm::openCreateChat) { Text("+") }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(st.chats, key = { "c${it.id}" }) { c ->
                    ChatRow(c, selected = c.id == st.activeChatId,
                        onOpen = { vm.selectChat(c.id) },
                        onToggleRag = { vm.toggleRag(c.id) },
                        onDelete = { vm.deleteChat(c.id) })
                }
                if (st.chats.isEmpty()) {
                    item { Text("Нет чатов — создай через +", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("База знаний", color = Color.White, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::openSettings) { Text("⚙", color = Color(0xFFBBBBBB), fontSize = 15.sp) }
                    Spacer(Modifier.width(2.dp))
                    Button(onClick = vm::openWiki) { Text("+") }
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(st.docs, key = { "d${it.source}" }) { d ->
                    DocRow(d, progress = st.indexProgress[d.source],
                        selected = st.viewingDoc == d.source,
                        onOpen = { vm.viewDoc(d.source) },
                        onToggle = { vm.toggleDoc(d.source) },
                        onDelete = { vm.removeDoc(d.source) })
                }
                if (st.docs.isEmpty()) {
                    item { Text("Пусто — нажми + и добавь статью Википедии", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("⚙ ${st.embedLabel}", color = Color(0xFF666666), fontSize = 10.sp, maxLines = 1)
            Text(st.indexSummary, color = Color(0xFF888888), fontSize = 11.sp, maxLines = 2)
            if (st.indexLog.isNotEmpty()) {
                Text(st.indexLog.last(), color = Color(0xFF666666), fontSize = 10.sp, maxLines = 1)
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
    if (st.showSettings) SettingsDialog(vm)
    if (st.showRagSettings) RagSettingsDialog(vm)
}

@Composable
fun ChatRow(c: RagChat, selected: Boolean, onOpen: () -> Unit, onToggleRag: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF2D2D2D) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "💬", fontSize = 13.sp,
            modifier = Modifier.padding(end = 6.dp).clickable(onClick = onToggleRag),
            color = if (c.ragEnabled) Color(0xFF7DD87D) else Color(0xFF777777),
        )
        Text(
            c.name + if (c.ragEnabled) "" else "  (без RAG)",
            color = if (selected) Color(0xFF7DD87D) else Color(0xFF9CCC9C),
            fontSize = 14.sp, modifier = Modifier.weight(1f).clickable(onClick = onOpen), maxLines = 1,
        )
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
        val messages = st.messages
        val listState = rememberLazyListState()
        LaunchedEffect(chat.id, messages.size, st.busy) {
            if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            items(messages, key = { it.id }) { m -> MessageBubble(m) }
            if (st.busy) {
                item(key = "typing") {
                    Text("Печатает…", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = st.input, onValueChange = vm::setInput,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // Enter — отправить, Shift+Enter — новая строка (дефолт текстового поля).
                    if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) {
                        vm.send()
                        true
                    } else false
                },
                placeholder = { Text(if (chat.ragEnabled) "вопрос по документам…" else "сообщение…") },
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
fun MessageBubble(m: RagMessage) {
    val bg = if (m.role == "user") Color(0xFFE3F2FD) else Color(0xFFF1F1F1)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(bg).padding(8.dp)) {
        Text(m.role, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        // Task 3: диагностика второго этапа ("найдено 20 → в контекст 4...").
        if (m.info.isNotBlank()) {
            Text(m.info, fontSize = 10.sp, color = Color(0xFF888888))
            Spacer(Modifier.height(2.dp))
        }
        SelectionContainer {
            if (m.role == "user") {
                Text(m.content, fontSize = 14.sp)
            } else {
                Markdown(m.content, typography = chatMarkdownTypography(), modifier = Modifier.fillMaxWidth())
            }
        }
        if (m.sources.isNotEmpty()) {
            var expanded by remember(m.id) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "▲ источники (${m.sources.size})" else "▼ источники (${m.sources.size})", fontSize = 11.sp)
            }
            if (expanded) {
                m.sources.forEach { s -> Text(s, fontSize = 11.sp, color = Color.Gray) }
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
    AlertDialog(
        onDismissRequest = vm::closeSettings,
        title = { Text("Эмбеддинг-модель") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Локальная модель через Ollama. После смены модели нужна переиндексация.", fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(
                    value = st.settingsModel, onValueChange = vm::setSettingsModel,
                    label = { Text("Модель") }, placeholder = { Text("bge-m3") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.settingsBusy,
                )
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
        confirmButton = { Button(onClick = vm::saveSettings, enabled = !st.settingsBusy) { Text("Сохранить") } },
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = st.temperature.toString(), onValueChange = vm::setTemperature,
                        label = { Text("Температура — точность (0–1)", fontSize = 11.sp) }, singleLine = true,
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
                        "Итого сейчас: забрать ${st.topK} → фильтр (темп. ${st.temperature}) → в ответ до ${st.postFilterK}."
                    } else {
                        "Итого сейчас: забрать ${st.topK} → фильтр (темп. ${st.temperature}) → в ответ все прошедшие."
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
    AlertDialog(
        onDismissRequest = vm::closeCreateChat,
        title = { Text("Новый чат") },
        text = {
            OutlinedTextField(
                value = st.createName, onValueChange = vm::setCreateName,
                label = { Text("Название") }, singleLine = true,
                modifier = Modifier.onPreviewKeyEvent { e ->
                    // Название всегда в одну строку — Enter сразу создаёт чат.
                    if (e.key == Key.Enter && e.type == KeyEventType.KeyDown) {
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
fun ChunkStrategyPicker(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Чанкинг:", fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StrategyChip("Обе", "both", selected, onSelect)
            StrategyChip("Фикс. размер", "fixed", selected, onSelect)
            StrategyChip("По структуре", "structure", selected, onSelect)
        }
    }
}

@Composable
fun StrategyChip(label: String, value: String, selected: String, onSelect: (String) -> Unit) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(label, fontSize = 12.sp) },
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
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                ChunkStrategyPicker(st.addStrategy, vm::setAddStrategy)
            }
        },
        confirmButton = { Button(onClick = vm::fetchWiki, enabled = st.wikiInput.isNotBlank()) { Text("Загрузить") } },
        dismissButton = { TextButton(onClick = vm::closeWiki) { Text("Отмена") } },
    )
}
