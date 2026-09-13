package dev.kimiterminal.runtime

import java.io.File

/**
 * Проверки цепочки запуска, каждая отделяет одно звено от следующего.
 *
 * Порядок не случаен — от дешёвого к рискованному:
 *  1. загрузчик: узаконен только exec из nativeLibraryDir, а glibc в Android нет;
 *     провал здесь означает, что payload выложен не туда или не распакован;
 *  2. симлинк: bin/sh указывает на libkexec.so в чужом каталоге — SELinux смотрит на
 *     метку цели, а не ссылки. Это единственное звено, которое нельзя доказать вне
 *     живого устройства, поэтому оно вынесено отдельно;
 *  3. PATH изнутри: kexec читает basename(argv[0]), значит имя дошло, а нода должна
 *     находиться так же, как её ищет дочерний процесс kimi;
 *  4. сам kimi — уже не про платформу, а про состав payload: он проходит только если
 *     таблица scripts/, симлинк, диспетчер, загрузчик и точка входа CLI согласованы.
 *
 * Разделённые, они говорят «что чинить»; слитые в одну — только «не работает».
 */
data class ProbeSpec(
    val name: String,
    val argv: List<String>,
    /** Префикс ожидаемого stdout; пустая строка — достаточно нулевого кода возврата. */
    val expect: String,
    /** Факультативные проверки не блокируют терминал: у них другой класс причин. */
    val optional: Boolean = false,
)

object RuntimeProbes {

    /** Имя первой проверки: по нему RuntimeManager решает, можно ли запускать агента. */
    const val LOADER = "загрузчик и glibc"

    fun specs(layout: RuntimeLayout): List<ProbeSpec> {
        val sh = File(layout.binDir, "sh").path
        return listOf(
            ProbeSpec(LOADER, layout.command("sh", listOf("-c", "echo ok")), "ok"),
            ProbeSpec("exec по симлинку из filesDir", listOf(sh, "-c", "echo ok"), "ok"),
            ProbeSpec(
                "PATH и glibc-нода внутри оболочки",
                listOf(sh, "-c", "node --version && echo PATH=" + DOLLAR + "PATH"),
                "v",
            ),
            ProbeSpec("kimi из таблицы скриптов", listOf(sh, "-c", "kimi --version"), "", optional = true),
        )
    }

    /** Сколько обязательных проверок должно сойтись, чтобы считать оболочку рабочей. */
    val REQUIRED: Int = 3

    /**
     * Доллар в Kotlin-строке — начало шаблона, поэтому он вставлен константой:
     * иначе компилятор ищет идентификатор PATH вместо текста.
     */
    const val DOLLAR = "\u0024"
}
