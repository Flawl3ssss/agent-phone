package dev.kimiterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kimiterminal.AgentViewModel
import dev.kimiterminal.Mode
import dev.kimiterminal.jvmAvailable
import dev.kimiterminal.acp.PermissionOption
import dev.kimiterminal.acp.SessionState
import dev.kimiterminal.agent.Item
import dev.kimiterminal.agent.PermissionPrompt

/**
 * UI-слой (CHARTER §9). Бюджет хрома: AppBar 56 + статус 24 + композер ≤ 40 = ≤120dp.
 * Таргеты ≥48dp. Тёмная тема — единственная.
 */

// ── палитра по дизайн-системе ────────────────────────────────────────────────
val Bg = Color(0xFF0E1116)
val Surface1 = Color(0xFF161B22)
val SurfaceV = Color(0xFF1E2836)
val Primary = Color(0xFF4CC38A)
val OnPrimary = Color(0xFF06281A)
val Secondary = Color(0xFF7AB8FF)
val Tertiary = Color(0xFFD0A8FF)
val ErrorC = Color(0xFFFF8A80)
val Warning = Color(0xFFFBBF24)
val Success = Color(0xFF34D399)
val Info = Color(0xFF60A5FA)
val Outline = Color(0xFF2E3642)
val TermFg = Color(0xFFE6EDF3)

/** Корень приложения. Отдельно от Activity: compose-плагин не должен видеть Activity-класс. */
@Composable
fun Root(factory: androidx.lifecycle.ViewModelProvider.Factory) {
    val vm: AgentViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
    val mode by vm.mode.collectAsState()
    val hasJava = jvmAvailable()
    AgentPhoneApp(
        vm = vm,
        defaultMode = if (hasJava) Mode.MockProcess else Mode.MockInProcess,
        jvmPresent = hasJava,
        modeOverride = mode,
    )
}

/**
 * Корень. `defaultMode` выбирается по наличию JVM: на телефоне второго java нет,
 * поэтому мок идёт in-process; на хосте — отдельным процессом (честнее по транспорту).
 */
@Composable
fun AgentPhoneApp(
    vm: AgentViewModel,
    defaultMode: Mode = Mode.MockInProcess,
    jvmPresent: Boolean = false,
    modeOverride: Mode? = null,
) {
    LaunchedEffect(Unit) { vm.setMode(defaultMode) }
    AgentTheme { MainScreen(vm) }
}

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = Bg, surface = Surface1, onSurface = TermFg, onBackground = TermFg,
            primary = Primary, onPrimary = OnPrimary, secondary = Secondary, tertiary = Tertiary,
            error = ErrorC, outline = Outline,
            surfaceVariant = SurfaceV, onSurfaceVariant = Color(0xFFA9B4C0),
        ),
        typography = MaterialTheme.typography,
        content = content,
    )
}

fun stateColor(s: SessionState): Color = when (s) {
    SessionState.RUNNING, SessionState.READY -> Primary
    SessionState.WAITING_APPROVAL, SessionState.WAITING_USER, SessionState.AUTH_REQUIRED -> Warning
    SessionState.DONE -> Success
    SessionState.FAILED, SessionState.DEAD -> ErrorC
    SessionState.CANCELLED -> Info
    SessionState.IDLE -> Outline
}

