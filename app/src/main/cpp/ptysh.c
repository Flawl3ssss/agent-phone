/*
 * ptysh — насос псевдо-терминала между stdin/stdout процесса и slave-концом PTY.
 *
 * Сделан отдельной программой, а не JNI-библиотекой, намеренно: так он наследует
 * окружение, которое собрала Kotlin-сторона (PATH/HOME/TMPDIR рантайма), умирает
 * вместе с родителем и не требует передавать файловые дескрипторы через JNI.
 * Приложение общается с ним через обычные трубы.
 *
 *   ptysh <rows> <cols> <файл-размера> <программа> [аргументы…]
 *
 * Размер меняется по SIGWINCH: приложение пишет "cols rows" в файл и шлёт сигнал,
 * потому что stdin насоса — труба, а не tty, и вычитать размер из него нельзя.
 */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static volatile sig_atomic_t g_pending = 0;
static char g_size_path[512];

static void on_winch(int signo)
{
    (void)signo;
    g_pending = 1;
}

static void apply_size(int master, int rows, int cols)
{
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(master, TIOCSWINSZ, &ws);
}

/* перечитывает файл размера; возвращает 0, если прочитать не удалось */
static int read_size(int *rows, int *cols)
{
    FILE *f = fopen(g_size_path, "r");
    int r, c;
    if (f == NULL)
        return 0;
    if (fscanf(f, "%d %d", &r, &c) != 2 || r < 1 || c < 1) {
        fclose(f);
        return 0;
    }
    fclose(f);
    *rows = r;
    *cols = c;
    return 1;
}

int main(int argc, char **argv)
{
    int rows, cols, master, slave, pid;
    struct winsize ws;
    struct sigaction sa;

    if (argc < 5) {
        fprintf(stderr, "usage: ptysh <rows> <cols> <sizefile> <prog> [args...]\n");
        return 2;
    }
    rows = atoi(argv[1]);
    cols = atoi(argv[2]);
    snprintf(g_size_path, sizeof(g_size_path), "%s", argv[3]);
    if (rows < 1 || cols < 1) {
        fprintf(stderr, "ptysh: неверный размер %dx%d\n", rows, cols);
        return 2;
    }

    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    if (openpty(&master, &slave, NULL, NULL, &ws) != 0) {
        fprintf(stderr, "ptysh: openpty: %s\n", strerror(errno));
        return 1;
    }

    sa.sa_handler = on_winch;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART;
    sigaction(SIGWINCH, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    pid = fork();
    if (pid < 0) {
        fprintf(stderr, "ptysh: fork: %s\n", strerror(errno));
        return 1;
    }
    if (pid == 0) {
        /* потомок: slave становится его управляющим терминалом */
        close(master);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO)
            close(slave);
        execvp(argv[4], &argv[4]);
        fprintf(stderr, "ptysh: exec %s: %s\n", argv[4], strerror(errno));
        _exit(127);
    }

    close(slave);

    while (1) {
        struct pollfd fds[2];
        char buf[4096];
        ssize_t n;
        int status;

        if (g_pending) {
            g_pending = 0;
            if (read_size(&rows, &cols))
                apply_size(master, rows, cols);
        }

        fds[0].fd = STDIN_FILENO;
        fds[1].fd = master;
        fds[0].events = fds[1].events = POLLIN;
        if (poll(fds, 2, -1) < 0) {
            if (errno == EINTR)
                continue;
            break;
        }

        if (fds[0].revents & POLLIN) {
            n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n <= 0)
                break;
            if (write(master, buf, (size_t)n) < 0 && errno != EIO)
                break;
        }
        if (fds[1].revents & (POLLIN | POLLHUP)) {
            n = read(master, buf, sizeof(buf));
            if (n <= 0) {
                if (n < 0 && errno == EINTR)
                    continue;
                break;      /* сессия завершилась: закрываем насос */
            }
            if (write(STDOUT_FILENO, buf, (size_t)n) < 0)
                break;
        }
        if (waitpid(pid, &status, WNOHANG) == pid)
            break;
    }

    kill(pid, SIGHUP);
    close(master);
    return 0;
}
