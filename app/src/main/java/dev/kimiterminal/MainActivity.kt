package dev.kimiterminal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent as setComposeContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import dev.kimiterminal.ui.Root
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // POST_NOTIFICATIONS: без неё T2 молча не работает, а «посмотреть позже» на
        // телефоне — норма, а не исключение. Спрашиваем один раз на старте.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // viewModel() — @Composable, поэтому фабрика передаётся внутрь, а не вызывается здесь.
        val factory = AgentViewModel.factory(applicationContext, javaBinOrNull(), classpathOrNull())
        setComposeContent { Root(factory) }
    }

    /** Путь к java в песочнице хоста; на устройстве null. */
    private fun javaBinOrNull(): String? = runCatching {
        System.getProperty("java.home")?.let { File(it, "bin/java") }
            ?.takeIf { it.canExecute() }?.absolutePath
    }.getOrNull()

    /** Classpath для мокагента: работает на хосте в JVM-тесте, на устройстве бесполезен. */
    private fun classpathOrNull(): String? = runCatching {
        System.getProperty("java.class.path")?.takeIf { it.isNotBlank() && it != "." }
    }.getOrNull()
}
