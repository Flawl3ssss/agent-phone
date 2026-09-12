package dev.kimiterminal.runtime

import java.io.File

/**
 * Три проверки, каждая из которых отделяет одно звено цепочки от следующего.
 *
 * Порядок не случайен — от самого дешёвого к самому рискованному:
 *  1. загрузчик: узаконен только exec из nativeLibraryDir, а glibc в Android нет;
 *     провал здесь означает, что payload выложен не туда (или не распакован);
 *  2. симлинк: bin/sh указывает на libkexec.so в чужом каталоге — решение SELinux
 *     по метке цели, а не ссылки. Это единственное звено, которое нельзя доказать
 *     вне живого устройства, поэтому оно и вынесено отдельно;
 *  3. PATH изнутри: kexec читает basename(argv[0]), значит имя команды дошло,
 *     но нода должна найтись так же, как её ищет дочерний процесс kimi.
 *
 * Разделённые, они дают «что чинить»; слитые в одну — только «не работает».
 */
object RuntimeProbes {

    /** Проверяемые команды вместе с ожиданием: первая строка stdout должна быть такой. */
    fun commands(layout: RuntimeLayout): List<Triple<String, List<String>, String>> = listOf(
        Triple(
            "загрузчик и glibc",
            layout.command("sh", listOf("-c", "echo ok")),
            "ok",
        ),
        Triple(
            "exec по симлинку из filesDir",
            listOf(File(layout.binDir, "sh").path, "-c", "echo ok"),
            "ok",
        ),
        Triple(
            "PATH и glibc-нода внутри оболочки",
            listOf(
                File(layout.binDir, "sh").path,
                "-c",
                "node --version && echo PATH=" + DOLLAR + "PATH",
            ),
            "v",
        ),
    )

    /**
     * Доллар в строке Kotlin — это начало шаблона, поэтому он вставлен константой:
     * иначе вместо текста переменной компилятор ищет идентификатор PATH.
     */
    const val DOLLAR = "\u0024"
}
