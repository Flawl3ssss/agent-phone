#!/usr/bin/env node
/**
 * kimi-acp-bridge — мост между приложением Agent Phone и `kimi acp` внутри Termux.
 *
 * ПОЧЕМУ ВООБЩЕ НУЖЕН МОСТ. Android запрещает приложению А запускать бинарь из
 * песочницы приложения Б: у Termux свой uid, свой mount namespace и свои библиотеки,
 * а ProcessBuilder нашего процесса их не видит. Значит «kimi acp из Termux» для
 * приложения недостижим напрямую. Единственный разрешённый канал между двумя
 * приложениями на одном устройстве — loopback-сокет: Termux держит сервер, приложение
 * подключается к 127.0.0.1 как клиент. Разрешение INTERNET для этого уже в манифесте.
 *
 *   Agent Phone (SocketTransport) ── tcp://127.0.0.1:8712 ──> этот мост ── stdio ──> kimi acp
 *
 * ПОЧЕМУ ГОЛЫЙ TCP, А НЕ WEBSOCKET. Транспорт приложения (AcpTransport) построен на
 * BufferedReader/BufferedWriter и NDJSON, поэтому сокет вставляется в него напрямую и
 * не нужна ни OkHttp в APK, ни библиотека ws в Termux. WS-вариант лежит рядом
 * (kimi-acp-bridge-ws.mjs) — он удобен, когда надо посмотреть трафик из браузера.
 *
 * КОНТРАКТ ФРЕЙМИНГА: одна JSON-RPC запись = одна строка, terminator \n.
 * С обеих сторон буфер по \n: TCP не сохраняет границы записей, и склейка двух
 * сообщений в один chunk или разрез одного пополам — типичная причина «клиент молчит
 * до таймаута». Ничего кроме NDJSON в сокет не пишется.
 *
 * Использование:
 *   node kimi-acp-bridge.mjs                  # слушать 127.0.0.1:8712
 *   node kimi-acp-bridge.mjs --selftest       # проверить фрейминг на моке (Kimi не нужен)
 *
 * Переменные окружения:
 *   BRIDGE_HOST   интерфейс; по умолчанию 127.0.0.1 (наружу не торчим: ACP без аутентификации)
 *   BRIDGE_PORT   порт (8712)
 *   KIMI_ACP_CMD  команда агента
 *   MAX_CLIENTS   одновременных соединений (4)
 *   BRIDGE_DEBUG  1 — печатать каждый кадр
 */

import { spawn } from "node:child_process";
import net from "node:net";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const MOCK = path.join(HERE, "..", "acp-mock-agent.mjs");

const HOST = process.env.BRIDGE_HOST ?? "127.0.0.1";
const PORT = Number(process.env.BRIDGE_PORT ?? 8712);
const DEBUG = process.env.BRIDGE_DEBUG === "1";
const MAX_CLIENTS = Number(process.env.MAX_CLIENTS ?? 4);
const DEFAULT_CMD = path.join(process.env.HOME ?? "/root", "agent-phone", "node_modules", ".bin", "kimi");
// Канон запуска — `kimi acp` (см. BLUEPRINT). Никаких «улучшайшеров» вроде
// --print-config: несуществующий флаг убьёт ребёнка на старте, а приложение
// увидит просто закрытый сокет вместо внятной ошибки.
const AGENT_CMD = process.env.KIMI_ACP_CMD ?? `${DEFAULT_CMD} acp`;

const stamp = () => new Date().toISOString().slice(11, 19);
const log = (...a) => console.log(`[bridge ${stamp()}]`, ...a);
const err = (...a) => console.error(`[bridge ${stamp()}]`, ...a);

/** argv из строки команды. Кавычки не разбираются — это ограничение, а не баг: путь
 *  с пробелами проще спрятать в симлинк, чем тащить парсер кавычек в 20 строк кода. */
function argv(cmd) {
    const parts = cmd.trim().split(/\s+/);
    return [parts[0], parts.slice(1)];
}

/**
 * Связывает одно клиентское соединение с одним процессом агента.
 * Возвращает объект с .child — чтобы server мог учесть его в лимите и в уборке.
 */
