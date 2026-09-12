package dev.kimiterminal.runtime

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** Источник установочных файлов: в приложении — assets, в тесте — обычный каталог. */
interface PayloadSource {
    /** null — файла нет. Путь относительный, как в репозитории (assets/runtime/...). */
    fun open(assetPath: String): InputStream?
}

/** Реализация поверх каталога: для тестов и отладки на хосте. */
class DirectorySource(private val root: File) : PayloadSource {
    override fun open(assetPath: String): InputStream? {
        val f = File(root, assetPath)
        return if (f.isFile) f.inputStream() else null
    }
}

/** Ход установки: сколько файлов готово из скольки и что делают сейчас. */
data class InstallProgress(val done: Int, val total: Int, val current: String)

/**
 * Устанавливает payload из манифеста в раскладку [RuntimeLayout].
 *
 * Порядок защищён тестами:
 *  1) сначала только проверка (манифест, наличие ELF в nativeLibraryDir и их sha256) —
 *     до того как что-либо записано;
 *  2) копируются читаемые файлы (библиотеки, замыкание kimi, сертификаты) в filesDir;
 *  3) симлинки PATH на kexec: они ведут в nativeLibraryDir, где exec разрешён;
 *  4) READY пишется последним — оборванная установка не должна считаться готовой.
 */
class RuntimeInstaller(
    private val source: PayloadSource,
    private val layout: RuntimeLayout,
    private val manifest: RuntimeManifest,
    private val onProgress: (InstallProgress) -> Unit = {},
    private val log: (String) -> Unit = {},
) {

    /** Манифест описывает пути от корня репозитория; AssetManager и filesDir живут без этого префикса. */
    private val repoPrefix = "app/src/main/"
    private val payloadPrefix = "app/src/main/assets/"

    /** Пустой список — успех; иначе человекочитаемые причины. В UI ничего не бросаем. */
    fun install(): List<String> {
        val complaints = manifest.problems().toMutableList()
        if (complaints.isNotEmpty()) {
            log("манифест неприемлем: $complaints")
            return complaints
        }

        // ELF устанавливает система вместе с APK; скопировать их в nativeLibraryDir мы не
        // можем, поэтому проверяем до всякой записи: иначе half-installed рантайм потом
        // объяснять придётся пользователю.
        for (e in manifest.entries.filter { it.path.startsWith(repoPrefix + "jniLibs/") }) {
            val name = e.path.substringAfterLast('/').removePrefix("lib").removeSuffix(".so")
            val target = layout.elf(name)
            if (!target.isFile) {
                complaints += "нет $name в nativeLibraryDir (APK собран без jniLibs?)"
            } else if (sha256Of(target) != e.sha256) {
                complaints += "sha256 $name не совпадает с манифестом"
            }
        }
        if (complaints.isNotEmpty()) {
            log("проверка не пройдена: $complaints")
            return complaints
        }

        val copies = manifest.entries.filter { it.path.startsWith(payloadPrefix) }
        val links = layout.binLinks()
        val total = copies.size + links.size + 1
        var done = 0

        for (e in copies) {
            onProgress(InstallProgress(done, total, File(e.path).name))
            val problem = copyAndVerify(e.path, File(layout.filesDir, e.path.removePrefix(payloadPrefix)), e.sha256)
            if (problem != null) return listOf(problem)
            done++
        }

        for ((name, targetPath) in links) {
            onProgress(InstallProgress(done, total, "bin/" + name))
            val problem = link(File(layout.binDir, name), File(targetPath))
            if (problem != null) return listOf(problem)
            done++
        }

        onProgress(InstallProgress(done, total, "READY"))
        val finish = runCatching {
            for (dir in layout.directories()) dir.mkdirs()
            layout.bashrcFile.writeText(BASHRC)
            layout.readyFile.writeText(RuntimeSpec.SPEC_VERSION)
        }
        if (finish.isFailure) return listOf("не удалось записать READY: ${finish.exceptionOrNull()?.message}")

        log("файлов установлено: ${copies.size}, ссылок: ${links.size}")
        val missing = layout.missingPieces()
        return if (missing.isEmpty()) emptyList() else listOf("после установки не хватает: $missing")
    }

    private fun copyAndVerify(assetPath: String, target: File, expectedSha: String): String? {
        val attempt = runCatching {
            target.parentFile?.mkdirs()
            val input = source.open(assetPath.removePrefix(payloadPrefix))
                ?: return@runCatching "в APK нет файла $assetPath"
            input.use { src -> target.outputStream().use { src.copyTo(it) } }
            if (sha256Of(target) != expectedSha) return@runCatching "повреждён $assetPath"
            null
        }
        return attempt.fold(
            onSuccess = { it },
            onFailure = { "сбой на $assetPath: ${it.message}" },
        )
    }

    private fun link(link: File, target: File): String? {
        val attempt = runCatching {
            link.parentFile?.mkdirs()
            val path = link.toPath()
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(path)
            Files.createSymbolicLink(path, target.toPath())
            null
        }
        return attempt.getOrElse { "симлинк ${link.name} не создался: ${it.message}" }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * Минимальный rc-файл оболочки. PATH задаётся окружением процесса, поэтому в PS1 нет
 * символа доллара: он потребовал бы экранирования в Kotlin-строке, а это лишний класс
 * ошибок на пустом месте.
 */
private val BASHRC = """
    export PS1='[kimi] \w> '
    alias ll='ls -l'
    set -o emacs
    bind 'set show-all-if-ambiguous on' 2>/dev/null || true
""".trimIndent()