fun stateLabel(s: SessionState): String = when (s) {
    SessionState.IDLE -> "ОЖИДАНИЕ"
    SessionState.AUTH_REQUIRED -> "НУЖЕН ВХОД"
    SessionState.READY -> "ГОТОВ"
    SessionState.RUNNING -> "РАБОТАЕТ"
    SessionState.WAITING_APPROVAL -> "ЖДЁТ РАЗРЕШЕНИЯ"
    SessionState.WAITING_USER -> "ЖДЁТ ОТВЕТА"
    SessionState.DONE -> "ГОТОВО"
    SessionState.FAILED -> "ОШИБКА"
    SessionState.CANCELLED -> "ОСТАНОВЛЕНО"
    SessionState.DEAD -> "ПРОЦЕСС УМЕР"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AgentViewModel) {
    val items by vm.items.collectAsState()
    val state by vm.state.collectAsState()
    val reason by vm.reason.collectAsState()
    val permission by vm.permission.collectAsState()
    val busy by vm.busy.collectAsState()
    val init by vm.init.collectAsState()
    val mode by vm.mode.collectAsState()
    val activeProvider by vm.activeProvider.collectAsState()
    var showCaps by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showProviders by remember { mutableStateOf(false) }
    var showTerm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Column(Modifier.height(80.dp)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TermFg),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Agent Phone", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            // Мок не должен притворяться агентом даже видом: пока работает
                            // MockAcpAgent, в шапке висит явный знак. Без него скриншот
                            // «0.42.0-mock-kotlin» можно принять за живой Kimi, и человек
                            // будет полчаса искать, почему «Kimi» ничего не думает.
                            // Termux-режим — тоже НЕ мок: там агент настоящий,
                            // вешать на него знак «МОК» значит врать пользователю.
                            if (mode !is Mode.Kimi && mode !is Mode.Termux && mode !is Mode.Local) {
                                AssistChip(
                                    onClick = { showCaps = true },
                                    label = { Text("МОК", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                            AssistChip(onClick = { showCaps = true }, label = {
                                Text(init?.agentInfo?.let { "${it.name?.split(" ")?.last() ?: "agent"} ${it.version}" } ?: "не подключено",
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            })
                            // Режим на виду: иначе «почему агент молчит» распутывается
                            // только через диалог, а молчание Termux-моста — частый случай.
                            AssistChip(onClick = { showMode = true }, label = {
                                Text(mode.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            })
                        }
                    },
                    actions = {
                        TextButton(onClick = { showProviders = true }) {
                            Text(
                                if (activeProvider?.hasKey == true) "ключ ${activeProvider?.masked}" else "ключи",
                                color = if (activeProvider?.hasKey == true) Primary else Warning,
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            )
                        }
                        // Терминал рядом с чатом: агент и оболочка должны видеть один и тот
                        // же рантайм, иначе «проверить, что он вообще жив» приходится вслепую.
                        TextButton(onClick = { showTerm = true }) { Text("терминал", color = Secondary, fontSize = 13.sp) }
                        TextButton(onClick = { showLog = true }) { Text("лог", color = Secondary, fontSize = 13.sp) }
                        OutlinedButton(onClick = { vm.connectOrRestart() }, shape = RoundedCornerShape(12.dp)) {
                            Text(if (init == null) "старт" else "рестарт", fontSize = 13.sp)
                        }
                    },
                    modifier = Modifier.height(56.dp),
                )
                StatusBar(state, reason, vm.statsText, busy)
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            // Рантайм выше ленты: пока цепочка не подтверждена, она и есть ответ
            // на вопрос «почему агент не запускается».
            vm.localRuntime?.let { RuntimeCard(it) }
            Feed(items, Modifier.weight(1f))
            Composer(
                enabled = !busy && init != null,
                onSend = { vm.send(it) },
                onCancel = { vm.cancelTurn() },
                onRollback = { vm.rollback() },
            )
        }
    }

    permission?.let { PermissionDialog(it) { outcome -> it.reply(outcome) } }
    if (showCaps) init?.let { CapsDialog(it) { showCaps = false } }
    if (showMode) ModeDialog(
        current = mode,
        onDismiss = { showMode = false },
        onPick = { m -> vm.setMode(m); showMode = false; vm.connectOrRestart() },
    )
    if (showLog) LogDialog(vm.stderrTail()) { showLog = false }
    if (showProviders) ProvidersDialog(vm) { showProviders = false }
    if (showTerm) TerminalOverlay(vm) { showTerm = false }
}

@Composable
fun StatusBar(s: SessionState, reason: String?, stats: String, busy: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(Surface1).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(stateColor(s)))
        Spacer(Modifier.width(6.dp))
        Text(stateLabel(s), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = stateColor(s), letterSpacing = 0.5.sp)
        if (!reason.isNullOrBlank()) {
            Text(" · $reason", fontSize = 11.sp, color = Color(0xFFA9B4C0), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Text(stats, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF6B7785), maxLines = 1)
        if (busy) { Spacer(Modifier.width(6.dp)); CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Primary) }
    }
}

@Composable
fun Feed(items: List<Item>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedListAutoScroll(listState, items.size)
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth().background(Bg), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 8.dp, 12.dp, 8.dp)) {
        items(items, key = { it.id }) { item -> ItemCard(item) }
    }
}

@Composable
private fun LaunchedListAutoScroll(state: androidx.compose.foundation.lazy.LazyListState, count: Int) {
    androidx.compose.runtime.LaunchedEffect(count) { if (count > 0) state.animateScrollToItem(count - 1) }
}