function attach(client, cmdLine) {
    const [exe, args] = argv(cmdLine);
    let child;
    try {
        child = spawn(exe, args, { stdio: ["pipe", "pipe", "pipe"], env: process.env });
    } catch (e) {
        err("spawn не удался:", e.message);
        client.end();
        return { child: null };
    }

    let closing = false;
    let outBuf = "";
    const stderrTail = [];

    const shutdown = (why) => {
        if (closing) return;
        closing = true;
        log(`соединение закрыто: ${why}`);
        try { client.end(); } catch { /* уже */ }
        if (child.exitCode === null && child.signalCode === null) {
            child.kill("SIGTERM");
            // Без добирающего SIGKILL зависший node-процесс переживёт нас навсегда:
            // в Termux за нами не придёт ни init, ни launchd.
            setTimeout(() => {
                if (child.exitCode === null && child.signalCode === null) {
                    try { child.kill("SIGKILL"); } catch { /* уже */ }
                }
            }, 3000).unref();
        }
    };

    // ── агент → приложение: режем поток строго по \n, неполный хвост держим ────
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
        outBuf += chunk;
        // Кап на размер буфера: клиент пропал из сети, агент генерирует вывод —
        // без этого лимита это тихий OOM на телефоне.
        if (outBuf.length > 32 * 1024 * 1024) { err("буфер переполнен — рву соединение"); return shutdown("переполнение буфера"); }
        let nl;
        while ((nl = outBuf.indexOf("\n")) >= 0) {
            const line = outBuf.slice(0, nl).replace(/\r$/, "").trim();
            outBuf = outBuf.slice(nl + 1);
            if (!line) continue;
            if (DEBUG) log(`→ app  ${line.slice(0, 140)}`);
            if (!client.write(`${line}\n`)) {
                err("клиент не читает (backpressure) — продолжаем, TCP буферизует");
            }
        }
    });

    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (c) => {
        for (const line of c.split("\n")) {
            if (!line.trim()) continue;
            stderrTail.push(line);
            if (stderrTail.length > 60) stderrTail.shift();
            process.stderr.write(`  [kimi] ${line}\n`);
        }
    });

    child.on("exit", (code, signal) => {
        // Хвост без завершающего \n на выходе агента — скорее всего обрезанное сообщение.
        // Отдавать его нельзя (клиент не распарсит), но молчать хуже: пишём в лог.
        if (outBuf.trim()) err(`обрезанный хвост на stdout агента (${outBuf.trim().length} Б) — отброшен`);
        outBuf = "";
        err(`агент завершился (code=${code} signal=${signal})`);
        if (code !== 0 && stderrTail.length) err("хвост stderr:", stderrTail.slice(-6).join(" | "));
        shutdown(`агент вышел: code=${code} signal=${signal}`);
    });
    child.on("error", (e) => err("ошибка процесса агента:", e.message));

    // ── приложение → агент ─────────────────────────────────────────────────────
    let inBuf = "";
    client.setEncoding("utf8");
    client.on("data", (chunk) => {
        inBuf += chunk;
        let nl;
        while ((nl = inBuf.indexOf("\n")) >= 0) {
            const line = inBuf.slice(0, nl).replace(/\r$/, "").trim();
            inBuf = inBuf.slice(nl + 1);
            if (!line) continue;
            if (child.exitCode !== null || child.signalCode !== null) {
                err("кадр от клиента после смерти агента — игнорирую");
                continue;
            }
            if (DEBUG) log(`→ kimi ${line.slice(0, 140)}`);
            child.stdin.write(`${line}\n`, (e) => {
                if (e) err("не передал команду агенту (битый пайп?):", e.message);
            });
        }
    });

    client.on("close", () => shutdown("клиент отключился"));
    client.on("error", (e) => { err("сокет:", e.message); shutdown("ошибка сокета"); });
    return { child };
}

