package dev.kimiterminal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kimiterminal.AgentViewModel
import dev.kimiterminal.secrets.Provider
import dev.kimiterminal.secrets.ProviderKind

/** Приглушённый текст. В Screens.kt таких цветов четыре инлайновых, здесь свой. */
private val Dim = Color(0xFF8B98A5)

/** Черновик формы. Отдельно от [Provider]: секрет живёт только в этом локальном состоянии. */
private data class Draft(
    val id: String,
    val label: String = "",
    val kind: ProviderKind = ProviderKind.MOONSHOT,
    val baseUrl: String = "",
    val model: String = "",
    val keyInput: String = "",
)

/**
 * Экран провайдеров и API-ключей.
 *
 * Что здесь важно не сломать (проверено текстом, а не намерением):
 *  1. Поле ключа при редактировании пустое, и пустое поле при сохранении означает
 *     «не менять», а не «стереть». Иначе правка метки убивает рабочий ключ.
 *  2. Маска вместо ключа. «Показать» достаёт секрет из Keystore в локальное состояние
 *     и ОБЯЗАНО сбрасываться при смене записи — иначе чужой ключ доезжает в следующую форму.
 *  3. Валидация возвращается строкой, а не исключением: исключение из Compose-обработчика
 *     уронит composition, и пользователь увидит пустой экран вместо внятной ошибки.
 */
@Composable
fun ProvidersDialog(vm: AgentViewModel, onClose: () -> Unit) {
    var draft by remember { mutableStateOf<Draft?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    // StateFlow, а не простой List: после сохранения экран обязан перерисоваться.
    val all by vm.providers.collectAsState()
    val activeId by vm.activeProvider.collectAsState()

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Surface1,
        title = { Text(if (draft == null) "Провайдеры" else "Провайдер", color = TermFg, fontSize = 17.sp) },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val d = draft
                if (d == null) {
                    if (all.isEmpty()) {
                        Text(
                            "Ключей нет. Без них живой агент не может сделать ни одного хода — " +
                                "сейчас подключён мок, ему ключ не нужен, поэтому запросов к модели и нет.",
                            color = Dim, fontSize = 12.5.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    all.forEach { p ->
                        ProviderRow(
                            p = p,
                            active = activeId?.id == p.id,
                            onPick = { vm.activateProvider(p.id) },
                            onEdit = {
                                err = null; showKey = false; draft = Draft(p.id, p.label, p.kind, p.baseUrl, p.model)
                            },
                            onDelete = { err = null; vm.deleteProvider(p.id) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            err = null; showKey = false
                            draft = Draft(id = "p%08x".format(System.nanoTime() and 0xffffffffL))
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("+ добавить провайдера") }
                } else {
                    // вид провайдера
                    // Семь чипов в обычный Row — это гарантированная обрезка на телефоне:
                    // «свой» и «OpenRouter» пользователь бы просто не увидел.
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProviderKind.values().forEach { k ->
                            FilterChip(
                                selected = d.kind == k,
                                onClick = {
                                    err = null
                                    draft = d.copy(
                                        kind = k,
                                        baseUrl = if (d.baseUrl.isBlank()) k.defaultBaseUrl else d.baseUrl,
                                    )
                                },
                                label = { Text(k.title, fontSize = 11.sp) },
                                modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
                            )
                        }
                    }
                    Field("название", d.label, placeholder = "напр. Moonshot рабочая") {
                        draft = d.copy(label = it)
                    }
                    Field(
                        "base URL", d.baseUrl,
                        placeholder = d.kind.defaultBaseUrl.ifBlank { "https://мой-сервер:8000/v1" },
                        mono = true,
                    ) { draft = d.copy(baseUrl = it) }
                    Field(
                        "модель", d.model,
                        placeholder = dev.kimiterminal.secrets.ProviderRegistry.defaultModel(d.kind),
                        mono = true,
                    ) { draft = d.copy(model = it) }
                    Field(
                        "API ключ", d.keyInput,
                        placeholder = "sk-…",
                        secret = !showKey,
                        trailing = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "скрыть" else "показать", color = Secondary, fontSize = 11.sp)
                            }
                        },
                    ) { err = null; draft = d.copy(keyInput = it) }
                    Text(
                        "Оставьте поле ключа пустым — ключ не изменится." +
                            " Хранится в AndroidKeyStore (AES/GCM), на диск не пишется открытым.",
                        color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    err?.let { Text(it, color = ErrorC, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { draft = null; err = null; showKey = false }, modifier = Modifier.weight(1f)) {
                            Text("отмена", fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val problem = vm.saveProvider(d.id, d.label, d.kind, d.baseUrl, d.model, d.keyInput)
                                if (problem == null) { draft = null; err = null; showKey = false } else err = problem
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                        ) { Text("сохранить", fontSize = 13.sp) }
                    }
                }
                // Общая ошибка для режима списка (исключение из delete/activate сюда не доходит).
                if (draft == null) err?.let { Text(it, color = ErrorC, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("закрыть", color = Secondary) } },
        dismissButton = {},
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String = "",
    mono: Boolean = false,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = Dim, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailing,
        textStyle = if (mono) androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        else androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}

@Composable
private fun ProviderRow(
    p: Provider,
    active: Boolean,
    onPick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Удаление — в два нажатия. Один тап за «×», и это же «×» стирает ключ без шанса
    // одуматься; для секрета, который пользователь копировал из платёжного аккаунта,
    // это слишком дорогая оплошность.
    // Ключ remember — p.id: без него после удаления строки состояние «уверен?»
    // переезжает на соседа, и первое же нажатие «×» на другой записи удаляет её сразу.
    var sure by remember(p.id) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (active) SurfaceV else Surface1),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.label, color = TermFg, fontSize = 14.sp)
                Text("${p.kind.title} · ${p.model}", color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                if (p.baseUrl.isNotBlank()) {
                    Text(p.baseUrl, color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Text(
                    if (p.hasKey) "ключ ${p.masked}" else "ключ не задан",
                    color = if (p.hasKey) Success else Warning, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
            if (active) AssistChip(onClick = onPick, label = { Text("активен", fontSize = 10.sp) })
            TextButton(onClick = onEdit) { Text("править", color = Secondary, fontSize = 12.sp) }
            Box {
                TextButton(onClick = { if (sure) { onDelete(); sure = false } else sure = true }) {
                    Text(if (sure) "уверен?" else "×", color = ErrorC, fontSize = if (sure) 11.sp else 15.sp)
                }
            }
        }
    }
}