@Composable
fun ItemCard(item: Item) {
    when (item) {
        is Item.User -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            Text(item.text, color = OnPrimary, fontSize = 14.sp, modifier = Modifier
                .clip(RoundedCornerShape(16.dp)).background(Primary).padding(12.dp))
        }
        is Item.AgentText -> Bubble(item.text, Surface1, TermFg, "агент", Primary)
        is Item.Thought -> Bubble(item.text, Color(0xFF141A21), Color(0xFF8FA0B0), "размышление", Tertiary, italic = true)
        is Item.Notice -> Bubble(item.text, if (item.error) Color(0xFF2A1518) else SurfaceV,
            if (item.error) ErrorC else Color(0xFF9FB0C0), if (item.error) "ошибка" else "система", if (item.error) ErrorC else Info)
        is Item.Tool -> ToolCard(item)
        is Item.Plan -> PlanCard(item)
        is Item.Usage -> UsageCard(item)
    }
}

@Composable
private fun Bubble(text: String, bg: Color, fg: Color, tag: String, accent: Color, italic: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(tag.uppercase(), fontSize = 10.sp, color = accent, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Text(text, fontSize = 14.sp, color = fg, fontFamily = if (italic) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).clip(RoundedCornerShape(16.dp)).background(bg).padding(12.dp))
    }
}

