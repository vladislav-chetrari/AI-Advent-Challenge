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
import com.mikepenz.markdown.m3.Markdown
import core.llm.LlmSettings
import core.project.ProjectEntry
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
    Window(onCloseRequest = ::exitApplication, title = "Week6 Dev Assistant вЂ” Р°СЃСЃРёСЃС‚РµРЅС‚ СЂР°Р·СЂР°Р±РѕС‚С‡РёРєР°", state = windowState) {
        MaterialTheme { Root() }
    }
}

@Composable
fun Root() {
    val scope = rememberCoroutineScope()
    val vm = remember(scope) { AppViewModel(scope = scope) }
    val st by vm.state.collectAsState()

    Row(Modifier.fillMaxSize()) {
        // РЎР»РµРІР°: РґРІРµ СЃРµРєС†РёРё С‡Р°С‚РѕРІ вЂ” РёРЅС„РѕСЂРјР°С†РёРѕРЅРЅС‹Рµ Рё Р°РіРµРЅС‚РЅС‹Рµ
        Column(
            Modifier.width(340.dp).fillMaxHeight().background(Color(0xFF1E1E1E)).padding(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Р§Р°С‚С‹", color = Color.White, fontWeight = FontWeight.Bold)
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
                    item { Text("РќРµС‚ С‡Р°С‚РѕРІ вЂ” СЃРѕР·РґР°Р№ С‡РµСЂРµР· +", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            // РђРіРµРЅС‚РЅС‹Рµ С‡Р°С‚С‹ вЂ” РѕС‚РґРµР»СЊРЅРѕР№ СЃРµРєС†РёРµР№: Сѓ РЅРёС… СЃРІРѕСЏ РёСЃС‚РѕСЂРёСЏ РїСЂРѕРіРѕРЅРѕРІ
            // Рё РёРј РЅРµ РЅСѓР¶РЅС‹ РІРѕРїСЂРѕСЃС‹ РїРѕ Р±Р°Р·Рµ Р·РЅР°РЅРёР№.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("рџЋЇ РђРіРµРЅС‚РЅС‹Рµ С‡Р°С‚С‹", color = Color(0xFF9CCC9C), fontWeight = FontWeight.Bold)
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
                    item { Text("РђРіРµРЅС‚ СЂР°Р±РѕС‚Р°РµС‚ СЃ С„Р°Р№Р»Р°РјРё РїСЂРѕРµРєС‚Р° вЂ” Р·Р°РІРµРґРё С‡Р°С‚ С‡РµСЂРµР· +", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("РџСЂРѕРµРєС‚С‹", color = Color.White, fontWeight = FontWeight.Bold)
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
                    item { Text("РќРµС‚ РїСЂРѕРµРєС‚РѕРІ вЂ” РЅР°Р¶РјРё + (git-URL РёР»Рё Р»РѕРєР°Р»СЊРЅС‹Р№ РїСѓС‚СЊ)", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Р¤Р°Р№Р»С‹ РїСЂРѕРµРєС‚Р°", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                    item { Text(if (st.projects.isEmpty()) "Р”РѕР±Р°РІСЊ РїРµСЂРІС‹Р№ РїСЂРѕРµРєС‚ РєРЅРѕРїРєРѕР№ +" else "Р¤Р°Р№Р»С‹ РїРѕСЏРІСЏС‚СЃСЏ РїРѕСЃР»Рµ РёРЅРґРµРєСЃР°С†РёРё", color = Color(0xFF888888), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("рџ¤– ${st.llmLabel}", color = Color(0xFF666666), fontSize = 10.sp, maxLines = 1)
            Text("вљ™ ${st.embedLabel}", color = Color(0xFF666666), fontSize = 10.sp, maxLines = 1)
            Text(st.indexSummary, color = Color(0xFF888888), fontSize = 11.sp, maxLines = 2)
            if (st.indexLog.isNotEmpty()) {
                Text(st.indexLog.last(), color = Color(0xFF666666), fontSize = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            // Р’РЅРёР·Сѓ СЃР°Р№РґР±Р°СЂР° вЂ” РЅР°СЃС‚СЂРѕР№РєРё LLM Рё СЌРјР±РµРґРґРёРЅРіРѕРІ (РєР°Рє Week 5: РІС‹Р±РѕСЂ РјРѕРґРµР»РµР№).
            OutlinedButton(onClick = vm::openSettings, modifier = Modifier.fillMaxWidth()) {
                Text("вљ™ РќР°СЃС‚СЂРѕР№РєРё", fontSize = 12.sp)
            }
        }
        // РЎРїСЂР°РІР°: С‡Р°С‚ РёР»Рё РґРѕРєСѓРјРµРЅС‚
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val selDoc = st.viewingDoc
            if (selDoc != null) {
                val doc = st.docs.firstOrNull { it.source == selDoc }
                DocPane(vm, selDoc, doc, st.viewingText)
            } else {
                val chat = st.chats.firstOrNull { it.id == st.activeChatId }
                if (chat == null) {
                    Text("Р’С‹Р±РµСЂРё С‡Р°С‚ СЃР»РµРІР° РёР»Рё СЃРѕР·РґР°Р№ РЅРѕРІС‹Р№ (+)", modifier = Modifier.padding(12.dp))
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
    // Task 4: СЂР°Р·СЂРµС€РµРЅРёРµ РЅР° Р·Р°РїРёСЃСЊ РїРѕРєР°Р·С‹РІР°РµС‚СЃСЏ РєР°СЂС‚РѕС‡РєРѕР№ РІ Р»РµРЅС‚Рµ Р°РіРµРЅС‚РЅРѕРіРѕ
    // С‡Р°С‚Р° (ApprovalCard), Р° РЅРµ РѕС‚РґРµР»СЊРЅС‹Рј РѕРєРЅРѕРј вЂ” СЃРј. AgentRunCard.
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
        // РЈ Р°РіРµРЅС‚РЅРѕРіРѕ С‡Р°С‚Р° РЅРµС‚ РїРµСЂРµРєР»СЋС‡Р°С‚РµР»СЏ RAG вЂ” Р·РЅР°С‡РѕРє РїСЂРѕСЃС‚Рѕ РјРµС‚РєР° РІРёРґР°.
        Text(
            if (agent) "рџЋЇ" else "рџ’¬", fontSize = 13.sp,
            modifier = if (agent) Modifier.padding(end = 6.dp) else Modifier.padding(end = 6.dp).clickable(onClick = onToggleRag),
            color = when {
                agent -> Color(0xFF1E88E5)
                c.ragEnabled -> Color(0xFF7DD87D)
                else -> Color(0xFF777777)
            },
        )
        Text(
            c.name + if (!agent && !c.ragEnabled) "  (Р±РµР· RAG)" else "",
            color = if (selected) {
                if (agent) Color(0xFF90CAF9) else Color(0xFF7DD87D)
            } else if (agent) {
                Color(0xFF64B5F6)
            } else {
                Color(0xFF9CCC9C)
            },
            fontSize = 14.sp, modifier = Modifier.weight(1f).clickable(onClick = onOpen), maxLines = 1,
        )
        Text("Г—", color = Color(0xFF777777), fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDelete).padding(start = 4.dp))
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
            if (selected) "рџ“¦в—Џ" else "рџ“¦в—‹",
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
        Text("вџі", color = Color(0xFF64B5F6), fontSize = 13.sp, modifier = Modifier.clickable(onClick = onUpdate).padding(horizontal = 4.dp))
        Text("Г—", color = Color(0xFF777777), fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDelete).padding(start = 4.dp))
    }
}

@Composable
fun DocRow(d: DocInfo, progress: Float?, selected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    // РРЅРґРµРєСЃРёСЂСѓСЋС‰РёР№СЃСЏ РґРѕРєСѓРјРµРЅС‚: СЃС‚СЂРѕРєР° РЅРµРєР»РёРєР°Р±РµР»СЊРЅР°, РІРјРµСЃС‚Рѕ РґРµР№СЃС‚РІРёР№ вЂ” РїСЂРѕРіСЂРµСЃСЃР±Р°СЂ.
    if (d.indexing) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("рџ“„вЏі", color = Color(0xFF777777), fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, color = Color(0xFF777777), fontSize = 13.sp, maxLines = 1)
                Text(
                    "РёРЅРґРµРєСЃРёСЂСѓРµС‚СЃСЏвЂ¦ В· ${d.strategyLabel.ifBlank { "вЂ¦" }}",
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
            if (d.active) "рџ“„в†“" else "рџ“„вњ•",
            color = if (d.active) Color(0xFF64B5F6) else Color(0xFF777777),
            fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp).clickable(onClick = onToggle),
        )
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(
                d.title, color = if (selected) Color(0xFF7DD87D) else Color(0xFF9CCC9C),
                fontSize = 13.sp, maxLines = 1,
            )
            Text(
                "${d.chunks} С‡Р°РЅРєРѕРІ В· ${d.strategyLabel.ifBlank { "вЂ¦" }}",
                color = Color(0xFF777777), fontSize = 10.sp, maxLines = 1,
            )
            d.error?.let {
                Text("вљ  $it", color = Color(0xFFCF6679), fontSize = 10.sp, maxLines = 1)
            }
        }
        Text("Г—", color = Color(0xFF777777), fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDelete).padding(start = 4.dp))
    }
}

// Enter СЃ РѕСЃРЅРѕРІРЅРѕР№ РєР»Р°РІРёР°С‚СѓСЂС‹ Рё СЃ NumPad вЂ” РїРѕРґС‚РІРµСЂР¶РґРµРЅРёРµ (РѕС‚РїСЂР°РІРєР°/СЃРѕР·РґР°РЅРёРµ/Р·Р°РіСЂСѓР·РєР°).
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
                label = { Text(if (chat.ragEnabled) "RAG РІРєР»" else "RAG РІС‹РєР»") },
            )
            if (chat.ragEnabled) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = vm::openRagSettings) { Text("RAG вљ™", fontSize = 12.sp) }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { vm.clearChat(chat.id) }) { Text("РѕС‡РёСЃС‚РёС‚СЊ", fontSize = 12.sp) }
        }
        if (!chat.ragEnabled) {
            Text(
                "RAG РІС‹РєР»СЋС‡РµРЅ вЂ” РѕС‚РІРµС‡Р°СЋ Р±РµР· Р±Р°Р·С‹ Р·РЅР°РЅРёР№",
                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        // Task 5: РїР°РјСЏС‚СЊ Р·Р°РґР°С‡Рё вЂ” С„Р°РєС‚С‹ С‡Р°С‚Р° РІРјРµСЃС‚Рѕ РїРѕР»РЅРѕРіРѕ Р»РѕРіР° РІ РїСЂРѕРјРїС‚Рµ.
        val mem = st.taskMemory
        if (mem != null && !mem.isEmpty()) {
            var memExpanded by remember(chat.id, mem.updatedAt) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().background(Color(0xFFF0F7F0)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "рџ§  ${if (mem.goal.isNotBlank()) mem.goal.take(80) else "С„Р°РєС‚РѕРІ: ${mem.facts.size}"}",
                        fontSize = 11.sp, color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f).clickable { memExpanded = !memExpanded }, maxLines = 1,
                    )
                    TextButton(onClick = { memExpanded = !memExpanded }) {
                        Text(if (memExpanded) "в–І" else "в–ј С„Р°РєС‚РѕРІ: ${mem.facts.size}", fontSize = 11.sp)
                    }
                    TextButton(onClick = vm::clearTaskMemory) { Text("Р·Р°Р±С‹С‚СЊ", fontSize = 11.sp) }
                }
                if (memExpanded) {
                    val memScroll = rememberScrollState()
                    Box(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                        Column(Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(memScroll)) {
                            if (mem.goal.isNotBlank()) Text("Р¦РµР»СЊ: ${mem.goal}", fontSize = 11.sp, color = Color(0xFF333333))
                            mem.facts.forEach { f -> Text("вЂў $f", fontSize = 11.sp, color = Color(0xFF555555)) }
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 20.dp)) {
                items(messages, key = { it.id }) { m -> MessageBubble(m) }
                if (st.busy) {
                    item(key = "typing") {
                        Text("РџРµС‡Р°С‚Р°РµС‚вЂ¦", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp))
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
                    // Enter / NumPadEnter вЂ” РѕС‚РїСЂР°РІРёС‚СЊ, Shift+Enter вЂ” РЅРѕРІР°СЏ СЃС‚СЂРѕРєР° (РґРµС„РѕР»С‚ С‚РµРєСЃС‚РѕРІРѕРіРѕ РїРѕР»СЏ).
                    if (isConfirmKey(e) && !e.isShiftPressed) {
                        vm.send()
                        true
                    } else false
                },
                placeholder = { Text(if (chat.ragEnabled) "РІРѕРїСЂРѕСЃ РїРѕ РїСЂРѕРµРєС‚СѓвЂ¦" else "СЃРѕРѕР±С‰РµРЅРёРµвЂ¦") },
                enabled = !st.busy, maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = vm::send, enabled = !st.busy && st.input.isNotBlank()) {
                Text(if (st.busy) "..." else "вћ¤")
            }
        }
    }
}

