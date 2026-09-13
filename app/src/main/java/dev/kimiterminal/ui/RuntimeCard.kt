package dev.kimiterminal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kimiterminal.runtime.RuntimeManager
import dev.kimiterminal.runtime.RuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Карточка in-app рантайма: установка payload'а из APK и диагностика цепочки запуска.
 *
 * Держим её на экране до тех пор, пока цепочка не подтверждена целиком. Исчезающий
 * индикатор «что-то не так» здесь хуже, чем лишний ряд:exec по симлинку решает SELinux
 * конкретного устройства, и молчать об этом нельзя.
 */
@Composable
fun RuntimeCard(manager: RuntimeManager, onReady: () -> Unit = {}) {
    val status by manager.status.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Приход на экран: проверяем уже установленный payload, а не начинаем качать заново.
    // Первый запуск ставим сами: содержимое лежит внутри APK, сети нет, спрашивать не о чем.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            manager.resume()
            if (manager.status.value is RuntimeStatus.Empty) manager.install()
            if (manager.status.value is RuntimeStatus.Ready) onReady()
        }
    }

    val ready = status as? RuntimeStatus.Ready
    // Факультативная проба (kimi) не прячет карточку: её отказ — это «поставь CLI»,
    // а не «платформа не пускает», и пользователю видно различие.
    val allOk = ready != null && ready.checks.isNotEmpty() &&
        ready.checks.none { it.startsWith("НЕТ") && "не критично" !in it }
    if (allOk && !expanded) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            Text(
                "рантайм готов · Kimi и терминал внутри приложения",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = true }) { Text("проверки", fontSize = MaterialTheme.typography.labelMedium.fontSize) }
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(headline(status), style = MaterialTheme.typography.bodyMedium)
            when (val st = status) {
                is RuntimeStatus.Broken -> CheckList(st.reasons)
                is RuntimeStatus.Ready -> CheckList(st.checks)
                else -> {}
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                when (status) {
                    is RuntimeStatus.Installing -> Button(onClick = {}, enabled = false) { Text("установка…") }
                    else -> Button(onClick = {
                        scope.launch { withContext(Dispatchers.IO) { manager.install() } }
                    }) { Text(if (status is RuntimeStatus.Ready) "переустановить" else "установить") }
                }
                if (ready != null) {
                    TextButton(onClick = { expanded = false }) { Text("свернуть") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { manager.resume() } }
                }) { Text("повторить пробы") }
            }
        }
    }
}

private fun headline(st: RuntimeStatus): String = when (st) {
    is RuntimeStatus.Empty -> "Терминал и Kimi внутри приложения не установлены. Нужно один раз скачать ~150 МБ."
    is RuntimeStatus.Installing -> "Установка ${st.done}/${st.total} · ${st.current}"
    is RuntimeStatus.Broken -> "Установка не завершена (${st.reasons.size} проблем). Ниже — что именно отвалилось."
    is RuntimeStatus.Ready -> "Установлено. Проверки цепочки запуска:"
}

@Composable
private fun CheckList(lines: List<String>) {
    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
        lines.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (it.startsWith("OK")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}