@Composable
private fun ToolCard(t: Item.Tool) {
    val accent = when (t.status) {
        "completed" -> Success; "failed" -> ErrorC; "in_progress" -> Warning; else -> Info
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).border(1.dp, Outline, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(t.title, fontSize = 13.sp, color = TermFg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text(t.kind, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = accent)
            }
            t.path?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            t.output?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFB9C6D4),
                    maxLines = 8, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg).padding(8.dp))
            }
            t.diffNew?.let { nt ->
                Spacer(Modifier.height(6.dp))
                val old = (t.diffOld ?: "").lines()
                val added = nt.lines().filterIndexed { i, l -> i >= old.size || old[i] != l }
                Text(if (added.isEmpty()) "изменён" else "+${added.size} строк", fontSize = 11.sp, color = Success, fontFamily = FontFamily.Monospace)
                added.take(6).forEach { Text(it, fontSize = 11.sp, color = Success, fontFamily = FontFamily.Monospace, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun PlanCard(p: Item.Plan) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceV), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("ПЛАН · ${p.entries.size}", fontSize = 10.sp, color = Tertiary, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(6.dp))
            p.entries.forEach { e ->
                val c = when (e.status) { "completed" -> Success; "in_progress" -> Warning; else -> Color(0xFF7C8896) }
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                    Text(when (e.status) { "completed" -> "☑ "; "in_progress" -> "▸ "; else -> "○ " }, color = c, fontSize = 13.sp)
                    Text(e.content, color = TermFg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(e.priority, color = c, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun UsageCard(u: Item.Usage) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        Text(
            "${u.used} / ${u.size} ток." + (u.amount?.let { " · %.3f %s".format(it, u.currency ?: "") } ?: ""),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF70808F),
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Surface1).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun Composer(enabled: Boolean, onSend: (String) -> Unit, onCancel: () -> Unit, onRollback: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(Surface1).border(1.dp, Outline)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel, enabled = true) { Text("стоп", color = ErrorC, fontSize = 12.sp) }
            TextButton(onClick = onRollback, enabled = true) { Text("откат turn", color = Warning, fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            Text(if (enabled) "готов" else "занят/нет сессии", fontSize = 10.sp, color = Color(0xFF6B7785))
            Spacer(Modifier.width(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 140.dp),
                placeholder = { Text("сообщение агенту…", color = Color(0xFF5C6875)) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, unfocusedBorderColor = Outline,
                    focusedContainerColor = Bg, unfocusedContainerColor = Bg, cursorColor = Primary),
                shape = RoundedCornerShape(12.dp), maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } },
                enabled = enabled && text.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary, disabledContainerColor = SurfaceV, disabledContentColor = Color(0xFF5C6875)),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("→", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun PermissionDialog(p: PermissionPrompt, onChoose: (dev.kimiterminal.acp.PermissionOutcome) -> Unit) {
    // Material3 1.3.1: у AlertDialog нет слота «произвольный контент», поэтому свой Dialog.
    // Кнопки перебираются из options[].kind — мы не изобретаем агенту варианты ответа.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onChoose(dev.kimiterminal.acp.PermissionOutcome("cancelled")) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Surface1, RoundedCornerShape(20.dp))
                .border(1.dp, Outline, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Агент просит разрешение", color = Warning, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(p.title, color = TermFg, fontSize = 14.sp)
            Text("тип: ${p.kind}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Tertiary)
            if (p.path.isNotBlank()) {
                Text(p.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Secondary,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Divider(color = Outline)
            p.request.options.forEach { o ->
                PermissionOptionRow(o) {
                    onChoose(dev.kimiterminal.acp.PermissionOutcome("selected", o.optionId))
                }
            }
            TextButton(
                onClick = { onChoose(dev.kimiterminal.acp.PermissionOutcome("cancelled")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("отмена", color = Color(0xFF6B7785), fontSize = 12.sp) }
            Text(
                "Варианты взяты из options[].kind — агент решает, что можно предложить.",
                fontSize = 10.sp, color = Color(0xFF6B7785),
            )
        }
    }
}

@Composable
private fun PermissionOptionRow(o: PermissionOption, onClick: () -> Unit) {
    val allow = o.kind.startsWith("allow")
    val always = o.kind.endsWith("always")
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (allow) (if (always) Color(0xFF1B3A2A) else Primary) else SurfaceV,
            contentColor = if (allow) (if (always) Primary else OnPrimary) else TermFg,
        ),
    ) { Text(o.name, fontSize = 14.sp) }
}

@Composable
fun CapsDialog(caps: dev.kimiterminal.acp.InitializeResponse, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose, containerColor = Surface1,
        title = { Text("Возможности агента", color = TermFg, fontSize = 16.sp) },
        text = {
            val c = caps.agentCapabilities
            Column(Modifier.heightIn(max = 420.dp)) {
                Text("${caps.agentInfo?.name} ${caps.agentInfo?.version} · протокол v${caps.protocolVersion}",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Secondary)
                Spacer(Modifier.height(10.dp))
                listOf(
                    "loadSession" to c.loadSession,
                    "prompt.image" to c.promptCapabilities.image,
                    "prompt.audio" to c.promptCapabilities.audio,
                    "prompt.embeddedContext" to c.promptCapabilities.embeddedContext,
                    "mcp.http" to c.mcpCapabilities.http,
                    "mcp.sse" to c.mcpCapabilities.sse,
                    "session.list" to (c.sessionCapabilities.list != null),
                    "session.resume" to (c.sessionCapabilities.resume != null),
                    "session.close" to (c.sessionCapabilities.close != null),
                    "session.delete" to (c.sessionCapabilities.delete != null),
                    "session.fork" to (c.sessionCapabilities.fork != null),
                    "session.additionalDirectories" to (c.sessionCapabilities.additionalDirectories != null),
                    "auth.logout" to (c.auth.logout != null),
                ).forEach { (k, v) -> CapRow(k, v) }
                Spacer(Modifier.height(10.dp))
                Text("authMethods: " + caps.authMethods.joinToString { "${it.type}:${it.id}" },
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF9FB0C0))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("закрыть", color = Primary) } },
    )
}

@Composable
private fun CapRow(k: String, v: Boolean) {
    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (v) "✔" else "✘", color = if (v) Success else ErrorC, fontSize = 12.sp, modifier = Modifier.width(18.dp))
        Text(k, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (v) TermFg else Color(0xFF6B7785))
    }
}

@Composable
fun LogDialog(lines: List<String>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose, containerColor = Surface1,
        title = { Text("stderr агента", color = TermFg, fontSize = 16.sp) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(lines) { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF9FB0C0)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("закрыть", color = Primary) } },
    )
}

/**
 * Переключатель агента. Выбор сразу переподключает сессию: отдельная кнопка «применить»
 * оставляет окно, в котором промпт может уйти не тому агенту.
 */
@Composable
private fun ModeDialog(current: Mode, onDismiss: () -> Unit, onPick: (Mode) -> Unit) {
    val options = listOf(
        Mode.Local to "kimi из in-app рантайма: терминал и агент внутри приложения, снаружи не ставится ничего",
        Mode.MockInProcess to "мок в этом процессе — UI и протокол работают без внешнего агента",
        Mode.MockProcess to "мок отдельным JVM — нужен java на устройстве",
        Mode.Termux() to "настоящий kimi acp из Termux: мост слушает 127.0.0.1:8712",
    )
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface1,
        title = { Text("Кого подключаем", color = TermFg, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "Выбор сразу перезапускает сессию.",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Secondary,
                )
                Spacer(Modifier.height(12.dp))
                options.forEach { (m, hint) ->
                    FilterChip(
                        selected = m::class == current::class,
                        onClick = { onPick(m) },
                        label = { Text(m.label, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                    )
                    Text(hint, fontSize = 10.sp, color = Secondary, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("закрыть", fontSize = 12.sp) } },
    )
}