// РђРіРµРЅС‚РЅС‹Р№ С‡Р°С‚. Р›РµРЅС‚Р° = СЃРїРёСЃРѕРє РїСЂРѕРіРѕРЅРѕРІ СЌС‚РѕРіРѕ С‡Р°С‚Р°: СЃРѕРѕР±С‰РµРЅРёРµ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ
// (С†РµР»СЊ) Рё РєР°СЂС‚РѕС‡РєР° РїСЂРѕРіРѕРЅР°, РіРґРµ РІРёРґРЅРѕ С…РѕРґ, diff Рё вЂ” РµСЃР»Рё Р°РіРµРЅС‚ Р¶РґС‘С‚ вЂ”
// СЂР°Р·СЂРµС€РµРЅРёРµ РЅР° Р·Р°РїРёСЃСЊ. РћС‚РґРµР»СЊРЅРѕРіРѕ РѕРєРЅР° РїРѕРґС‚РІРµСЂР¶РґРµРЅРёСЏ РЅРµС‚: СЂРµС€РµРЅРёРµ РІРёРґРЅРѕ
// С‚Р°Рј Р¶Рµ, РіРґРµ РёРґС‘С‚ СЂР°Р±РѕС‚Р°.
@Composable
fun AgentChatPane(vm: AppViewModel, chat: RagChat) {
    val st by vm.state.collectAsState()
    Column(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFEAF1FB)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("рџЋЇ ${chat.name}", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                st.selectedProject?.let { "РїСЂРѕРµРєС‚: $it" } ?: "РїСЂРѕРµРєС‚ РЅРµ РІС‹Р±СЂР°РЅ",
                fontSize = 12.sp, color = if (st.selectedProject != null) Color.Gray else Color(0xFFB00020),
                maxLines = 1,
            )
            if (st.busy) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = vm::cancelAgent) { Text("вЏ№ СЃС‚РѕРї", fontSize = 12.sp, color = Color(0xFFB00020)) }
            }
        }
        val runs = st.chatRuns
        val listState = rememberLazyListState()
        // РќР° РЅРѕРІС‹Р№ РїСЂРѕРіРѕРЅ Рё РЅР° РєР°Р¶РґРѕРј С…РѕРґРµ РїСЂРѕРєСЂСѓС‡РёРІР°РµРј Рє РїРѕСЃР»РµРґРЅРµР№ РєР°СЂС‚РѕС‡РєРµ вЂ”
        // РёРЅР°С‡Рµ РґР»РёРЅРЅС‹Р№ trace СѓРµР·Р¶Р°РµС‚ Р·Р° СЌРєСЂР°РЅ Рё Р°РіРµРЅС‚ РІС‹РіР»СЏРґРёС‚ В«Р·Р°РІРёСЃС€РёРјВ».
        LaunchedEffect(chat.id, runs.size, st.agentRun?.steps, st.pendingChange) {
            if (runs.isNotEmpty()) listState.animateScrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 24.dp)) {
                if (runs.isEmpty() && !st.busy) {
                    item(key = "agent-hint") {
                        Text(
                            "РђРіРµРЅС‚РЅС‹Р№ С‡Р°С‚: РїРёС€Рё С†РµР»СЊ вЂ” РѕРЅ СЃР°Рј РІС‹Р±РµСЂРµС‚ С„Р°Р№Р»С‹.\n" +
                                "РќР°РїСЂРёРјРµСЂ: В«РЅР°Р№РґРё РІСЃРµ РјРµСЃС‚Р°, РіРґРµ РёСЃРїРѕР»СЊР·СѓРµС‚СЃСЏ RagToolsВ» РёР»Рё\n" +
                                "В«РѕР±РЅРѕРІРё README РїРѕРґ С‚РµРєСѓС‰РёР№ РєРѕРґВ».\n" +
                                "РљР°Р¶РґРѕРµ РёР·РјРµРЅРµРЅРёРµ С„Р°Р№Р»РѕРІ СЃРїСЂРѕСЃРёС‚ СЂР°Р·СЂРµС€РµРЅРёСЏ Р·РґРµСЃСЊ Р¶Рµ, РІ С‡Р°С‚Рµ.",
                            fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                itemsIndexed(runs, key = { _, r -> "r${r.id}" }) { _, r ->
                    Column {
                        // РЎРѕРѕР±С‰РµРЅРёРµ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ вЂ” СЃР°РјР° С†РµР»СЊ.
                        Column(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp)
                                .background(Color(0xFFE3F2FD)).padding(8.dp),
                        ) {
                            Text("рџЋЇ С†РµР»СЊ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Text(r.goal, fontSize = 14.sp)
                        }
                        AgentRunCard(vm, r, st)
                    }
                }
                if (st.busy && runs.isEmpty()) {
                    item(key = "agent-thinking") {
                        Text("РђРіРµРЅС‚ РґСѓРјР°РµС‚вЂ¦", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp),
            )
        }
        // Р’РІРѕРґ СЃРЅРёР·Сѓ вЂ” РєР°Рє РІ РѕР±С‹С‡РЅРѕРј С‡Р°С‚Рµ: С‚РµРєСЃС‚ СѓС…РѕРґРёС‚ Р°РіРµРЅС‚Сѓ РєР°Рє С†РµР»СЊ.
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = st.input, onValueChange = vm::setInput,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // Enter / NumPadEnter вЂ” РѕС‚РїСЂР°РІРёС‚СЊ, Shift+Enter вЂ” РЅРѕРІР°СЏ СЃС‚СЂРѕРєР°.
                    if (isConfirmKey(e) && !e.isShiftPressed) {
                        vm.sendAgent()
                        true
                    } else false
                },
                placeholder = { Text("С†РµР»СЊ РґР»СЏ Р°РіРµРЅС‚Р°: С‡С‚Рѕ СЃРґРµР»Р°С‚СЊ СЃ С„Р°Р№Р»Р°РјРё РїСЂРѕРµРєС‚Р°вЂ¦") },
                enabled = !st.busy && st.selectedProject != null, maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = vm::sendAgent, enabled = !st.busy && st.input.isNotBlank()) {
                Text(if (st.busy) "..." else "вћ¤")
            }
        }
    }
}

