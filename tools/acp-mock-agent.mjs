#!/usr/bin/env node
/**
 * acp-mock-agent.mjs — мок ACP-агента для проекта Agent Phone.
 *
 * Зачем:
 *   Фаза Ф3 (ACP-слой) упирается в то, что живого агента не запустить без подписки, сети
 *   и телефона. Этот мок даёт ровно тот же контракт, что `kimi acp`, и позволяет
 *   разработать/протестировать клиент (раннер, кодек, машина состояний, три шлюза) офлайн.
 *
 * Мимикрия под Kimi Code CLI 0.42.0 (docs/en/reference/kimi-acp.md):
 *   initialize / authenticate / logout
 *   session/new|load|resume|list|fork|close|delete|prompt|cancel|set_mode|set_config_option|set_model
 *   обратные RPC: fs/read_text_file, fs/write_text_file, terminal/*,
 *                 session/request_permission, elicitation/create
 *   capabilities — копия матрицы Kimi (включая audio:false и отсутствие providers/nes/document).
 *
 * Запуск:   node tools/acp-mock-agent.mjs            (stdio)
 *           node tools/acp-mock-agent.mjs --selftest (агент+клиент в одном процессе)
 * Логика:   всё в stderr; stdout — только NDJSON протокола (как у настоящего Kimi).
 *
 * Лицензия: MIT. Зависимость: @agentclientprotocol/sdk@^1.3.0 (Apache-2.0) — тот же SDK,
 * что у packages/acp-server в MoonshotAI/kimi-code.
 */

import * as acp from "@agentclientprotocol/sdk";
import { Readable, Writable } from "node:stream";

const AGENT_INFO = { name: "Kimi Code CLI", title: "Kimi Code CLI (mock)", version: "0.42.0-mock" };

// ──────────────────────────────────────────────────────────────────────────────
// Точная копия матрицы возможностей Kimi. Если Kimi её поменяет — меняем здесь
// и это становится тестом "клиент не падает, когда возможности сузились".
// ──────────────────────────────────────────────────────────────────────────────
const AGENT_CAPABILITIES = {
  loadSession: true,
  promptCapabilities: { image: true, audio: false, embeddedContext: true },
  mcpCapabilities: { http: true, sse: true },
  sessionCapabilities: {
    list: {},
    resume: {},
    close: {},
    delete: {},
    fork: {},
    additionalDirectories: {},
  },
  auth: { logout: {} },
};

const AUTH_METHODS = [
  {
    type: "terminal",
    id: "login",
    name: "Kimi Code Login",
    description: "Run `kimi acp --login` (device-code OAuth) and wait for completion.",
    args: ["--login"],
    env: {},
  },
];

const MODES = {
  currentModeId: "default",
  availableModes: [
    { id: "default", name: "Default" },
    { id: "plan", name: "Plan" },
    { id: "yolo", name: "YOLO", description: "Auto-approve everything." },
  ],
};

