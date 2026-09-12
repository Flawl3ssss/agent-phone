#!/usr/bin/env node
/**
 * acp-live-probe.mjs — часть гейта G3′: то же рукопожатие, что делает приложение,
 * но против **живого** `kimi acp`. Сравнивает полученную матрицу capabilities с
 * задокументированной (docs/en/reference/kimi-acp.md) и печатает расхождения.
 *
 * Использование:
 *   node tools/acp-live-probe.mjs                 # spawn("kimi", ["acp"])
 *   KIMI_BIN=/path/to/kimi node tools/acp-live-probe.mjs
 *   ACP_CWD=/workspace/proj node tools/acp-live-probe.mjs
 *   PROBE_PROMPT=0 node tools/acp-live-probe.mjs  # не слать реальный prompt (не тратит токены)
 *
 * Выход: 0 — расхождений нет; 1 — есть. Расхождения НЕ фатальны для клиента
 * (клиент обязан читать capabilities из ответа), но сигналят, что пора переснимать
 * docs/facts/upstream/kimi-acp.md (CHARTER §10).
 *
 * ВАЖНО по API SDK: у ClientApp/AgentApp можно вызвать connect()/connectWith()
 * РОВНО ОДИН раз на поток. Все вызовы — внутри одного connectWith.
 */

