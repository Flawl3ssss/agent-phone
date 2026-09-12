/*
 * kexec — диспетчер имён внутри приложения.
 *
 * Каждая команда лежит в nativeLibraryDir как `lib<имя>.so` (оттуда разрешён exec:
 * W^X запрещает запуск файлов из домашнего каталога приложения при targetSdk >= 29),
 * а в PATH попадают симлинки `bin/<имя>` -> этот файл. Имя команды читается из
 * basename(argv[0]), поэтому `sh`, `node`, `ls` работают для дочерних процессов node
 * так же, как для нас.
 *
 * Дальше всё делает glibc-загрузчик: PT_INTERP у ubuntu-ELF указывает на
 * /lib/ld-linux-aarch64.so.1, которого в Android нет, поэтому исполняем
 *   libldr.so --library-path <runtime>/lib  lib<имя>.so  аргументы…
 *
 * Собная реализация basename/dirname: у libc-вариантов есть статические буферы,
 * и два вызова подряд затирают результат друг друга.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define PATHMAX 4096

static char *xstrdup(const char *s)
{
    char *p = strdup(s);
    if (p == NULL) {
        fprintf(stderr, "kexec: нет памяти\n");
        exit(127);
    }
    return p;
}

static const char *base_of(const char *p)
{
    const char *slash = strrchr(p, '/');
    return (slash != NULL) ? slash + 1 : p;
}

int main(int argc, char **argv)
{
    const char *root = getenv("AGENT_PHONE_RUNTIME");
    if (root == NULL || root[0] == '\0') {
        fprintf(stderr, "kexec: не задан AGENT_PHONE_RUNTIME\n");
        return 127;
    }

    char self[PATHMAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n < 0) {
        perror("kexec: readlink /proc/self/exe");
        return 127;
    }
    self[n] = '\0';

    /* Каталог этого файла == nativeLibraryDir: от него берём libldr.so и lib<cmd>.so. */
    char *slash = strrchr(self, '/');
    if (slash != NULL) {
        *slash = '\0';
    }
    const char *ndir = self;

    const char *name = base_of(argv[0]);
    if (strcmp(name, "kexec") == 0 || strcmp(name, "libkexec.so") == 0) {
        fprintf(stderr, "kexec: запускать через симлинк в bin/ (sh, node, ls, …)\n");
        return 2;
    }

    /* Помощники git (git-status, git-remote-https) живут в одном ELF: префикс снимается,
     * а оставшаяся часть уходит первым аргументом — ровно как ожидает git. */
    char target[256];
    const char *subcommand = NULL;
    if (strncmp(name, "git-", 4) == 0) {
        snprintf(target, sizeof(target), "%s", "git");
        subcommand = name + 4;
    } else {
        snprintf(target, sizeof(target), "%s", name);
    }

    char ldr[PATHMAX];
    char wanted[PATHMAX];
    char libs[PATHMAX];
    snprintf(ldr, sizeof(ldr), "%s/libldr.so", ndir);
    snprintf(wanted, sizeof(wanted), "%s/lib%s.so", ndir, target);
    snprintf(libs, sizeof(libs), "%s/lib", root);

    char **a = calloc((size_t)argc + 8, sizeof(char *));
    if (a == NULL) {
        fprintf(stderr, "kexec: нет памяти\n");
        return 127;
    }
    int i = 0;
    a[i++] = xstrdup(ldr);
    a[i++] = xstrdup("--library-path");
    a[i++] = xstrdup(libs);
    a[i++] = xstrdup(wanted);
    if (subcommand != NULL) {
        a[i++] = xstrdup(subcommand);
    }
    for (int j = 1; j < argc && i < argc + 6; j++) {
        a[i++] = argv[j];
    }
    a[i] = NULL;

    execv(ldr, a);
    fprintf(stderr, "kexec: не удалось запустить %s -> %s: ", name, wanted);
    perror("");
    return 127;
}