export function startServer({ host = HOST, port = PORT, cmd = AGENT_CMD, quiet = false } = {}) {
    const conns = new Set();
    const server = net.createServer((client) => {
        if (conns.size >= MAX_CLIENTS) {
            if (!quiet) log(`занято: ${conns.size} активных — новому отказ`);
            client.end();
            return;
        }
        if (!quiet) log(`клиент ${client.remoteAddress}:${client.remotePort}`);
        // Задержка Негольда нам враг: стриминг идёт мелкими сообщениями, и без
        // NODELAY каждое сообщение дожидается ack (~40 мс на накопление).
        client.setNoDelay(true);
        const { child } = attach(client, cmd);
        if (child) {
            conns.add(child);
            child.on("exit", () => conns.delete(child));
        }
    });
    server.on("error", (e) => {
        if (e.code === "EADDRINUSE") {
            err(`порт ${port} занят: другой экземпляр моста? Проверьте: ss -tlnp 2>/dev/null | grep ${port}`);
        } else {
            err("сервер:", e.message);
        }
    });
    server.listen(port, host, () => {
        if (quiet) return;
        log(`слушаю ${host}:${port}`);
        log(`агент: ${cmd}`);
        log(`лимит клиентов: ${MAX_CLIENTS}`);
        if (host !== "127.0.0.1" && host !== "localhost") {
            err("ВНИМАНИЕ: слушаю не loopback. ACP не шифруется и не имеет аутентификации —");
            err("любой в этой сети сможет давать команды агенту и читать ваши файлы.");
        }
    });
    return server;
}

