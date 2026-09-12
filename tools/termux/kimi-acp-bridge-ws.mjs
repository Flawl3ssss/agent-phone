#!/usr/bin/env node
/**
 * kimi-acp-bridge — WebSocket-мост между приложением Agent Phone и `kimi acp`
 * внутри Termux.
 *
 * Зачем он вообще нужен: Android запрещает одному приложению запускать бинарь из
 * песочницы другого. У Termux свой uid, его node и его glibc-зависимости для нашего
 * процесса недостижимы. Остаётся единственный канал — loopback-сокет: Termux держит
 * сервер, приложение подключается к 127.0.0.1 как WebSocket-клиент.
 *
 *   Agent Phone (OkHttp WS)  ── ws://127.0.0.1:8712/acp ──>  этот мост  ── stdio ──>  kimi acp
 *
 * ГЛАВНЫЙ КОНТРАКТ, который здесь соблюдается: одно JSON-сообщение = один WebSocket-кадр.
 * AcpTransport на стороне приложения читает поток ПОСТРОЧНО (readLine). Если прислать
 * два сообщения в одном кадре — второе потеряется до следующего события; если разрезать
 * одно сообщение по кадрам — приложение упадёт на JsonParser и молча таймаутит запрос.
 * Поэтому тут: буфер по \n с дочерней стороны, и по одному socket.send() на строку.
 *
 * Использование:
 *   node kimi-acp-bridge.mjs                 # слушать :8712
 *   node kimi-acp-bridge.mjs --selftest      # проверить фрейминг на моке, Kimi не нужен
 *
 * Переменные окружения:
 *   BRIDGE_HOST   интерфейс (по умолчанию 127.0.0.1 — наружу не торчим)
 *   BRIDGE_PORT   порт (8712)
 *   BRIDGE_PATH   путьhandshake, по умолчанию /acp ('*' = любой)
 *   KIMI_ACP_CMD  команда агента (по умолчанию kimi из локального node_modules)
 *   MAX_CLIENTS   сколько одновременных соединений терпеть (1)
 *   BRIDGE_DEBUG  1 — печатать каждый кадр
 */

import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const MOCK = path.join(HERE, "..", "acp-mock-agent.mjs");

const HOST = process.env.BRIDGE_HOST ?? "127.0.0.1";
const PORT = Number(process.env.BRIDGE_PORT ?? 8712);
const PATH_FILTER = process.env.BRIDGE_PATH ?? "/acp";
const DEBUG = process.env.BRIDGE_DEBUG === "1";
const MAX_CLIENTS = Number(process.env.MAX_CLIENTS ?? 1);
const DEFAULT_CMD = path.join(
    process.env.HOME ?? "/root", "agent-phone", "node_modules", ".bin", "kimi",
);
const AGENT_CMD =
    process.env.KIMI_ACP_CMD ?? `${DEFAULT_CMD} --print-config plain acp`;

const log = (...a) => console.log(`[bridge ${new Date().toISOString().slice(11, 19)}]`, ...a);
const err = (...a) => console.error(`[bridge ${new Date().toISOString().slice(11, 19)}]`, ...a);

/** Разбивает команду на argv. Кавычки не поддерживает — это ограничение: путь с
 *  пробелами надо задавать через KIMI_ACP_CMD без аргументов или симлинком. */
function argv(cmd) {
    const parts = cmd.trim().split(/\s+/);
    return [parts[0], parts.slice(1)];
}

/**
 * Оборачивает один WebSocket в жизненный цикл одного процесса агента.
 * @param {import("ws").WebSocket} socket
 * @param {"ws" | "loopback"} kind тип транспорта (для selftest используется in-process пары)
 */
