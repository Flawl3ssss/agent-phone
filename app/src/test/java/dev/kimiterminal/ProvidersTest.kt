package dev.kimiterminal

import dev.kimiterminal.secrets.InMemorySecretBox
import dev.kimiterminal.secrets.ProviderKind
import dev.kimiterminal.secrets.ProviderRegistry
import dev.kimiterminal.secrets.maskSecret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Реестр провайдеров — единственная часть Ф2.7, которая тестировается без устройства:
 * криптография живёт в AndroidKeyStore, а вот «что именно утекает в сериализуемое
 * состояние и в окружение процесса» — чистая логика. Проверяю в основном запретительные
 * ветки: то, что ключ не уехал в plaintext, важнее, чем то, что он вообще сохраняется.
 */
class ProvidersTest {

    /** Хранилище как в проде: строка-снимок наружу, секреты в отдельном боксе. */
    private class Harness(val box: InMemorySecretBox = InMemorySecretBox()) {
        var text: String? = null
        val reg = ProviderRegistry(
            box,
            load = { text },
            save = { text = it },
        )
    }

    private val goodKey = "sk-proj-Q3R5T6Y7U8I9O0P1A2S3D4F6G7H8J9K0"

    @Test fun `ключ маскируется так чтобы не утечь`() {
        val m = maskSecret(goodKey)
        assertTrue("маска короткая: $m", m.length <= 12)
        assertTrue("начало сохранено: $m", m.startsWith("sk"))
        assertTrue("конец сохранён: $m", m.endsWith("J9K0"))
        assertFalse("в маске нет тела ключа", m.contains("Q3R5T6Y7U8I9O0P1"))
        assertEquals("короткий ключ целиком под точками", "••••••", maskSecret("abc"))
        assertEquals("пустой", "", maskSecret(""))
        assertEquals("null", "", maskSecret(null))
    }

    @Test fun `кривой ключ не сохраняется вообще`() {
        val h = Harness()
        for (bad in listOf("", "   ", "ключ", "короткий", "sk-abc", "ab\ncd", "x".repeat(300))) {
            val saved = kotlin.runCatching {
                h.reg.upsert("k$bad", "K", ProviderKind.MOONSHOT, "", "", bad)
            }.isSuccess
            assertFalse("прошёл мусор как ключ: «$bad»", saved)
        }
        assertTrue("реестр должен остаться пустым", h.reg.all().isEmpty())
    }

    @Test fun `свой провайдер без base url отклоняется`() {
        val h = Harness()
        // MOONSHOT имеет дефолтный URL — ему пусто допустимо.
        val ok = h.reg.upsert("m", "Moonshot", ProviderKind.MOONSHOT, "", "", goodKey)
        assertEquals("base подставился из пресета", "https://api.moonshot.ai/v1", ok.baseUrl)

        val ex = kotlin.runCatching {
            h.reg.upsert("c", "Custom", ProviderKind.CUSTOM, "", "", goodKey)
        }.exceptionOrNull()
        assertTrue("без base URL бросается IllegalArgumentException", ex is IllegalArgumentException)
        assertEquals("CUSTOM не добавился", 1, h.reg.all().size)
    }

    @Test fun `ключ не попадает в сериализуемое состояние`() {
        val h = Harness()
        h.reg.upsert("p1", "Основной", ProviderKind.ANTHROPIC, "", "claude-sonnet-4-5", goodKey)

        val snapshot = h.text ?: error("реестр ничего не записал")
        assertFalse("секрет утёк в plaintext-снимок", snapshot.contains(goodKey))
        // Проверяю positively: в снимке лежит МАСКА. «Не содержит секрета» само по себе
        // прошло бы и на полностью пустом файле — то есть ничего не доказало бы.
        val mask = maskSecret(goodKey)
        assertTrue("в снимке маска «$mask», а не она", snapshot.contains(mask))

        // И в памяти модели его тоже нет.
        assertFalse("secret в модели провайдера", h.reg.all().first().toString().contains(goodKey))
        assertEquals("а в боксе — есть", goodKey, h.box.get("p1"))
    }

    @Test fun `перезапуск читает то же самое из другого реестра`() {
        val h = Harness()
        h.reg.upsert("a", "Первый", ProviderKind.MOONSHOT, "", "", goodKey)
        h.reg.upsert("b", "Второй", ProviderKind.OPENAI, "", "", "sk-" + "Z".repeat(36))
        h.reg.setActive("b")

        val reopened = ProviderRegistry(h.box, load = { h.text }, save = { })
        val list = reopened.all()
        assertEquals("провайдеров восстановилось", 2, list.size)
        assertEquals("активный тот же", "b", reopened.active()?.id)
        assertEquals("метаданные доехали", "Первый", list.first { it.id == "a" }.label)
        assertEquals("ключ достанется агенту", goodKey, reopened.secretOf("a"))
    }