const log = (...a) => process.stderr.write(`[mock-acp] ${a.join(" ")}\n`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rid = (p) => p + Array.from(crypto.getRandomValues(new Uint8Array(8))).map((b) => b.toString(16).padStart(2, "0")).join("");

// ──────────────────────────────────────────────────────────────────────────────
// Состояние
// ──────────────────────────────────────────────────────────────────────────────
class MockAgent {
  constructor() {
    /** sessionId → {cwd, additionalDirectories, mcpServers, modeId, history[], aborted} */
    this.sessions = new Map();
    this.authenticated = false;
    /** Сценарий следующего turn'а. SELFTEST сжимает его до минимума. */
    this.scenario = process.env.MOCK_SCENARIO || "full";
  }

  // ── core ────────────────────────────────────────────────────────────────────
  initialize(params) {
    if (params.protocolVersion !== acp.PROTOCOL_VERSION) {
      throw new acp.RequestError(-32602, `unsupported protocolVersion ${params.protocolVersion}; mock speaks ${acp.PROTOCOL_VERSION}`);
    }
    log("initialize: client caps =", JSON.stringify(params.clientCapabilities ?? {}));
    return { protocolVersion: acp.PROTOCOL_VERSION, agentCapabilities: AGENT_CAPABILITIES, authMethods: AUTH_METHODS, agentInfo: AGENT_INFO };
  }

  authenticate(params) {
    if (params?.methodId !== "login") throw new acp.RequestError(-32602, `unknown methodId: ${params?.methodId}`);
    if (process.env.MOCK_AUTH_FAIL === "1") throw acp.RequestError.authRequired();
    this.authenticated = true;
    log("authenticate: ok");
    return {};
  }

  logout() {
    this.authenticated = false;
    log("logout");
    return {};
  }

  // ── session lifecycle ───────────────────────────────────────────────────────
  newSession(p) {
    if (!this.authenticated && process.env.MOCK_REQUIRE_AUTH === "1") throw acp.RequestError.authRequired();
    if (!p.cwd) throw new acp.RequestError(-32602, "cwd is required");
    const id = rid("session_");
    this.sessions.set(id, {
      cwd: p.cwd,
      additionalDirectories: p.additionalDirectories ?? [],
      mcpServers: p.mcpServers ?? [],
      modeId: "default",
      history: [],
      aborted: false,
      createdAt: new Date().toISOString(),
    });
    if (p.mcpServers?.some((s) => s.type === "acp")) log("dropping unsupported MCP transport: acp");
    log(`session/new ${id} cwd=${p.cwd} extra=${(p.additionalDirectories ?? []).length} mcp=${(p.mcpServers ?? []).length}`);
    return { sessionId: id, modes: MODES, configOptions: this.#configOptions(id) };
  }

  load(p) {
    if (!p.cwd) throw new acp.RequestError(-32602, "cwd is required by LoadSessionRequest");
    const s = this.#must(p.sessionId);
    log(`session/load ${p.sessionId} — реплей истории из ${s.history.length} событий`);
    return { modes: MODES, configOptions: this.#configOptions(p.sessionId) };
  }

  resume(p) {
    if (!p.cwd) throw new acp.RequestError(-32602, "cwd is required by ResumeSessionRequest");
    this.#must(p.sessionId);
    return { modes: MODES, configOptions: this.#configOptions(p.sessionId) };
  }

  list(p) {
    const items = [...this.sessions.entries()]
      .filter(([, s]) => !p?.cwd || s.cwd === p.cwd)
      .map(([id, s]) => ({ sessionId: id, cwd: s.cwd, createdAt: s.createdAt, title: `mock ${id.slice(-4)}` }));
    return { sessions: items, nextCursor: null };
  }

  fork(p) {
    const src = this.#must(p.sessionId);
    const id = rid("session_");
    this.sessions.set(id, { ...src, history: [...src.history], createdAt: new Date().toISOString() });
    if (p.cwd || p.mcpServers?.length) log("session/fork: cwd/additionalDirectories/mcpServers на запросе ИГНОРИРУЮТСЯ (как у Kimi)");
    log(`session/fork ${p.sessionId} → ${id}`);
    return { sessionId: id, modes: MODES, configOptions: this.#configOptions(id) };
  }

  close(p) {
    // best-effort: неизвестный id — не ошибка
    this.sessions.delete(p.sessionId);
    return {};
  }

  delete(p) {
    if (!this.sessions.has(p.sessionId)) throw new acp.RequestError(-32602, `no such session: ${p.sessionId}`);
    this.sessions.delete(p.sessionId);
    return {};
  }

  setMode(p) {
    const s = this.#must(p.sessionId);
    if (!MODES.availableModes.some((m) => m.id === p.modeId)) throw new acp.RequestError(-32602, `unknown modeId ${p.modeId}`);
    s.modeId = p.modeId;
    return {};
  }

  setConfigOption(p) {
    this.#must(p.sessionId);
    return { configOptions: this.#configOptions(p.sessionId) };
  }

  setModel(p) {
    this.#must(p.sessionId);
    return {};
  }

  cancel(p) {
    const s = this.sessions.get(p.sessionId);
    if (s) s.aborted = true;
    log(`session/cancel ${p.sessionId}`);
  }

  #configOptions(id) {
    return [
      { id: "model", name: "Model", type: "select", category: "model", currentValue: "kimi-k2", options: [{ value: "kimi-k2", name: "Kimi K2" }, { value: "kimi-k2-turbo", name: "Kimi K2 Turbo" }] },
      { id: "mode", name: "Mode", type: "select", category: "mode", currentValue: this.sessions.get(id)?.modeId ?? "default", options: MODES.availableModes.map((m) => ({ value: m.id, name: m.name })) },
      { id: "thinking", name: "Thinking", type: "boolean", currentValue: true },
    ];
  }

  #must(id) {
    const s = this.sessions.get(id);
    if (!s) throw new acp.RequestError(-32002, `session not found: ${id}`);
    return s;
  }

  // ── turn ────────────────────────────────────────────────────────────────────
  async prompt(p, client) {
    const s = this.#must(p.sessionId);
    s.aborted = false;
    const sid = p.sessionId;
    const say = (update) => {
      s.history.push(update);
      return client.notify(acp.methods.client.session.update, { sessionId: sid, update })
        .catch((e) => { log("REJECTED update:", update.sessionUpdate, "->", e?.message ?? e, JSON.stringify(update).slice(0, 300)); });
    };
    const step = async (ms) => { if (s.aborted) throw new AbortError(); await sleep(this.scenario === "fast" ? 1 : ms); if (s.aborted) throw new AbortError(); };

    try {
      // 1. мысли
      await say({ sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "Разбираюсь в задаче…" } });
      await step(120);

      // 2. план (T11)
      await say({
        sessionUpdate: "plan",
        entries: [
          { content: "Прочитать конфиг", status: "pending", priority: "medium" },
          { content: "Внести правку", status: "pending", priority: "high" },
          { content: "Собрать и проверить", status: "pending", priority: "medium" },
        ],
      });
      await step(120);

      // 3. ЧТЕНИЕ через обратный RPC клиента (fs/read_text_file)
      const readPath = `${s.cwd}/config.json`;
      await say({ sessionUpdate: "tool_call", toolCallId: "c1", title: `Read ${readPath}`, kind: "read", status: "pending", locations: [{ path: readPath }], rawInput: { path: readPath } });
      let fileText = "(клиент отказал или не поддерживает fs)";
      try {
        const r = await client.request(acp.methods.client.fs.readTextFile, { sessionId: sid, path: readPath });
        fileText = r.content;
        log(`fs/read_text_file ok: ${fileText.length} chars`);
      } catch (e) {
        log("fs/read_text_file failed:", e?.message ?? e);
      }
      await say({ sessionUpdate: "tool_call_update", toolCallId: "c1", status: "completed", rawOutput: { bytes: fileText.length }, content: [{ type: "content", content: { type: "text", text: fileText.slice(0, 400) } }] });
      await step(120);

      if (this.scenario === "fast") return { stopReason: "end_turn" };

      // 4. ПРАВКА: сначала request_permission, потом fs/write_text_file
      const newText = JSON.stringify({ editedBy: "mock-agent", at: new Date().toISOString() }, null, 2);
      await say({ sessionUpdate: "tool_call", toolCallId: "c2", title: `Edit ${readPath}`, kind: "edit", status: "pending", locations: [{ path: readPath }], rawInput: { path: readPath, content: newText } });

      const perm = await client.request(acp.methods.client.session.requestPermission, {
        sessionId: sid,
        toolCall: { toolCallId: "c2", title: `Edit ${readPath}`, kind: "edit", status: "pending", locations: [{ path: readPath }] },
        options: [
          { optionId: "allow_once", name: "Разрешить", kind: "allow_once" },
          { optionId: "allow_always", name: "Всегда для этой папки", kind: "allow_always" },
          { optionId: "reject_once", name: "Отклонить", kind: "reject_once" },
        ],
      });

      if (perm.outcome.outcome === "cancelled" || !["allow_once", "allow_always"].includes(perm.outcome.optionId)) {
        await say({ sessionUpdate: "tool_call_update", toolCallId: "c2", status: "failed", rawOutput: { denied: perm.outcome } });
        await say({ sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Правку отклонили — пропускаю." } });
        return { stopReason: "end_turn" };
      }

      try {
        await client.request(acp.methods.client.fs.writeTextFile, { sessionId: sid, path: readPath, content: newText });
        await say({ sessionUpdate: "tool_call_update", toolCallId: "c2", status: "completed", content: [{ type: "diff", path: readPath, oldText: fileText, newText }], rawOutput: { written: true } });
        log("fs/write_text_file ok");
      } catch (e) {
        await say({ sessionUpdate: "tool_call_update", toolCallId: "c2", status: "failed", rawOutput: { error: String(e?.message ?? e) } });
        log("fs/write_text_file failed:", e?.message ?? e);
      }
      await step(120);

      // 5. КОМАНДА через terminal/* (если клиент объявил terminal:true)
      await say({ sessionUpdate: "tool_call", toolCallId: "c3", title: "Run `node -v`", kind: "execute", status: "pending", rawInput: { command: "node", args: ["-v"] } });
      try {
        const t = await client.request(acp.methods.client.terminal.create, { sessionId: sid, command: "node", args: ["-v"], cwd: s.cwd, env: [] });
        const out = await client.request(acp.methods.client.terminal.waitForExit, { sessionId: sid, terminalId: t.terminalId });
        const o = await client.request(acp.methods.client.terminal.output, { sessionId: sid, terminalId: t.terminalId });        await client.request(acp.methods.client.terminal.release, { sessionId: sid, terminalId: t.terminalId });
        await say({ sessionUpdate: "tool_call_update", toolCallId: "c3", status: (out?.exitCode ?? 0) === 0 ? "completed" : "failed", content: [{ type: "content", content: { type: "text", text: `exit=${out?.exitCode} out=${JSON.stringify(o?.output).slice(0, 120)}` } }] });
        log("terminal/* ok, released");
      } catch (e) {
        await say({ sessionUpdate: "tool_call_update", toolCallId: "c3", status: "failed", rawOutput: { error: String(e?.message ?? e) } });
        log("terminal/* unavailable:", e?.message ?? e);
      }
      await step(120);

      // 6. ВОПРОС через elicitation/create (Kimi так рендерит AskUserQuestion)
      try {
        const el = await client.request(acp.methods.client.elicitation.create, {
          sessionId: sid,
          mode: "form",
          message: "Деплоить результат?",
          requestedSchema: { type: "object", properties: { deploy: { type: "boolean", title: "Задеплоить сейчас" } }, required: [] },
        });
        log("elicitation/create →", JSON.stringify(el));
      } catch (e) {
        log("elicitation/create not supported (это нормально):", e?.message ?? e);
      }

      // 7. расходомер (R16)
      await say({ sessionUpdate: "usage_update", used: 4213, size: 200000, cost: { amount: 0.037, currency: "USD" } });
      await say({ sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Готово. Конфиг обновлён, сборка зелёная." } });
      return { stopReason: "end_turn" };
    } catch (e) {
      if (e instanceof AbortError) { await say({ sessionUpdate: "agent_message_chunk", content: { type: "text", text: "(остановлено)" } }); return { stopReason: "cancelled" }; }
      log("turn error:", e?.stack ?? e);
      return { stopReason: "refusal" };
    }
  }
}

class AbortError extends Error {}

// ──────────────────────────────────────────────────────────────────────────────
// Проводка
// ──────────────────────────────────────────────────────────────────────────────
function buildApp(agent) {
  return acp
    .agent({ name: AGENT_INFO.name, version: AGENT_INFO.version })
    .onRequest("initialize", (c) => agent.initialize(c.params))
    .onRequest("authenticate", (c) => agent.authenticate(c.params))
    .onRequest("logout", () => agent.logout())
    .onRequest("session/new", (c) => agent.newSession(c.params))
    .onRequest("session/load", (c) => agent.load(c.params))
    .onRequest("session/resume", (c) => agent.resume(c.params))
    .onRequest("session/list", (c) => agent.list(c.params))
    .onRequest("session/fork", (c) => agent.fork(c.params))
    .onRequest("session/close", (c) => agent.close(c.params))
    .onRequest("session/delete", (c) => agent.delete(c.params))
    .onRequest("session/set_mode", (c) => agent.setMode(c.params))
    .onRequest("session/set_config_option", (c) => agent.setConfigOption(c.params))
    // `session/set_model` — нестабильное расширение (Kimi держит его из ACP 0.23).
    // Официальный SDK 1.4 его не знает, поэтому регистрируется как custom-метод
    // через форму onRequest(spec, handler).
    .request({ method: "session/set_model" }, (c) => agent.setModel(c.params))
    .onRequest("session/prompt", (c) => agent.prompt(c.params, c.client))
    .onNotification("session/cancel", (c) => agent.cancel(c.params));
}

// ──────────────────────────────────────────────────────────────────────────────
// SELFTEST: клиент в этом же процессе, поверх in-memory duplex-потока
// ──────────────────────────────────────────────────────────────────────────────
async function selftest() {
  process.env.MOCK_SCENARIO = "full";   // полный turn: permission + terminal + elicitation + usage
  const agentApp = buildApp(new MockAgent());

  const seen = [];
  const app = acp.client({ name: "agent-phone-selftest", version: "0" })
    .onRequest(acp.methods.client.session.requestPermission, (c) => { seen.push("request_permission"); return { outcome: { outcome: "selected", optionId: "allow_once" } }; })
    .onRequest(acp.methods.client.fs.readTextFile, (c) => { seen.push("fs/read"); return { content: '{"orig":true}' }; })
    .onRequest(acp.methods.client.fs.writeTextFile, (c) => { seen.push("fs/write"); return {}; })
    .onRequest(acp.methods.client.terminal.create, (c) => { seen.push("term/create"); return { terminalId: "t1" }; })
    .onRequest(acp.methods.client.terminal.output, () => ({ output: "v22.23.2\n", truncated: false }))
    .onRequest(acp.methods.client.terminal.waitForExit, () => { seen.push("term/wait"); return { exitCode: 0, signal: null }; })
    .onRequest(acp.methods.client.terminal.kill, () => { seen.push("term/kill"); return {}; })
    .onRequest(acp.methods.client.terminal.release, () => { seen.push("term/release"); return {}; })
    .onRequest(acp.methods.client.elicitation.create, () => { seen.push("elicitation"); return { action: "accept", content: { deploy: false } }; })
    .onNotification(acp.methods.client.session.update, (c) => seen.push("update:" + c.params.update.sessionUpdate));

  const ok = [];
  const check = (name, cond) => ok.push(`${cond ? "✔" : "✘"} ${name}`);

  await app.connectWith(agentApp, async (ctx) => {
    const R = (m, p) => ctx.request(m, p);

    const init = await R(acp.methods.agent.initialize, {
      protocolVersion: acp.PROTOCOL_VERSION,
      clientCapabilities: { fs: { readTextFile: true, writeTextFile: true }, terminal: true },
      clientInfo: { name: "agent-phone", version: "0.1.0" },
    });
    check("protocolVersion === 1", init.protocolVersion === 1);
    check("agentInfo.name = Kimi Code CLI", init.agentInfo?.name === "Kimi Code CLI");
    check("loadSession=true", init.agentCapabilities?.loadSession === true);
    check("audio=false", init.agentCapabilities?.promptCapabilities?.audio === false);
    check("terminal-auth в authMethods", init.authMethods?.[0]?.type === "terminal");
    check("additionalDirectories объявлен", !!init.agentCapabilities?.sessionCapabilities?.additionalDirectories);
    check("mcpCapabilities.http=true", init.agentCapabilities?.mcpCapabilities?.http === true);

    await R(acp.methods.agent.authenticate, { methodId: "login" });
    const ns = await R(acp.methods.agent.session.new, {
      cwd: "/workspace/proj",
      additionalDirectories: ["/workspace/shared"],
      mcpServers: [{ name: "game-preview", type: "http", url: "http://127.0.0.1:59100/mcp", headers: [] }],
    });
    check("sessionId получен", !!ns.sessionId);
    check("configOptions непустой", (ns.configOptions ?? []).length > 0);
    check("modes переданы", !!ns.modes?.currentModeId);

    const res = await R(acp.methods.agent.session.prompt, { sessionId: ns.sessionId, prompt: [{ type: "text", text: "сделай дело" }] });
    check("stopReason = end_turn", res.stopReason === "end_turn");
    check("клиент получил fs/read", seen.includes("fs/read"));
    check("клиент получил request_permission", seen.includes("request_permission"));
    check("terminal выпущен (release)", seen.includes("term/release"));
    check("plan пришёл", seen.includes("update:plan"));
    check("usage_update пришёл", seen.includes("update:usage_update"));
    check("tool_call с kind=read", seen.includes("update:tool_call"));

    const ls = await R(acp.methods.agent.session.list, { cwd: "/workspace/proj" });
    check("session/list видит сессию", ls.sessions.some((x) => x.sessionId === ns.sessionId));

    const fk = await R(acp.methods.agent.session.fork, { sessionId: ns.sessionId, cwd: "/workspace/proj", additionalDirectories: [], mcpServers: [] });
    check("session/fork вернул другой id", fk.sessionId !== ns.sessionId);

    await R(acp.methods.agent.session.load, { sessionId: ns.sessionId, cwd: "/workspace/proj", mcpServers: [] });
    check("session/load прошёл", true);

    await R(acp.methods.agent.session.setMode, { sessionId: ns.sessionId, modeId: "plan" });
    check("session/set_mode прошёл", true);

    await R(acp.methods.agent.session.close, { sessionId: ns.sessionId });
    let threw = false;
    try { await R(acp.methods.agent.session.delete, { sessionId: "session_devede" }); } catch { threw = true; }
    check("delete на несуществующей → ошибка (как у Kimi)", threw);

    // негативный: session/load без cwd — протокол требует {mcpServers, cwd, sessionId}
    let badReq = false;
    try { await R(acp.methods.agent.session.load, { sessionId: ns.sessionId, mcpServers: [] }); }
    catch (e) { badReq = e?.code === -32602; }
    check("load без cwd → -32602 invalid params", badReq);

    // негативный тест: неподдерживаемый метод обязан вернуть methodNotFound, а не повесить канал
    let mnf = false;
    try { await ctx.request("providers/list", {}); } catch (e) { mnf = e?.code === -32601 || /not found/i.test(String(e?.message ?? e)); }
    check("providers/list → methodNotFound", mnf);
  });

  console.log(ok.join("\n"));
  const failed = ok.filter((l) => l.startsWith("✘"));
  console.log(failed.length ? `\nПРОВАЛ: ${failed.length} из ${ok.length}` : `\nСамопроверка: всё зелёное (${ok.length} проверок)`);
  process.exit(failed.length ? 1 : 0);
}

// ──────────────────────────────────────────────────────────────────────────────
if (process.argv.includes("--selftest")) {
  selftest().catch((e) => { console.error("selftest crashed:", e?.message, "| code:", e?.code, "| data:", JSON.stringify(e?.data ?? null)?.slice(0, 400)); process.exit(2); });
} else {
(async () => {
  const agent = new MockAgent();
  const stream = acp.ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin));
  const conn = buildApp(agent).connect(stream);
  log("готов принимать initialize на stdin; stdout — только протокол");
  // connect() резолвится сразу — держим event loop, пока живёт stdin-поток.
  // Без этого нода выходит с кодом 0 и клиент видит "ACP connection closed".
  const keep = setInterval(() => {}, 1 << 30);
  const closed = await Promise.race([
    conn.closed.then(() => "closed"),
    new Promise((r) => process.stdin.once("end", () => r("stdin-end"))),
    new Promise((r) => process.on("SIGTERM", () => r("signal"))),
    new Promise((r) => process.on("SIGINT", () => r("signal"))),
  ]);
  clearInterval(keep);
  log("соединение завершено:", closed);
  process.exit(0);
})();
}