// РљР°СЂС‚РѕС‡РєР° РїСЂРѕРіРѕРЅР° РІ Р»РµРЅС‚Рµ: СЃРѕСЃС‚РѕСЏРЅРёРµ, С…РѕРґ С‚СѓР»РѕРІ, РёС‚РѕРі, РёР·РјРµРЅС‘РЅРЅС‹Рµ С„Р°Р№Р»С‹,
// diff, РѕС‚РєР°С‚ вЂ” Рё Р·Р°РїСЂРѕСЃ СЂР°Р·СЂРµС€РµРЅРёСЏ, РєРѕРіРґР° Р°РіРµРЅС‚ С…РѕС‡РµС‚ С‡С‚Рѕ-С‚Рѕ Р·Р°РїРёСЃР°С‚СЊ.
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
                "С€Р°РіРѕРІ ${run.steps} В· РІС‹Р·РѕРІРѕРІ ${run.invocations.size} В· ${run.id}",
                fontSize = 10.sp, color = Color.Gray, modifier = Modifier.weight(1f), maxLines = 1,
            )
            if (run.state !is core.agent.RunState.Done && run.state !is core.agent.RunState.Cancelled &&
                run.state !is core.agent.RunState.Failed
            ) {
                Text("в—Џ", fontSize = 11.sp, color = color)
            }
        }
        // Р—Р°РїСЂРѕСЃ СЂР°Р·СЂРµС€РµРЅРёСЏ: Р°РіРµРЅС‚ С…РѕС‡РµС‚ Р·Р°РїРёСЃР°С‚СЊ С„Р°Р№Р». Р РµС€РµРЅРёРµ вЂ” Р·РґРµСЃСЊ.
        if (st.pendingChange != null && run.state is core.agent.RunState.AwaitingApproval) {
            ApprovalCard(vm, st.pendingChange)
        }
        // РС‚РѕРі РїСЂРѕРіРѕРЅР°.
        val summary = when (val s = run.state) {
            is core.agent.RunState.Done -> s.summary
            is core.agent.RunState.Failed -> "РџСЂРѕРіРѕРЅ РїСЂРµСЂРІР°РЅ: ${s.error}"
            is core.agent.RunState.Cancelled -> "РџСЂРѕРіРѕРЅ РѕС‚РјРµРЅС‘РЅ."
            else -> ""
        }.ifBlank { run.summary }
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Markdown(summary, typography = chatMarkdownTypography(), modifier = Modifier.fillMaxWidth())
            }
        }
        if (run.error.isNotBlank() && run.state !is core.agent.RunState.Failed) {
            Text(run.error, fontSize = 11.sp, color = Color(0xFFB00020))
        }
        // РҐРѕРґ: РІС‹Р·РѕРІС‹ С‚СѓР»РѕРІ Рё Р¶СѓСЂРЅР°Р» вЂ” РїРѕРґ СЂР°СЃРєСЂС‹РІР°С€РєРѕР№, С‡С‚РѕР±С‹ РЅРµ РґР°РІРёР»Рѕ С‚РµРєСЃС‚РѕРј.
        if (run.invocations.isNotEmpty() || log.isNotEmpty()) {
            var open by remember(run.id, run.invocations.size) { mutableStateOf(run.invocations.size <= 6) }
            TextButton(onClick = { open = !open }) {
                Text(
                    if (open) "в–І С…РѕРґ: ${run.invocations.size} РІС‹Р·РѕРІРѕРІ" else "в–ј С…РѕРґ: ${run.invocations.size} РІС‹Р·РѕРІРѕРІ",
                    fontSize = 11.sp,
                )
            }
            if (open) {
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    run.invocations.forEach { inv ->
                        val (icon, c, note) = when (val s = inv.state) {
                            is core.agent.ToolState.Done -> Triple("вњ…", Color(0xFF2E7D32), s.summary.take(80))
                            is core.agent.ToolState.Failed -> Triple("вќЊ", Color(0xFFB00020), s.error.take(80))
                            is core.agent.ToolState.Running -> Triple("рџ”§", Color(0xFF00838F), "РІС‹РїРѕР»РЅСЏРµС‚СЃСЏ")
                            core.agent.ToolState.Pending -> Triple("вЏі", Color.Gray, "Р¶РґС‘С‚")
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(icon, fontSize = 11.sp, modifier = Modifier.width(18.dp))
                            Text(
                                inv.tool, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c,
                                modifier = Modifier.width(96.dp), maxLines = 1,
                            )
                            SelectionContainer {
                                Text(
                                    inv.args.replace(Regex("\\s+"), " ").take(70),
                                    fontSize = 11.sp, color = Color.DarkGray,
                                    modifier = Modifier.weight(1f), maxLines = 1,
                                )
                            }
                            Text(note, fontSize = 10.sp, color = Color.Gray, maxLines = 1, modifier = Modifier.width(120.dp))
                        }
                    }
                    log.forEach { Text("В· $it", fontSize = 10.sp, color = Color.Gray, maxLines = 2) }
                }
            }
        }
        // РР·РјРµРЅС‘РЅРЅС‹Рµ С„Р°Р№Р»С‹ + diff + РѕС‚РєР°С‚: СЂРµР·СѓР»СЊС‚Р°С‚ РїРѕ Р·Р°РґР°РЅРёСЋ РґРѕР»Р¶РµРЅ Р±С‹С‚СЊ РЅР° СЂСѓРєР°С….
        if (run.changedFiles.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "РёР·РјРµРЅРµРЅРѕ С„Р°Р№Р»РѕРІ: ${run.changedFiles.size} вЂ” ${run.changedFiles.joinToString(", ").take(90)}",
                    fontSize = 11.sp, color = Color(0xFF2E7D32), modifier = Modifier.weight(1f), maxLines = 2,
                )
                TextButton(onClick = { vm.revertRun(run.id) }) { Text("РѕС‚РєР°С‚РёС‚СЊ", fontSize = 11.sp) }
            }
            var showDiff by remember(run.id, run.updatedAt) { mutableStateOf(false) }
            TextButton(onClick = { showDiff = !showDiff }) {
                Text(if (showDiff) "СЃРєСЂС‹С‚СЊ diff" else "РїРѕРєР°Р·Р°С‚СЊ diff", fontSize = 11.sp)
            }
            if (showDiff) {
                Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Color(0xFFFAFAFA)).padding(6.dp)) {
                    SelectionContainer {
                        Text(
                            run.diff.take(DIFF_PREVIEW_CHARS),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (run.diff.length > DIFF_PREVIEW_CHARS) {
                    Text("вЂ¦ diff РѕР±СЂРµР·Р°РЅ; РїРѕР»РЅС‹Р№ вЂ” РІ agent_runs.json РёР»Рё `week6 agent-diff`", fontSize = 10.sp, color = Color.Gray)
                }
            }
        }
    }
}