    @Test fun `окружение зависит от типа провайдера`() {
        val h = Harness()
        h.reg.upsert("o", "O", ProviderKind.OPENAI, "", "gpt-5", goodKey)
        var env = h.reg.envFor()
        assertEquals("SDK OpenAI ждёт OPENAI_API_KEY", goodKey, env["OPENAI_API_KEY"])
        assertNull("чужое имя переменной не засоряем", env["MOONSHOT_API_KEY"])

        h.reg.upsert("m", "M", ProviderKind.MOONSHOT, "", "kimi-k2", "sk-moonshot-" + "Q".repeat(32))
        h.reg.setActive("m")
        env = h.reg.envFor()
        assertEquals("переключили провайдера — сменилась и переменная",
            "sk-moonshot-" + "Q".repeat(32), env["MOONSHOT_API_KEY"])
        assertNull(env["OPENAI_API_KEY"])

        // Единые имена — чтобы мок и живой агент читали одно и то же.
        assertEquals("единая переменная ключа", env["MOONSHOT_API_KEY"], env["AGENT_PHONE_API_KEY"])
        assertEquals("единая переменная типа", "moonshot", env["AGENT_PHONE_PROVIDER"])
        assertEquals("единая переменная модели", "kimi-k2", env["AGENT_PHONE_MODEL"])
        assertEquals("единая переменная base", "https://api.moonshot.ai/v1", env["AGENT_PHONE_BASE_URL"])
    }

    @Test fun `без активного провайдера окружение не строится`() {
        val h = Harness()
        assertNull("актива нет", h.reg.active())
        // envFor бросает, а не возвращает пусто: молча отдать агенту пустое окружение
        // означало бы «kimi ходит в API без ключа и получает 401» вместо внятной ошибки.
        val ex = kotlin.runCatching { h.reg.envFor() }.exceptionOrNull()
        assertTrue("ожидался IllegalStateException", ex is IllegalStateException)

        h.reg.upsert("x", "X", ProviderKind.GOOGLE, "", "gemini-2.5-pro", "AIza" + "W".repeat(35))
        // Первый провайдер становится активным сам — иначе UI показал бы «нет ключей»,
        // хотя пользователь уже всё ввёл.
        assertEquals("первый автоактивен", "x", h.reg.active()?.id)
        assertEquals("Google SDK читает GEMINI_API_KEY",
            "AIza" + "W".repeat(35), h.reg.envFor()["GEMINI_API_KEY"])
    }

    @Test fun `удаление активного переносит активный на соседа`() {
        val h = Harness()
        h.reg.upsert("a", "A", ProviderKind.MOONSHOT, "", "", goodKey)
        h.reg.upsert("b", "B", ProviderKind.OPENAI, "", "", "sk-" + "L".repeat(36))
        h.reg.setActive("a")

        h.reg.remove("a")
        assertEquals("активный переехал", "b", h.reg.active()?.id)
        assertNull("секрет удалённого стёрт из бокса", h.box.get("a"))
        assertEquals("метаданные удалены", 1, h.reg.all().size)

        h.reg.remove("b")
        assertNull("последний удалён — актива нет", h.reg.active())
        // Здесь стояло `assertTrue(envFor().isEmpty())`, что прямо противоречит
        // `без активного провайдера окружение не строится`: тот же вызов требует
        // IllegalStateException. Два разных ответа на одно состояние быть не могло,
        // и в этом тесте стрелял именно этот последний assertion. Оставляю бросок —
        // он задокументирован как продуктовое правило: молча отдать агенту пустой
        // env значит получить 401 вместо внятной ошибки.
        val ex = kotlin.runCatching { h.reg.envFor() }.exceptionOrNull()
        assertTrue("без активного провайдера env не отдаём", ex is IllegalStateException)
    }

    @Test fun `правкой нельзя потерять уже сохранённый ключ`() {
        val h = Harness()
        h.reg.upsert("a", "A", ProviderKind.MOONSHOT, "", "kimi-k1", goodKey)
        val before = h.reg.secretOf("a")

        // UI отдаёт пустую строку, когда поле не трогали: ключ обязан остаться.
        h.reg.upsert("a", "A переименован", ProviderKind.MOONSHOT, "https://example.test/v1", "kimi-k2", "")
        val p = h.reg.all().first()
        assertEquals("ключ не перезаписан пустотой", before, h.reg.secretOf("a"))
        assertEquals("метаданные обновились", "A переименован", p.label)
        assertEquals("модель обновилась", "kimi-k2", p.model)
        assertEquals("URL обновился", "https://example.test/v1", p.baseUrl)
        assertTrue("hasKey остался", p.hasKey)

        // Смена типа: старое имя переменной больше не отдаётся, новое — да.
        h.reg.upsert("a", "", ProviderKind.ANTHROPIC, "", "claude-3", "")
        // Метка передана пустой: это «поле не трогали» (тот же контракт, что и у
        // ключа), поэтому ниже и ожидается, что label переименование сохранило.
        val env = h.reg.envFor()
        assertEquals("антропиково имя", before, env["ANTHROPIC_API_KEY"])
        assertNull("луншотово имя исчезло", env["MOONSHOT_API_KEY"])
        val after = h.reg.all().first()
        assertEquals("смена типа не стёрла маску и метаданные", p.masked, after.masked)
        assertEquals("и label тоже", "A переименован", after.label)
    }

    @Test fun `id переиспользуется а не дублируется`() {
        val h = Harness()
        val p1 = h.reg.upsert("", "Первый", ProviderKind.MOONSHOT, "", "", goodKey)
        val p2 = h.reg.upsert(p1.id, "Первый", ProviderKind.MOONSHOT, "", "", goodKey)
        assertEquals("тот же id", p1.id, p2.id)
        assertEquals("один провайдер, не два", 1, h.reg.all().size)
    }
}
