// Проверка собранного payload тем же способом, каким его запустит приложение:
// ld-linux из jniLibs + --library-path на assets/runtime/lib + node + main.mjs из assets.
// Ничего не переупаковано и не подменено — берутся файлы ровно дерева сборки.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const ROOT = '/var/minis/workspace/agent-phone';
const JNI = join(ROOT, 'app/src/main/jniLibs/arm64-v8a');
const ASSETS = join(ROOT, 'app/src/main/assets/runtime');
const LDR = join(JNI, 'libldr.so');
const NODE = join(JNI, 'libnode.so');
const LIBS = join(ASSETS, 'lib');
const ENTRY = join(ASSETS, 'kimi/node_modules/@moonshot-ai/kimi-code/dist/main.mjs');

for (const p of [LDR, NODE, ENTRY]) if (!existsSync(p)) { console.log('НЕТ ФАЙЛА', p); process.exit(1); }

const home = mkdtempSync(join(tmpdir(), 'kimhome-'));
const p = spawn(LDR, ['--library-path', `${LIBS}:${JNI}`, NODE, ENTRY, 'acp'], {
  cwd: home,
  env: { HOME: home, PATH: `/system/bin:/usr/bin:/bin`, TERM: 'xterm-256color', TMPDIR: home, LANG: 'C.UTF-8' },
  stdio: ['pipe', 'pipe', 'pipe'],
});

let buf = ''; const frames = [];
p.stdout.on('data', (d) => {
  buf += d.toString('utf8');
  let i; while ((i = buf.indexOf('\n')) >= 0) { const l = buf.slice(0, i).trim(); buf = buf.slice(i + 1); if (l) frames.push(l); }
});
let err = '';
p.stderr.on('data', (d) => { err += d.toString('utf8'); });
p.on('exit', (c, s) => console.log(`EXIT code=${c} sig=${s}`));

await new Promise((r) => setTimeout(r, 4000));
p.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: 1, clientCapabilities: { fs: { readTextFile: true, writeTextFile: true } } } }) + '\n');
await new Promise((r) => setTimeout(r, 6000));

const f = frames[0];
if (!f) { console.log('КАДРОВ НЕТ. stderr:', err.slice(0, 600) || '(пусто)'); }
else {
  const j = JSON.parse(f);
  const caps = j.result?.agentCapabilities ?? {};
  console.log('ОТВЕТ ЕСТЬ:', f.slice(0, 120), '…');
  console.log('agentInfo        :', JSON.stringify(j.result?.agentInfo));
  console.log('protocolVersion  :', j.result?.protocolVersion);
  console.log('loadSession      :', caps.loadSession);
  console.log('prompt.image     :', caps.promptCapabilities?.image);
  console.log('prompt.audio     :', caps.promptCapabilities?.audio);
  console.log('mcp http/sse     :', JSON.stringify(caps.mcpCapabilities));
  console.log('authMethods[0]   :', JSON.stringify(j.result?.authMethods?.[0]?.args));
  p.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'session/new', params: { cwd: home, mcpServers: [] } }) + '\n');
  await new Promise((r) => setTimeout(r, 5000));
  const s = frames.find((x) => x.includes('"id":2'));
  console.log('session/new      :', s ? s.slice(0, 150) : 'нет ответа');
}
p.kill('SIGKILL');