// Р Р°Р·СЂРµС€РµРЅРёРµ РЅР° Р·Р°РїРёСЃСЊ вЂ” РєР°СЂС‚РѕС‡РєРѕР№ РІ С‡Р°С‚Рµ, Р° РЅРµ РјРѕРґР°Р»СЊРЅС‹Рј РѕРєРЅРѕРј: РІРёРґРЅРѕ, РєР°РєРѕР№
// РїСЂРѕРіРѕРЅ Рё РєР°РєРѕР№ С„Р°Р№Р» Р¶РґСѓС‚ РѕС‚РІРµС‚Р°, Рё СЂРµС€РµРЅРёРµ РѕСЃС‚Р°С‘С‚СЃСЏ РІ РёСЃС‚РѕСЂРёРё Р»РµРЅС‚С‹.
@Composable
fun ApprovalCard(vm: AppViewModel, change: core.agent.PendingChange) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .background(Color(0xFFFFF3E0)).padding(8.dp),
    ) {
        Text("РќСѓР¶РЅРѕ СЂР°Р·СЂРµС€РµРЅРёРµ РЅР° Р·Р°РїРёСЃСЊ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF6C00))
        Text(
            "${change.action} ${change.path} В· Р·Р°РјРµРЅ: ${change.replacements}" +
                if (change.summary.isNotBlank()) "\n${change.summary}" else "",
            fontSize = 12.sp,
        )
        Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).background(Color.White).padding(6.dp)) {
            SelectionContainer {
                Text(change.diff.take(DIFF_PREVIEW_CHARS), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::approveOnce) { Text("Р Р°Р·СЂРµС€РёС‚СЊ", fontSize = 12.sp) }
            OutlinedButton(onClick = vm::approveAlways) { Text("Р’СЃРµРіРґР° РґР»СЏ СЌС‚РѕРіРѕ РїСЂР°РІРёР»Р°", fontSize = 12.sp) }
            TextButton(onClick = vm::rejectAgent) { Text("РћС‚РєР»РѕРЅРёС‚СЊ", fontSize = 12.sp, color = Color(0xFFB00020)) }
        }
    }
}