// ───────────────────────────── selftest ─────────────────────────────
//
// Проверяется то, на чём горят все мосты подобного рода: границы сообщений.
// Плюс отдельно — что смерть клиента убивает процесс агента (иначе на телефоне
// копятся сироты, съедая RAM, которой и так 512 МБ).
async function selftest() {
    const checks = [];
    const expect = (name, cond, detail = "") => {
        checks.push({ name, ok: !!cond });
        console.log(`${cond ? "  ok  " : " FAIL "} ${name}${cond ? "" : ` — ${detail}`}`);
    };

    const frames = [];
    const waiters = [];
    // Таймаут обязан назвать причину и показать, что реально приходило: иначе отладка
    // моста превращается в угадывание (я на этом уже потеряла полчаса).
    const waitFor = (pred, ms, what) => new Promise((res, rej) => {
        const hit = frames.find(pred);
        if (hit) return res(hit);
        const t = setTimeout(() => {
            const dump = frames.length ? frames.map((f) => `      · ${f.slice(0, 150)}`).join("\n") : "      (ни одного кадра)";
            rej(new Error(`таймаут ожидания: ${what}. Кадров получено: ${frames.length}\n${dump}`));
        }, ms);
        waiters.push({ want: pred, res: (v) => { clearTimeout(t); res(v); } });
    });

    // Мини-клиент: отвечает на запросы АГЕНТА. Без этого ход встаёт на первом же
    // request_permission, и «молчание» выглядит как поломка моста.
    const ANSWERS = {
        "session/request_permission": { outcome: { outcome: "selected", optionId: "allow_once" } },
        "fs/read_text_file": { content: "строка 1\nстрока 2\n" },
        "fs/write_text_file": {},
        "terminal/create": { terminalId: "t-selftest" },
        "terminal/output": { output: "v22.23.2\n", truncated: false },
        "terminal/wait_for_exit": { exitCode: 0, signal: null },
        "terminal/kill": {},
        "terminal/release": {},
    };
    const answered = new Set();
    let sock;

    const onData = (chunk) => {
        for (const line of chunk.toString("utf8").split("\n")) {
            const s = line.trim();
            if (!s) continue;
            frames.push(s);
            let o = null;
            try { o = JSON.parse(s); } catch { /* не наш кадр — не на что отвечать */ }
            if (o && o.method && o.id !== undefined && o.id !== null) {
                answered.add(o.method);
                sock.write(`${JSON.stringify({ jsonrpc: "2.0", id: o.id, result: o.method in ANSWERS ? ANSWERS[o.method] : {} })}\n`);
            }
            while (waiters.length && waiters[0].want(s)) waiters.shift().res(s);
        }
    };

    const server = startServer({ host: "127.0.0.1", port: 0, cmd: `node ${MOCK}`, quiet: true });
    await new Promise((r) => server.once("listening", r));
    const port = server.address().port;
    sock = net.connect(port, "127.0.0.1");
    await new Promise((r, j) => { sock.once("connect", r); sock.once("error", j); });
    sock.on("data", onData);
    log(`подключился к мосту на :${port}, гоняю трафик`);

    const send = (o) => sock.write(`${JSON.stringify(o)}\n`);

    send({ jsonrpc: "2.0", id: 0, method: "initialize", params: { protocolVersion: 1, clientCapabilities: {} } });
    const init = await waitFor((s) => s.includes('"id":0'), 20000, "initialize");
    expect("initialize пришёл одним кадром", (() => { try { return JSON.parse(init).id === 0; } catch { return false; } })(), init.slice(0, 90));
    expect("в кадре ровно один объект (без склейки через \\n)", !init.includes("\n"), JSON.stringify(init.slice(-40)));

    // ДВЕ грабли, проверенные на себе:
    //   1) поле id обязательно — JSON-RPC без id это УВЕДОМЛЕНИЕ, агент на него не отвечает;
    //   2) mcpServers обязательно по схеме ACP — иначе валидация отбивает запрос.
    send({ jsonrpc: "2.0", id: 2, method: "session/new", params: { cwd: process.cwd(), mcpServers: [] } });
    const sn = await waitFor((s) => s.includes('"id":2'), 20000, "ответ на session/new (id:2)");
    expect("session/new отвечен валидным JSON", (() => { try { return JSON.parse(sn).id === 2; } catch { return false; } })(), sn.slice(0, 90));
    const sessionId = (() => { try { return JSON.parse(sn).result?.sessionId; } catch { return null; } })();
    expect("session/new вернул sessionId", !!sessionId, sn.slice(0, 120));

    const before = frames.length;
    send({ jsonrpc: "2.0", id: 1, method: "session/prompt", params: { sessionId, prompt: [{ type: "text", text: "тест моста" }] } });
    // Ждём именно stopReason: предикат по одному `"id":1` совпадает и с ошибкой —
    // так тест «зеленеет», ничего на самом деле не проверяя.
    const done = await waitFor((s) => s.includes('"id":1') && s.includes("stopReason"), 90000, "результат prompt со stopReason");
    expect("prompt завершился result, а не error", (() => { try { const o = JSON.parse(done); return !!o.result && !o.error; } catch { return false; } })(), done.slice(0, 140));

    const turn = frames.slice(before);
    expect("на один prompt пришло несколько кадров", turn.length >= 4, `пришло ${turn.length}`);
    const allValid = turn.every((f) => { try { JSON.parse(f); return true; } catch { return false; } });
    expect("каждый кадр — самостоятельный JSON", allValid, turn.find((f) => { try { return false; } catch { return true; } })?.slice(0, 120) ?? "");
    const pushN = turn.filter((f) => f.includes('"method":"session/update"')).length;
    expect("уведомления агента (session/update) прошли сквозь мост", pushN >= 3, `дошло ${pushN} из ${turn.length}`);
    expect("запросы агента к клиенту дошли и были обслужены", answered.has("session/request_permission"), [...answered].join(","));
    expect("поток не разрезан и не склеен (ни одного \\n внутри кадра)", !turn.some((f) => f.includes("\n")), "");
    const last = (() => { try { return JSON.parse(turn[turn.length - 1]); } catch { return {}; } })();
    expect("последний кадр — ответ на prompt", last.id === 1, `id=${last.id} method=${last.method}`);

    // Смерть клиента обязана убивать агента.
    sock.end();
    await new Promise((r) => setTimeout(r, 1500));
    const survivors = await new Promise((res) => {
        // Считаем живые node-процессы мока: если после разрыва их больше, чем было до
        // теста, — мост оставляет сирот.
        const p = spawn("sh", ["-c", "ps -o pid=,args= 2>/dev/null | grep acp-mock-agent | grep -v grep | wc -l"]);
        let o = "";
        p.stdout.on("data", (d) => { o += d; });
        p.on("exit", () => res(Number(o.trim() || "0")));
    });
    expect("после отключения клиента процесс агента убит (нет сирот)", survivors === 0, `живых мок-процессов: ${survivors}`);

    server.close();
    await new Promise((r) => setTimeout(r, 300));
    const failed = checks.filter((c) => !c.ok);
    console.log(`\nselftest: ${checks.length - failed.length}/${checks.length} пройдено`);
    process.exit(failed.length ? 1 : 0);
}

if (process.argv[2] === "--selftest") {
    selftest().catch((e) => { err("selftest упал:", e.message || e.stack); process.exit(1); });
} else {
    startServer();
    const bye = () => { log("останавливаюсь"); process.exit(0); };
    process.on("SIGINT", bye);
    process.on("SIGTERM", bye);
}