function attach(socket, child) {
    let agent = child;
    let closed = false;
    let outBuf = "";
    const stderrTail = [];

    const noteErr = (line) => {
        stderrTail.push(line);
        if (stderrTail.length > 40) stderrTail.shift();
    };

    const shutdown = (why) => {
        if (closed) return;
        closed = true;
        log(`соединение закрыто: ${why}`);
        try {
            socket.close(1012, "bridge restart");
        } catch { /* уже закрыт */ }
        if (agent && agent.exitCode === null && !agent.killed) {
            agent.kill("SIGTERM");
            // Если агент не отреагировал на SIGTERM за 3 с — добираем SIGKILL.
            // Без этого зависший node-процесс остаётся навсегда: мы внутри Termux,
            // nobody придёт и не уберёт.
            setTimeout(() => {
                if (agent.exitCode === null) {
                    try { agent.kill("SIGKILL"); } catch { /* уже мёртв */ }
                }
            }, 3000).unref();
        }
    };

    // ── агент → приложение: строго по одному сообщению на кадр ───────────────
    agent.stdout.setEncoding("utf8");
    agent.stdout.on("data", (chunk) => {
        outBuf += chunk;
        let nl;
        while ((nl = outBuf.indexOf("\n")) >= 0) {
            const line = outBuf.slice(0, nl).replace(/\r$/, "").trim();
            outBuf = outBuf.slice(nl + 1);
            if (!line) continue;
            if (socket.readyState !== 1 /* OPEN */) {
                // Буфер растёт, пока клиент не читает, — режем его, а не кормим OOM.
                if (outBuf.length > 8 * 1024 * 1024) { outBuf = ""; shutdown("клиент закрыт, буфер переполнен"); }
                return;
            }
            if (DEBUG) log(`→ app  ${line.slice(0, 140)}`);
            socket.send(line, (e) => { if (e) err("не отправил кадр клиенту:", e.message); });
        }
    });

    agent.stderr.setEncoding("utf8");
    agent.stderr.on("data", (c) => {
        for (const line of c.split("\n")) {
            if (!line.trim()) continue;
            noteErr(line);
            console.error(`  [kimi] ${line}`);
        }
    });

    agent.on("exit", (code, signal) => {
        // Незавершённая строка в буфере к этому моменту — мусор, выбрасываем.
        if (outBuf.trim() && socket.readyState === 1) {
            socket.send(outBuf.trim(), () => {});
        }
        outBuf = "";
        err(`агент завершился (code=${code} signal=${signal})`);
        if (code !== 0) err("хвост stderr:", stderrTail.slice(-8).join(" | "));
        shutdown(`агент вышел: code=${code} signal=${signal}`);
    });
    agent.on("error", (e) => err("не запустил агента:", e.message));

    // ── приложение → агент ───────────────────────────────────────────────────
    socket.on("message", (data) => {
        const text = data.toString("utf8").trim();
        if (!text) return;
        if (agent.exitCode !== null || agent.killed) {
            err("кадр от клиента после смерти агента — игнорирую");
            return;
        }
        if (DEBUG) log(`→ kimi ${text.slice(0, 140)}`);
        // Один кадр = одна команда: добавляем перевод строки, которым NDJSON разделён.
        agent.stdin.write(`${text}\n`, (e) => {
            if (e) err("не передал команду агенту (битый пайп?):", e.message);
        });
    });

    socket.on("close", () => shutdown("клиент отключился"));
    socket.on("error", (e) => { err("сокет:", e.message); shutdown("ошибка сокета"); });
}

export async function startServer() {
    // `ws` — единственная внешняя зависимость моста; ставится в Termux вместе с kimi.
    const { WebSocketServer } = await import("ws");
    let live = 0;
    const wss = new WebSocketServer({
        host: HOST,
        port: PORT,
        maxPayload: 8 * 1024 * 1024,
        // Пульс нужен, чтобы замечать полуоткрытые соединения: на Android сокет может
        // пережить убийство процесса и остаться «живым» с нашей стороны.
        perMessageDeflate: false,
    });

    wss.on("connection", (socket, req) => {
        if (PATH_FILTER !== "*" && req.url && !req.url.startsWith(PATH_FILTER)) {
            log(`отклонён handshake: путь ${req.url} (ожидан ${PATH_FILTER})`);
            socket.close(1008, "bad path");
            return;
        }
        if (live >= MAX_CLIENTS) {
            log("занято: уже есть активное соединение — новому отказ");
            socket.close(1013, "bridge busy");
            return;
        }
        live += 1;
        const [exe, args] = argv(AGENT_CMD);
        log(`клиент подключился; запускаю: ${exe} ${args.join(" ")}`);
        let child;
        try {
            child = spawn(exe, args, { stdio: ["pipe", "pipe", "pipe"], env: process.env });
        } catch (e) {
            err("spawn не удался:", e.message);
            live -= 1;
            socket.close(1011, "agent spawn failed");
            return;
        }
        child.on("exit", () => { live -= 1; });
        attach(socket, child);
    });

    // keepalive раз в 20 с: мёртвого клиента видно по отсутствию pong
    const pingTimer = setInterval(() => {
        for (const s of wss.clients) {
            if (s.isAlive === false) { s.terminate(); continue; }
            s.isAlive = false;
            try { s.ping(); } catch { /* закрыт */ }
        }
    }, 20000);
    pingTimer.unref();
    wss.on("connection", (s) => {
        s.isAlive = true;
        s.on("pong", () => { s.isAlive = true; });
    });
    wss.on("close", () => clearInterval(pingTimer));
    return wss;
}

