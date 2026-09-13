package dev.kimiterminal.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kimiterminal.AgentViewModel
import dev.kimiterminal.terminal.TerminalBridge

/**
 * Терминал in-app: xterm.js в WebView, сессия — в PTY-насосе.
 *
 * Байты идут в обе стороны base64: управляющие последовательности и кавычки ломают
 * и вызов JS из Kotlin, и обратный мост, а здесь данные обязаны доходить без изменений.
 * Локального эха нет намеренно — эхо делает настоящая tty, иначе каждый символ
 * удвоится.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalOverlay(vm: AgentViewModel, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val bridge = remember { TerminalBridge(app, vm.localRuntime) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var view by remember { mutableStateOf<WebView?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onClose)
    DisposableEffect(Unit) { onDispose { bridge.stop() } }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "терминал",
                    color = Color(0xFF8B949E),
                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                notice?.let {
                    Text(
                        it,
                        color = Color(0xFFF0883E),
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                TextButton(onClick = { bridge.stop(); notice = null }) {
                    Text("перезапуск", color = Color(0xFF58A6FF), fontSize = MaterialTheme.typography.labelMedium.fontSize)
                }
                TextButton(onClick = onClose) {
                    Text("закрыть", color = Color(0xFF8B949E), fontSize = MaterialTheme.typography.labelMedium.fontSize)
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    makeWebView(
                        ctx, bridge, main,
                        attach = { w -> view = w },
                        onNotice = { notice = it },
                    )
                },
            )
        }
    }
}

/** WebView с мостом. Оболочка поднимается, когда страница сама скажет, что готова. */
@SuppressLint("SetJavaScriptEnabled")
private fun makeWebView(
    ctx: android.content.Context,
    bridge: TerminalBridge,
    main: Handler,
    attach: (WebView) -> Unit,
    onNotice: (String?) -> Unit,
): WebView {
    val web = WebView(ctx)
    web.settings.javaScriptEnabled = true
    // Число, а не Color.rgb: фон виден до первой отрисовки xterm, иначе при открытии вспыхивает белое.
    web.setBackgroundColor(0xFF0D1117.toInt())
    web.isVerticalScrollBarEnabled = false
    web.webViewClient = object : android.webkit.WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            main.post { view.evaluateJavascript("window.pterm && window.pterm.fit()", null) }
        }
    }
    web.addJavascriptInterface(
        object : Any() {
            @JavascriptInterface
            fun input(b64: String) {
                runCatching { bridge.input(Base64.decode(b64, Base64.DEFAULT)) }
            }

            @JavascriptInterface
            fun resize(cols: Int, rows: Int) {
                bridge.resize(cols, rows)
            }

            @JavascriptInterface
            fun ready(cols: Int, rows: Int) {
                bridge.onOutput = { bytes, n ->
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    main.post { web.evaluateJavascript("window.pterm && window.pterm.feed('$b64')", null) }
                }
                onNotice(bridge.start(cols, rows))
            }
        },
        "Android",
    )
    attach(web)
    web.loadUrl("file:///android_asset/terminal/index.html")
    return web
}