@Composable
fun MessageBubble(m: RagMessage) {
    val bg = if (m.role == "user") Color(0xFFE3F2FD) else Color(0xFFF1F1F1)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(bg).padding(8.dp)) {
        Text(m.role, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        // Task 3: РґРёР°РіРЅРѕСЃС‚РёРєР° РІС‚РѕСЂРѕРіРѕ СЌС‚Р°РїР° ("РЅР°Р№РґРµРЅРѕ 20 в†’ РІ РєРѕРЅС‚РµРєСЃС‚ 4...").
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
        // Task 4: РєРѕРјРїР°РєС‚РЅРѕ вЂ” РІ РѕС‚РІРµС‚Рµ С‚РѕР»СЊРєРѕ РєРѕСЂРѕС‚РєРёРµ [N], РґРµС‚Р°Р»Рё СЃРєСЂС‹С‚С‹ РїРѕРґ СЂР°СЃРєСЂС‹РІР°С€РєРѕР№.
        val refCount = if (m.refs.isNotEmpty()) m.refs.size else m.sources.size
        if (refCount > 0) {
            var expanded by remember(m.id) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "в–І РёСЃС‚РѕС‡РЅРёРєРё ($refCount)" else "в–ј РёСЃС‚РѕС‡РЅРёРєРё ($refCount)", fontSize = 11.sp)
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
                            "${r.source} В· ${r.chunkId.ifBlank { "вЂ”" }}",
                            fontSize = 10.sp, color = Color(0xFF888888), maxLines = 1,
                        )
                        if (r.excerpt.isNotBlank()) {
                            SelectionContainer {
                                Text("вЂњ${r.excerpt}вЂќ", fontSize = 11.sp, color = Color(0xFF555555))
                            }
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
            TextButton(onClick = vm::closeDoc) { Text("Р·Р°РєСЂС‹С‚СЊ") }
        }
        if (doc != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${doc.chunks} С‡Р°РЅРєРѕРІ В· ${doc.strategyLabel.ifBlank { "вЂ¦" }} В· ~${doc.chars} СЃРёРјРІ",
                    fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.toggleDoc(source) }) {
                    Text(if (doc.active) "РІС‹РєР»СЋС‡РёС‚СЊ РёР· RAG" else "РІРєР»СЋС‡РёС‚СЊ РІ RAG", fontSize = 12.sp)
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
        title = { Text("РќР°СЃС‚СЂРѕР№РєРё") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // --- РњРѕРґРµР»СЊ СЏР·С‹РєР° (РєР°Рє Week 5: Р»РѕРєР°Р»СЊРЅС‹Рµ on-device + РѕР±Р»Р°РєРѕ) ---
                Text("РњРѕРґРµР»СЊ СЏР·С‹РєР°", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = isLocal,
                        onClick = { vm.setLlmProvider(LlmSettings.PROVIDER_OLLAMA) },
                        label = { Text("Р›РѕРєР°Р»СЊРЅР°СЏ (Ollama)", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = !isLocal,
                        onClick = { vm.setLlmProvider(LlmSettings.PROVIDER_DEEPSEEK) },
                        label = { Text("DeepSeek (РѕР±Р»Р°РєРѕ)", fontSize = 12.sp) },
                    )
                }
                if (isLocal) {
                    Text(
                        "Р›РѕРєР°Р»СЊРЅР°СЏ РјРѕРґРµР»СЊ С‡РµСЂРµР· Ollama (`ollama serve` + `ollama pull <РјРѕРґРµР»СЊ>`). РљР»СЋС‡ РЅРµ РЅСѓР¶РµРЅ. Р”Р»СЏ M3 Pro 64 Р“Р‘ СЂРµРєРѕРјРµРЅРґРѕРІР°РЅРѕ qwen3:32b.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                    OutlinedTextField(
                        value = st.llmModel, onValueChange = vm::setLlmModel,
                        label = { Text("РњРѕРґРµР»СЊ") }, placeholder = { Text(LlmSettings.DEFAULT_OLLAMA_MODEL) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.llmBusy,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LlmSettings.LLM_PRESETS.forEach { (id, desc) ->
                            FilterChip(
                                selected = st.llmModel.trim() == id,
                                onClick = { vm.setLlmModel(id) },
                                label = { Text("$id вЂ” $desc", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = st.llmUrl, onValueChange = vm::setLlmUrl,
                        label = { Text("РђРґСЂРµСЃ Ollama (С…РѕСЃС‚:РїРѕСЂС‚)") }, placeholder = { Text(LlmSettings.DEFAULT_OLLAMA_URL) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.llmBusy,
                    )
                } else {
                    Text(
                        "РћР±Р»Р°С‡РЅС‹Р№ DeepSeek С‡РµСЂРµР· OpenAI-СЃРѕРІРјРµСЃС‚РёРјС‹Р№ API. РќСѓР¶РµРЅ DEEPSEEK_API_KEY (env, РєРѕСЂРЅРµРІРѕР№ .env РёР»Рё РїРѕР»Рµ РЅРёР¶Рµ).",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                    OutlinedTextField(
                        value = st.llmModel, onValueChange = vm::setLlmModel,
                        label = { Text("РњРѕРґРµР»СЊ") }, placeholder = { Text(LlmSettings.DEFAULT_DEEPSEEK_MODEL) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.llmBusy,
                    )
                    OutlinedTextField(
                        value = st.llmKeyInput, onValueChange = vm::setLlmKeyInput,
                        label = { Text(if (st.llmHasKey) "РќРѕРІС‹Р№ РєР»СЋС‡ (РµСЃС‚СЊ СЃРѕС…СЂР°РЅС‘РЅРЅС‹Р№)" else "API-РєР»СЋС‡") },
                        placeholder = { Text("sk-вЂ¦") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.llmBusy,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::testLlm, enabled = !st.llmBusy) {
                        Text(if (st.llmBusy) "..." else "РџСЂРѕРІРµСЂРёС‚СЊ LLM")
                    }
                    Spacer(Modifier.width(8.dp))
                    val check = st.llmCheck
                    if (check != null) {
                        Text(
                            check, fontSize = 11.sp,
                            color = if (check.startsWith("РћРЁРР‘РљРђ")) Color(0xFFB00020) else Color(0xFF2E7D32),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // --- Р­РјР±РµРґРґРёРЅРі-РјРѕРґРµР»СЊ ---
                Text("Р­РјР±РµРґРґРёРЅРі-РјРѕРґРµР»СЊ", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "Р›РѕРєР°Р»СЊРЅР°СЏ РјРѕРґРµР»СЊ С‡РµСЂРµР· Ollama. Р’РµРєС‚РѕСЂРёР·СѓРµС‚СЃСЏ С‚РѕР»СЊРєРѕ РґРѕРєСѓРјРµРЅС‚Р°С†РёСЏ (README/docs/*.md/СЃС…РµРјС‹) вЂ” РєРѕРґ РЅРµ СЌРјР±РµРґРґРёС‚СЃСЏ. РџРѕСЃР»Рµ СЃРјРµРЅС‹ РјРѕРґРµР»Рё РЅСѓР¶РЅР° РїРµСЂРµРёРЅРґРµРєСЃР°С†РёСЏ.",
                    fontSize = 11.sp, color = Color.Gray,
                )
                OutlinedTextField(
                    value = st.settingsModel, onValueChange = vm::setSettingsModel,
                    label = { Text("РњРѕРґРµР»СЊ") }, placeholder = { Text("bge-m3") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.settingsBusy,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LlmSettings.EMBED_PRESETS.forEach { (id, desc) ->
                        FilterChip(
                            selected = st.settingsModel.trim() == id,
                            onClick = { vm.setSettingsModel(id) },
                            label = { Text("$id вЂ” $desc", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                OutlinedTextField(
                    value = st.settingsUrl, onValueChange = vm::setSettingsUrl,
                    label = { Text("РђРґСЂРµСЃ Ollama (С…РѕСЃС‚:РїРѕСЂС‚)") }, placeholder = { Text("http://localhost:11434") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !st.settingsBusy,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::testSettings, enabled = !st.settingsBusy) {
                        Text(if (st.settingsBusy) "..." else "РџСЂРѕРІРµСЂРёС‚СЊ")
                    }
                    Spacer(Modifier.width(8.dp))
                    val check = st.settingsCheck
                    if (check != null) {
                        Text(
                            check, fontSize = 11.sp,
                            color = if (check.startsWith("РћРЁРР‘РљРђ")) Color(0xFFB00020) else Color(0xFF2E7D32),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = vm::saveSettings, enabled = !st.settingsBusy && !st.llmBusy) { Text("РЎРѕС…СЂР°РЅРёС‚СЊ") } },
        dismissButton = { TextButton(onClick = vm::closeSettings) { Text("РћС‚РјРµРЅР°") } },
    )
}

// Р—Р°РґР°РЅРёРµ 3: РЅР°СЃС‚СЂРѕР№РєРё RAG вЂ” РїСЂРёРјРµРЅСЏСЋС‚СЃСЏ СЃСЂР°Р·Сѓ, Р±РµР· РєРЅРѕРїРєРё "РЎРѕС…СЂР°РЅРёС‚СЊ".
// РЎС‚СЂСѓРєС‚СѓСЂР° РїРѕРІС‚РѕСЂСЏРµС‚ РїР°Р№РїР»Р°Р№РЅ: top-K РґРѕ в†’ rewrite в†’ С„РёР»СЊС‚СЂ (С‚РµРјРїРµСЂР°С‚СѓСЂР° + top-K РїРѕСЃР»Рµ).
@Composable
fun RagSettingsDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = vm::closeRagSettings,
        title = { Text("РќР°СЃС‚СЂРѕР№РєРё RAG") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 1. Top-K РґРѕ С„РёР»СЊС‚СЂР°С†РёРё.
                Text("Top-K (РґРѕ С„РёР»СЊС‚СЂР°С†РёРё)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = st.topK.toString(), onValueChange = vm::setTopK,
                    label = { Text("Р§Р°РЅРєРѕРІ Р·Р°Р±СЂР°С‚СЊ (1вЂ“50)", fontSize = 11.sp) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "РЎРєРѕР»СЊРєРѕ Р»СѓС‡С€РёС… С‡Р°РЅРєРѕРІ РІР·СЏС‚СЊ РёР· РёРЅРґРµРєСЃР°. Р РµРєРѕРјРµРЅРґСѓРµС‚СЃСЏ 10вЂ“30 (РґРµС„РѕР»С‚ 20): " +
                        "РјРµРЅСЊС€Рµ 10 вЂ” РјРѕР¶РЅРѕ РїСЂРѕРїСѓСЃС‚РёС‚СЊ РЅСѓР¶РЅРѕРµ, Р±РѕР»СЊС€Рµ 30 вЂ” РґРѕР»СЊС€Рµ Рё С€СѓРјРЅРµРµ.",
                    fontSize = 11.sp, color = Color.Gray,
                )
                // 2. Rewrite query СЃ РїРѕРјРѕС‰СЊСЋ LLM.
                Text("Rewrite Р·Р°РїСЂРѕСЃР°", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = st.rewriteEnabled,
                        onClick = { vm.setRewriteEnabled(!st.rewriteEnabled) },
                        label = { Text(if (st.rewriteEnabled) "Р’РєР»СЋС‡С‘РЅ" else "Р’С‹РєР»СЋС‡РµРЅ", fontSize = 12.sp) },
                    )
                }
                Text(
                    "РњРѕРґРµР»СЊ РїРµСЂРµРїРёСЃС‹РІР°РµС‚ РІРѕРїСЂРѕСЃ РІ 1вЂ“2 РїРѕРёСЃРєРѕРІС‹С… Р·Р°РїСЂРѕСЃР° (В«Р° РїСЂРё С‡С‘Рј С‚СѓС‚ РљРѕСЃРѕРІРѕ?В» в†’ В«СЃРµСЂР±СЃРєРѕРµ РєРѕСЃ, С‡С‘СЂРЅС‹Р№ РґСЂРѕР·РґВ»). " +
                        "РџРѕРјРѕРіР°РµС‚ РєРѕСЂРѕС‚РєРёРј Рё СЂР°Р·РіРѕРІРѕСЂРЅС‹Рј РІРѕРїСЂРѕСЃР°Рј; СЃС‚РѕРёС‚ +1 Р·Р°РїСЂРѕСЃ Рє DeepSeek.",
                    fontSize = 11.sp, color = Color.Gray,
                )
                // 3. Р¤РёР»СЊС‚СЂР°С†РёСЏ РІРєР»/РІС‹РєР».
                Text("Р¤РёР»СЊС‚СЂР°С†РёСЏ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !st.filterEnabled,
                        onClick = { vm.setFilterEnabled(false) },
                        label = { Text("Р’С‹РєР»", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = st.filterEnabled,
                        onClick = { vm.setFilterEnabled(true) },
                        label = { Text("Р’РєР»", fontSize = 12.sp) },
                    )
                }
                if (!st.filterEnabled) {
                    Text(
                        "Р¤РёР»СЊС‚СЂ РІС‹РєР»СЋС‡РµРЅ: РїРµСЂРІС‹Рµ top-K С‡Р°РЅРєРѕРІ РёРґСѓС‚ РјРѕРґРµР»Рё РєР°Рє РµСЃС‚СЊ (СЂРµР¶РёРј Task 2). " +
                            "Р‘С‹СЃС‚СЂРѕ, РЅРѕ РјСѓСЃРѕСЂ С‚РѕР¶Рµ РїРѕРїР°РґР°РµС‚ РІ РєРѕРЅС‚РµРєСЃС‚.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                } else {
                    Text(
                        "Р¤РёР»СЊС‚СЂ РІРєР»СЋС‡С‘РЅ: СЃРЅР°С‡Р°Р»Р° РѕС‚СЃРµС‡РµРЅРёРµ РїРѕ С‚РµРјРїРµСЂР°С‚СѓСЂРµ, Р·Р°С‚РµРј РѕРїС†РёРѕРЅР°Р»СЊРЅС‹Р№ top-K РїРѕСЃР»Рµ. " +
                            "Р•СЃР»Рё РЅРµ РѕСЃС‚Р°Р»РѕСЃСЊ РЅРёС‡РµРіРѕ вЂ” Р°СЃСЃРёСЃС‚РµРЅС‚ С‡РµСЃС‚РЅРѕ СЃРєР°Р¶РµС‚, С‡С‚Рѕ РЅРµ РЅР°С€С‘Р».",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                    // РўРµРјРїРµСЂР°С‚СѓСЂР° вЂ” С‚РѕС‡РЅРѕСЃС‚СЊ РѕС‚РІРµС‚Р°, 0..1.
                    Text(
                        "РўРµРјРїРµСЂР°С‚СѓСЂР°: ${"%.2f".format(st.temperature)}",
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
                        "РџРѕСЂРѕРі РѕС‚СЃРµС‡РµРЅРёСЏ РїРѕ РїРѕС…РѕР¶РµСЃС‚Рё: РІС‹С€Рµ вЂ” СЃС‚СЂРѕР¶Рµ, РЅРѕ РјРѕР¶РЅРѕ РѕС‚СЂРµР·Р°С‚СЊ Рё РЅСѓР¶РЅРѕРµ. " +
                            "РЎС‚Р°СЂС‚РѕРІР°СЏ РІРёР»РєР° 0.3вЂ“0.5: РѕС‚РІРµС‚С‹ РїСѓСЃС‚С‹Рµ вЂ” СЃРЅРёР¶Р°Р№, РІ РѕС‚РІРµС‚ Р»РµР·РµС‚ РјСѓСЃРѕСЂ вЂ” РїРѕРІС‹С€Р°Р№.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                    // Top-K РїРѕСЃР»Рµ С„РёР»СЊС‚СЂР°С†РёРё вЂ” РѕРїС†РёРѕРЅР°Р»РµРЅ, 1..top-K РґРѕ.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = st.postFilterK != null,
                            onClick = { vm.setPostFilterKEnabled(st.postFilterK == null) },
                            label = { Text("РћРіСЂР°РЅРёС‡РёС‚СЊ top-K РїРѕСЃР»Рµ", fontSize = 12.sp) },
                        )
                    }
                    OutlinedTextField(
                        value = st.postFilterK?.toString() ?: "",
                        onValueChange = vm::setPostFilterK,
                        label = { Text("Top-K РїРѕСЃР»Рµ (1вЂ“${st.topK}, РїСѓСЃС‚Рѕ = РІСЃРµ)", fontSize = 11.sp) },
                        singleLine = true, enabled = st.postFilterK != null,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Р±РµР· РѕРіСЂР°РЅРёС‡РµРЅРёСЏ", fontSize = 11.sp) },
                    )
                    Text(
                        "РџРѕРІС‚РѕСЂРЅР°СЏ РѕР±СЂРµР·РєР° РїРѕСЃР»Рµ С„РёР»СЊС‚СЂР° РїРѕ С‚РµРјРїРµСЂР°С‚СѓСЂРµ: РІ РєРѕРЅС‚РµРєСЃС‚ СѓР№РґС‘С‚ РЅРµ Р±РѕР»СЊС€Рµ N С‡Р°РЅРєРѕРІ. " +
                            "РџСѓСЃС‚Рѕ вЂ” РёРґСѓС‚ РІСЃРµ РїСЂРѕС€РµРґС€РёРµ РїРѕСЂРѕРі.",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                }
                Text(
                    if (!st.filterEnabled) {
                        "РС‚РѕРіРѕ СЃРµР№С‡Р°СЃ: Р·Р°Р±СЂР°С‚СЊ ${st.topK} в†’ РІ РєРѕРЅС‚РµРєСЃС‚ ${st.topK} Р±РµР· С„РёР»СЊС‚СЂР°."
                    } else if (st.postFilterK != null) {
                        "РС‚РѕРіРѕ СЃРµР№С‡Р°СЃ: Р·Р°Р±СЂР°С‚СЊ ${st.topK} в†’ С„РёР»СЊС‚СЂ (С‚РµРјРї. ${"%.2f".format(st.temperature)}) в†’ РІ РѕС‚РІРµС‚ РґРѕ ${st.postFilterK}."
                    } else {
                        "РС‚РѕРіРѕ СЃРµР№С‡Р°СЃ: Р·Р°Р±СЂР°С‚СЊ ${st.topK} в†’ С„РёР»СЊС‚СЂ (С‚РµРјРї. ${"%.2f".format(st.temperature)}) в†’ РІ РѕС‚РІРµС‚ РІСЃРµ РїСЂРѕС€РµРґС€РёРµ."
                    },
                    fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = { Button(onClick = vm::closeRagSettings) { Text("Р“РѕС‚РѕРІРѕ") } },
        dismissButton = {},
    )
}

@Composable
fun CreateChatDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    val agent = st.createKind == CHAT_KIND_AGENT
    AlertDialog(
        onDismissRequest = vm::closeCreateChat,
        title = { Text(if (agent) "РќРѕРІС‹Р№ Р°РіРµРЅС‚РЅС‹Р№ С‡Р°С‚" else "РќРѕРІС‹Р№ С‡Р°С‚") },
        text = {
            Column {
                Text(
                    if (agent) "Р¦РµР»СЊ СЃС‚Р°РІРёС€СЊ С‚С‹, С„Р°Р№Р»С‹ РІС‹Р±РёСЂР°РµС‚ Р°РіРµРЅС‚. РџСЂРѕРіРѕРЅС‹ Рё diff С…СЂР°РЅСЏС‚СЃСЏ РІ СЌС‚РѕРј С‡Р°С‚Рµ."
                    else "РџРµСЂРµРїРёСЃРєР° СЃ РІРѕРїСЂРѕСЃР°РјРё РїРѕ Р±Р°Р·Рµ Р·РЅР°РЅРёР№ РїСЂРѕРµРєС‚Р°.",
                    fontSize = 12.sp, color = Color.Gray,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = st.createName, onValueChange = vm::setCreateName,
                    label = { Text("РќР°Р·РІР°РЅРёРµ") }, singleLine = true,
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        // РќР°Р·РІР°РЅРёРµ РІСЃРµРіРґР° РІ РѕРґРЅСѓ СЃС‚СЂРѕРєСѓ вЂ” Enter / NumPadEnter СЃСЂР°Р·Сѓ СЃРѕР·РґР°С‘С‚ С‡Р°С‚.
                        if (isConfirmKey(e)) {
                            vm.commitCreateChat()
                            true
                        } else false
                    },
                )
            }
        },
        confirmButton = { Button(onClick = vm::commitCreateChat) { Text("РЎРѕР·РґР°С‚СЊ") } },
        dismissButton = { TextButton(onClick = vm::closeCreateChat) { Text("РћС‚РјРµРЅР°") } },
    )
}

@Composable
fun ChunkStrategyPicker(selected: String, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Р§Р°РЅРєРёРЅРі:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selected != "structure",
                onClick = { onToggle("fixed") },
                label = { Text("Р¤РёРєСЃ. СЂР°Р·РјРµСЂ", fontSize = 12.sp) },
            )
            FilterChip(
                selected = selected != "fixed",
                onClick = { onToggle("structure") },
                label = { Text("РџРѕ СЃС‚СЂСѓРєС‚СѓСЂРµ", fontSize = 12.sp) },
            )
        }
    }
}

@Composable
fun AddProjectDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = vm::closeAddProject,
        title = { Text("РќРѕРІС‹Р№ РїСЂРѕРµРєС‚") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Git-URL РєР»РѕРЅРёСЂСѓРµС‚СЃСЏ РІ Р»РѕРєР°Р»СЊРЅС‹Рµ repos, РїСѓС‚СЊ вЂ” РёРЅРґРµРєСЃРёСЂСѓРµС‚СЃСЏ РЅР° РјРµСЃС‚Рµ. РРјСЏ РїРѕРґСЃС‚Р°РІРёС‚СЃСЏ РёР· С…РІРѕСЃС‚Р° РїСѓС‚Рё, РјРѕР¶РЅРѕ РїСЂР°РІРёС‚СЊ.",
                    fontSize = 11.sp, color = Color.Gray,
                )
                OutlinedTextField(
                    value = st.projectInput, onValueChange = vm::setProjectInput,
                    label = { Text("Git-URL РёР»Рё Р»РѕРєР°Р»СЊРЅС‹Р№ РїСѓС‚СЊ") },
                    placeholder = { Text("https://github.com/...git РёР»Рё /path/to/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = st.projectNameInput, onValueChange = vm::setProjectNameInput,
                    label = { Text("РРјСЏ (Р»Р°С‚РёРЅРёС†Р°)") },
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
            ) { Text("Р”РѕР±Р°РІРёС‚СЊ") }
        },
        dismissButton = { TextButton(onClick = vm::closeAddProject) { Text("РћС‚РјРµРЅР°") } },
    )
}

@Composable
fun WikiDialog(vm: AppViewModel) {
    val st by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = vm::closeWiki,
        title = { Text("РЎС‚Р°С‚СЊСЏ Р’РёРєРёРїРµРґРёРё") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = st.wikiInput, onValueChange = vm::setWikiInput,
                    label = { Text("РќР°Р·РІР°РЅРёРµ РёР»Рё URL") },
                    placeholder = { Text("РСЃРєСѓСЃСЃС‚РІРµРЅРЅС‹Р№ РёРЅС‚РµР»Р»РµРєС‚") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        // Enter / NumPadEnter вЂ” С‚Рѕ Р¶Рµ, С‡С‚Рѕ РєРЅРѕРїРєР° В«Р—Р°РіСЂСѓР·РёС‚СЊВ».
                        if (isConfirmKey(e) && st.wikiInput.isNotBlank()) {
                            vm.fetchWiki()
                            true
                        } else false
                    },
                )
                ChunkStrategyPicker(st.addStrategy, vm::toggleChunkPart)
            }
        },
        confirmButton = { Button(onClick = vm::fetchWiki, enabled = st.wikiInput.isNotBlank()) { Text("Р—Р°РіСЂСѓР·РёС‚СЊ") } },
        dismissButton = { TextButton(onClick = vm::closeWiki) { Text("РћС‚РјРµРЅР°") } },
    )
}

// ===== Task 4: РїР°РЅРµР»СЊ Р°РіРµРЅС‚Р°, РґРёР°Р»РѕРі РїРѕРґС‚РІРµСЂР¶РґРµРЅРёСЏ Рё diff РїСЂРѕРіРѕРЅР° =====

private val STATE_COLORS = mapOf(
    "goal" to Color(0xFF1E88E5),
    "thinking" to Color(0xFF6A1B9A),
    "awaiting" to Color(0xFFEF6C00),
    "applying" to Color(0xFF00838F),
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
    is core.agent.RunState.Done -> "done"
    is core.agent.RunState.Failed -> "failed"
    is core.agent.RunState.Cancelled -> "cancelled"
}


private const val DIFF_PREVIEW_CHARS = 20_000
