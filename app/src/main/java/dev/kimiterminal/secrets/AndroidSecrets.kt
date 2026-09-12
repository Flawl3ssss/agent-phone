package dev.kimiterminal.secrets

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ключи в AndroidKeyStore, на диск — только AES/GCM(iv || ct).
 *
 * Почему не `androidx.security:security-crypto`: это alpha-пакет, он тянет собственный
 * зависимости-круг и всё равно хранит всё в одном SharedPreferences-файле, который при
 * рассинхроне MasterKey теряет ВЕСЬ файл. Здесь один ключ на всё хранилище и запись
 * деградирует поштучно: сломалась одна запись — пропал один ключ, а не весь список.
 *
 * Что это НЕ защищает: рут-устройство с дампом памяти процесса, и пока приложение
 * живое, ключ лежит в env дочернего процесса — любой процесс с uid приложения его
 * прочитает. Это осознанная граница (CHARTER §9): цель — не дать ключу уехать на
 * диск в открытом виде и в бэкап, а не остановить атакующего с root.
 */
class KeystoreSecretBox(private val prefs: SharedPreferences) : SecretBox {

    private fun key(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate()
    }.getOrNull()

    private fun generate(): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Нарочно НЕ setUnlockedDeviceRequired / биометрия: иначе ключ перестаёт
                // читаться после перезагрузки или перерегистрации отпечатка, а на части
                // прошивок — и вовсе при погасшем экране.
                .build()
        )
        return kg.generateKey()
    }

    override fun put(id: String, secret: String) {
        val k = key() ?: throw IllegalStateException("Keystore недоступен")
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, k)
        val iv = c.iv
        val ct = c.doFinal(secret.toByteArray(Charsets.UTF_8))
        // Длина IV у GCM в Android всегда 12, но пишем его префиксом, а не «знаем»:
        // иначе любая будущая смена трансформации прочитает старые записи с мусором.
        val blob = ByteArray(iv.size + ct.size).apply {
            iv.copyInto(this); ct.copyInto(this, iv.size)
        }
        prefs.edit().putString(PREFIX + id, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override fun get(id: String): String? = runCatching {
        val b64 = prefs.getString(PREFIX + id, null) ?: return null
        val blob = Base64.decode(b64, Base64.NO_WRAP)
        val k = key() ?: return null
        val c = Cipher.getInstance(TRANSFORM)
        // IV = первые 12 байт; GCM у Android всегда 12.
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 0, IV_LEN))
        String(c.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
    }.getOrElse {
        // Ключ Keystore не переживает сброс блокировки/разблок по биометрии на части
        // прошивок. Молча потерять все ключи — хуже, чем потерять один: убираем запись
        // и сигнализируем наружу null, UI покажет «ключ потерян, введите заново».
        prefs.edit().remove(PREFIX + id).apply()
        null
    }

    override fun drop(id: String) { prefs.edit().remove(PREFIX + id).apply() }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "agent-phone-master"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val PREFIX = "sk_"
    }
}

/** Точка сборки: реестр провайдеров, привязанный к приватным prefs приложения. */
object Secrets {
    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("agent_phone_secrets", Context.MODE_PRIVATE)

    fun registry(c: Context): ProviderRegistry {
        val p = prefs(c)
        return ProviderRegistry(
            box = KeystoreSecretBox(p),
            load = { p.getString(META, null) },
            save = { p.edit().putString(META, it).apply() },
        )
    }

    /** Метаданные (label/base/model/маска) — не секрет, лежат открыто. */
    const val META = "providers_meta"
}