import { spawn } from "node:child_process";
import { Writable, Readable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk";

const KIMI = process.env.KIMI_BIN || "kimi";
const CWD = process.env.ACP_CWD || process.cwd();
const SEND_PROMPT = process.env.PROBE_PROMPT !== "0";
// В CI без подписки живой агент отвечает на initialize/capabilities, но не пускает
// в session/new. Разрешать это явно: иначе прогон «красный» по причине, которая ничего
// не проверяет, и настоящий регресс утонет в шуме.
const ALLOW_NO_AUTH = process.env.PROBE_ALLOW_NO_AUTH === "1";
let authBlocked = false;

// Ожидание по docs/en/reference/kimi-acp.md (снимок 2026-09-12, kimi-code 0.42.0)
const EXPECT = {
  protocolVersion: 1,
  "agentCapabilities.loadSession": true,
  "agentCapabilities.promptCapabilities.image": true,
  "agentCapabilities.promptCapabilities.audio": false,
  "agentCapabilities.promptCapabilities.embeddedContext": true,
  "agentCapabilities.mcpCapabilities.http": true,
  "agentCapabilities.mcpCapabilities.sse": true,
  "agentCapabilities.sessionCapabilities.list": "object",
  "agentCapabilities.sessionCapabilities.resume": "object",
  "agentCapabilities.sessionCapabilities.close": "object",
  "agentCapabilities.sessionCapabilities.delete": "object",
  "agentCapabilities.sessionCapabilities.fork": "object",
  "agentCapabilities.sessionCapabilities.additionalDirectories": "object",
  "agentCapabilities.auth.logout": "object",
};

const dot = (o, path) => path.split(".").reduce((a, k) => (a == null ? undefined : a[k]), o);
const results = [];
const record = (name, ok, detail = "") => results.push({ name, ok, detail });
const seen = [];

const proc = spawn(KIMI, ["acp"], { stdio: ["pipe", "pipe", "pipe"] });
proc.stderr.on("data", (b) => process.stderr.write(`[kimi:err] ${b}`));
proc.on("error", (e) => { console.error(`не запустил ${KIMI} acp: ${e.message}`); process.exit(1); });

// ndJsonStream(output, input): пишем в stdout-поток агента, читаем из его stdin
const stream = acp.ndJsonStream(Writable.toWeb(proc.stdin), Readable.toWeb(proc.stdout));

const clientApp = acp.client({ name: "agent-phone-probe", version: "0.1.0" })
  .onRequest(acp.methods.client.session.requestPermission, (c) => {
    seen.push("request_permission:" + (c.params.toolCall?.title ?? "?"));
    const rej = (c.params.options ?? []).find((o) => o.kind === "reject_once" || o.kind === "reject_always");
    return { outcome: { outcome: "selected", optionId: rej?.optionId ?? "reject" } };
  })
  .onRequest(acp.methods.client.fs.readTextFile, (c) => { seen.push("fs/read:" + c.params.path); return { content: "" }; })
  .onRequest(acp.methods.client.fs.writeTextFile, (c) => { seen.push("fs/write:" + c.params.path); return {}; })
  .onRequest(acp.methods.client.terminal.create, (c) => { seen.push("term/create:" + c.params.command); return { terminalId: "t_probe" }; })
  .onRequest(acp.methods.client.terminal.output, () => ({ output: "", truncated: false }))
  .onRequest(acp.methods.client.terminal.waitForExit, () => ({ exitCode: 0, signal: null }))
  .onRequest(acp.methods.client.terminal.kill, () => ({}))
  .onRequest(acp.methods.client.terminal.release, () => { seen.push("term/release"); return {}; })
  .onRequest(acp.methods.client.elicitation.create, () => ({ action: "cancel" }))
  .onNotification(acp.methods.client.session.update, (c) => seen.push("update:" + c.params.update.sessionUpdate));

const call = (ctx, method, params) =>
  ctx.request(method, params).then(
    (r) => ({ ok: true, r }),
    (e) => ({ ok: false, e }),
  );

try {
  await clientApp.connectWith(stream, async (ctx) => {
    const init = await call(ctx, acp.methods.agent.initialize, {
      protocolVersion: acp.PROTOCOL_VERSION,
      clientCapabilities: { fs: { readTextFile: true, writeTextFile: true }, terminal: true },
      clientInfo: { name: "agent-phone", version: "0.1.0" },
    });
    if (!init.ok) { record("initialize", false, init.e?.message); return; }
    const i = init.r;

    console.log(`agent: ${i.agentInfo?.name} ${i.agentInfo?.version}`);
    console.log(`authMethods: ${JSON.stringify((i.authMethods ?? []).map((m) => ({ type: m.type, id: m.id, args: m.args })))}`);
    console.log(`capabilities: ${JSON.stringify(i.agentCapabilities)}\n`);

    for (const [path, want] of Object.entries(EXPECT)) {
      const got = dot(i, path);
      const ok = want === "object" ? got != null && typeof got === "object" : got === want;
      record(path, ok, ok ? "" : `ожидал ${JSON.stringify(want)}, получил ${JSON.stringify(got)}`);
    }

    const term = (i.authMethods ?? []).find((m) => m.type === "terminal");
    if (!term) record("terminal-auth в authMethods", false, "метода нет");
    else {
      const a = await call(ctx, acp.methods.agent.authenticate, { methodId: term.id });
      if (!a.ok && ALLOW_NO_AUTH) console.log(`authenticate: недоступен (${a.e?.message})`);
      else record(`authenticate(${term.id})`, a.ok, a.ok ? "" : `${a.e?.message} — вероятно уже залогинен, либо нужен \`kimi acp --login\``);
    }

    const ns = await call(ctx, acp.methods.agent.session.new, { cwd: CWD, mcpServers: [], additionalDirectories: [] });
    if (!ns.ok) {
      if (ALLOW_NO_AUTH) {
        authBlocked = true;
        console.log(`\nsession/new закрыт авторизацией (${ns.e?.message}) — дальняя часть пропущена`);
        return;
      }
      record("session/new", false, ns.e?.message); return;
    }
    const sessionId = ns.r.sessionId;
    record("session/new", !!sessionId, sessionId);
    record("configOptions непустой", (ns.r.configOptions ?? []).length > 0, JSON.stringify((ns.r.configOptions ?? []).map((o) => o.id)));
    record("modes переданы", !!ns.r.modes?.currentModeId, ns.r.modes?.currentModeId);

    const l = await call(ctx, acp.methods.agent.session.list, { cwd: CWD });
    record("session/list", l.ok && Array.isArray(l.r.sessions), l.ok ? `${l.r.sessions.length} шт.` : l.e?.message);

    const f = await call(ctx, acp.methods.agent.session.fork, { sessionId, cwd: CWD, mcpServers: [], additionalDirectories: [] });
    record("session/fork", f.ok && f.r.sessionId !== sessionId, f.ok ? f.r.sessionId : f.e?.message);
    if (f.ok) await call(ctx, acp.methods.agent.session.close, { sessionId: f.r.sessionId });

    const ld = await call(ctx, acp.methods.agent.session.load, { sessionId, cwd: CWD, mcpServers: [] });
    record("session/load", ld.ok, ld.e?.message ?? "replay истории разрешён");

    const neg = await call(ctx, acp.methods.agent.session.load, { sessionId, mcpServers: [] });
    record("load без cwd → -32602", !neg.ok && neg.e?.code === -32602, neg.ok ? "принял невалидный запрос!" : `код ${neg.e?.code}`);

    const mnf = await call(ctx, "providers/list", {});
    record("providers/list → methodNotFound", !mnf.ok && (mnf.e?.code === -32601 || /not found/i.test(String(mnf.e?.message))), mnf.ok ? "неожиданно поддержан" : `код ${mnf.e?.code}`);

    if (SEND_PROMPT) {
      const p = await call(ctx, acp.methods.agent.session.prompt, { sessionId, prompt: [{ type: "text", text: "Ответь ровно OK и больше ничего." }] });
      record("session/prompt → stopReason", p.ok && ["end_turn", "cancelled", "refusal"].includes(p.r.stopReason), p.ok ? p.r.stopReason : p.e?.message);
    } else console.log("(PROBE_PROMPT=0 — prompt не отправлялся, токены не потрачены)\n");

    await call(ctx, acp.methods.agent.session.close, { sessionId });
  });
} catch (e) {
  record("соединение", false, e?.message ?? String(e));
}

for (const r of results) console.log(`${r.ok ? "✔" : "✘"} ${r.name}${r.detail ? "  — " + r.detail : ""}`);
console.log(`\nобратные RPC, которые реально дёрнул агент: ${seen.length ? seen.join(", ") : "(ни одного)"}`);
const bad = results.filter((r) => !r.ok);
if (authBlocked) {
  console.log(`\nG3′ ЧАСТИЧНО (${bad.length ? "с расхождениями!" : "без расхождений"}): живым агентом подтверждены ` +
    `initialize + матрица capabilities (${results.length} проверок). Авторизованные методы не проверялись — нужен KIMI_API_KEY.`);
  proc.kill();
  process.exit(bad.length ? 1 : 0);
}
console.log(bad.length ? `\nРАСХОЖДЕНИЙ: ${bad.length} из ${results.length} — пересними docs/facts/upstream/kimi-acp.md` : `\nВсё совпало (${results.length})`);
proc.kill();
process.exit(bad.length ? 1 : 0);