// ───────────────────────── selftest: проверка фрейминга без Kimi ─────────────────────────
//
// Здесь проверяется ровно то, на чём обычно горят мосты: склейка сообщений.
// Шлём в агента команду, на которую он отвечает НЕСКОЛЬКИМИ сообщениями, и сверяем,
// что на выходе из моста их столько же и каждый кадр — самостоятельный JSON.
async function selftest() {
    const { WebSocketServer, default: WS } = await import("ws");
    const checks = [];
    const expect = (name, cond, detail = "") => {
        checks.push({ name, ok: !!cond, detail });
        console.log(`${cond ? "  ok  " : " FAIL "} ${name}${cond ? "" : ` — ${detail}`}`);
    };

    const server = new WebSocketServer({ host: "127.0.0.1", port: 0 });
    await new Promise((r) => server.once("listening", r));
    const port = server.address().port;
    let spawned = null;
    server.on("connection", (socket) => {
        const [exe, args] = argv(`node ${MOCK}`);
        spawned = spawn(exe, args, { stdio: ["pipe", "pipe", "pipe"] });
        attach(socket, spawned);
    });

    const client = new WS(`ws://127.0.0.1:${port}/acp`);
    const frames = [];
    const waiters = [];
    // Мини-клиент: отвечает на ЗАПРОСЫ агента, пришедшие сквозь мост. Без этого ход
    // мока встаёт на первом же request_permission и таймаут выглядит как «мост виснет».
    // Это же отдельное и важное покрытие: агент→клиент запрос + ответ в обе стороны.
    const ANSWERS = {
        "session/request_permission": { outcome: { outcome: "selected", optionId: "allow_once" } },
        "fs/read_text_file": { content: "строка 1\nстрока 2\n" },
        "fs/write_text_file": {},
        "terminal/create": { terminalId: "t-selftest" },
        "terminal/output": { output: "v22.23.2\n", truncated: false },
        "terminal/wait_for_exit": { exitCode: 0, signal: null },
        "terminal/kill": {},
        "terminal/release": {},
        "terminal/wait_for_terminal": { exitCode: 0, signal: null },
    };
    const answered = new Set();
    client.on("message", (d) => {
        const s = d.toString("utf8");
        frames.push(s);
        let o = null;
        try { o = JSON.parse(s); } catch { /* не JSON — просто не на что отвечать */ }
        if (o && o.method && o.id !== undefined && o.id !== null) {
            const ans = o.method in ANSWERS ? ANSWERS[o.method] : {};
            answered.add(o.method);
            client.send(JSON.stringify({ jsonrpc: "2.0", id: o.id, result: ans }));
        }
        while (waiters.length && waiters[0].want(s)) { waiters.shift().res(s); }
    });
    // Таймаут обязан назвать причину, а не просто сработать: без дампа полученных
    // кадров отладка моста превращается в угадывание.
    const waitFor = (pred, ms = 15000, what = "сообщение") =>
        new Promise((res, rej) => {
            const hit = frames.find(pred);
            if (hit) return res(hit);
            const t = setTimeout(() => {
                const dumped = frames.length
                    ? frames.map((f) => `      · ${f.slice(0, 160)}`).join("\n")
                    : "      (ни одного кадра не дошло)";
                rej(new Error(`таймаут ожидания: ${what}. Получено кадров: ${frames.length}\n${dumped}`));
            }, ms);
            waiters.push({ want: pred, res: (v) => { clearTimeout(t); res(v); } });
        });

    await new Promise((r, j) => { client.once("open", r); client.once("error", j); });
    log("клиент подключился, гоняю трафик");

    const send = (obj) => client.send(JSON.stringify(obj));

    // 1. initialize → один ответ
    send({ jsonrpc: "2.0", id: 0, method: "initialize", params: { protocolVersion: 1, clientCapabilities: {} } });
    const init = await waitFor((s) => s.includes('"id":0'), 15000, "initialize");
    expect("initialize пришёл одним кадром", (() => { try { JSON.parse(init); return true; } catch { return false; } })(), init.slice(0, 80));
    expect("кадр — один объект, не склейка", !init.includes("}\n{") && !init.trim().startsWith("{["), init.slice(0, 60));

    // 2. newSession → session/new
    // ДВЕ грабли, на которых строятся такие тесты:
    //   1) mcpServers — обязательное поле NewSessionRequest (схема ACP валидирует на лету);
    //   2) поле id ОБЯЗАТЕЛЬНО: сообщение JSON-RPC без id — это УВЕДОМЛЕНИЕ, и агент
    //      на него не отвечает. Проверено на себе: полчаса поиска «моста, который молчит».
    send({ jsonrpc: "2.0", id: 2, method: "session/new", params: { cwd: process.cwd(), mcpServers: [] } });
    const sn = await waitFor((s) => s.includes('"id":2'), 10000, "ответ на session/new (id:2)");
    expect("session/new получил ответ одним валидным кадром", (() => { try { const o = JSON.parse(sn); return o.id === 2 && !!o.result; } catch { return false; } })(), sn.slice(0, 100));
    // Направление «агент → клиент без запроса» здесь покрывают session/update-уведомления
    // внутри хода (шаг 3): мок, в отличие от настоящего Kimi, других пушей не шлёт.
    expect("на ответе session/new нет мусора после JSON", sn.trim() === JSON.stringify(JSON.parse(sn)), sn.slice(-60));

    // 3. prompt → серия кадров (вот здесь ломается фрейминг)
    // sessionId берём ИЗ ОТВЕТА session/new: захардкоженный id даёт «session not found»,
    // а предикат по одному лишь `"id":1` на такой ошибке проходит — ловушка, в которую
    // я уже успела попасть. Ищем именно stopReason, т.е. настоящий результат хода.
    const sessionId = (() => { try { return JSON.parse(sn).result.sessionId; } catch { return null; } })();
    expect("session/new вернул sessionId", !!sessionId, sn.slice(0, 120));
    const before = frames.length;
    send({ jsonrpc: "2.0", id: 1, method: "session/prompt", params: { sessionId, prompt: [{ type: "text", text: "тест моста" }] } });
    const done = await waitFor((s) => s.includes('"id":1') && s.includes("stopReason"), 60000, "результат prompt со stopReason");
    expect("prompt завершился result, а не error", (() => { try { const o = JSON.parse(done); return o.result && !o.error; } catch { return false; } })(), done.slice(0, 140));
    const turn = frames.slice(before);
    expect("на один prompt пришло несколько кадров", turn.length >= 4, `пришло ${turn.length}`);
    const allValid = turn.every((f) => { try { JSON.parse(f); return true; } catch { return false; } });
    expect("каждый кадр — самостоятельный JSON (ни склеек, ни обрезков)", allValid, turn.find((f) => { try { JSON.parse(f); return !0; } catch { return false; } })?.slice(0, 120) ?? "");
    const pushN = turn.filter((f) => f.includes('"method":"session/update"')).length;
    expect("уведомления агента (session/update) прошли сквозь мост", pushN >= 3, `дошло ${pushN} из ${turn.length} кадров`);
    expect("запросы агента к клиенту дошли и были обслужены", answered.has("session/request_permission"), [...answered].join(","));
    const ids = turn.map((f) => { try { const o = JSON.parse(f); return o.id ?? o.method ?? "?"; } catch { return "!"; } });
    expect("последний кадр — ответ на prompt (id:1)", ids[ids.length - 1] === 1, ids.join(","));
    expect("ни одно сообщение не потеряно и не разрезано", !turn.some((f) => f.includes("\n")), JSON.stringify(turn[0]?.slice(-20)));

    // 4. смерть агента → закрытый сокет (приложение должно узнать, а не висеть)
    client.close();
    const gone = await new Promise((res) => {
        const t = setTimeout(() => res(false), 8000);
        const iv = setInterval(() => {
            if (spawned && (spawned.exitCode !== null || spawned.signalCode)) { clearTimeout(t); clearInterval(iv); res(true); }
        }, 200);
    });
    expect("после отключения клиента процесс агента убит", gone, "агент пережил разрыв — утечка процесса");

    server.close();
    await new Promise((r) => setTimeout(r, 500));
    const failed = checks.filter((c) => !c.ok);
    console.log(`\nselftest: ${checks.length - failed.length}/${checks.length} пройдено`);
    process.exit(failed.length ? 1 : 0);
}

if (process.argv[2] === "--selftest") {
    selftest().catch((e) => { err("selftest упал:", e.message || e.stack); process.exit(1); });
} else {
    startServer().then(() => {
        log(`слушаю ws://${HOST}:${PORT}${PATH_FILTER === "*" ? "" : PATH_FILTER}`);
        log(`агент: ${AGENT_CMD}`);
        log(`лимит клиентов: ${MAX_CLIENTS}`);
        if (HOST !== "127.0.0.1") err("ВНИМАНИЕ: слушаю не loopback — ACP не шифруется и не имеет аутентификации");
    }).catch((e) => {
        if (String(e.code) === "EADDRINUSE") {
            err(`порт ${PORT} занят: ${e.message}`);
            err("поднят другой экземпляр моста? Проверьте: ss -tlnp | head");
        } else {
            err("не смог запуститься:", e.stack);
        }
        process.exit(1);
    });
}
