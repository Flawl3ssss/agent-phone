#!/bin/sh
# tools/acp-smoke.sh — гейт G3′ из CHARTER §8.
#
# Проверяет ACP-слой БЕЗ подписки и БЕЗ сети (режим mock) и против живого Kimi (режим live).
# ЗАПУСКАТЬ НА GLIBC-ХОСТЕ (CHARTER §4.2): паритет libc с боевым гостем, иначе тест ничего
# не проверяет. Скрипт сам это проверяет и падает на musl.
set -eu

say() { printf '[acp-smoke] %s\n' "$*"; }
die() { printf '[acp-smoke] FAIL: %s\n' "$*" >&2; exit 1; }

# ── 0. паритет libc и архитектура ─────────────────────────────────────────────
if [ -e /lib/libc.musl-aarch64.so.1 ] || [ -e /lib/libc.musl-x86_64.so.1 ] || ldd /bin/ls 2>&1 | grep -q musl; then
  die "хост на musl. G3′ обязан идти на glibc (см. CHARTER §4.2). Подними debian:12 / ubuntu:24.04."
fi
[ "$(uname -m)" = "aarch64" ] || say "WARNING: хост $(uname -m), а гость arm64 — результат переносим частично"
say "ldd: $(ldd --version 2>&1 | head -1)"

MODE="${1:-mock}"
HERE="$(cd "$(dirname "$0")" && pwd)"

# ── 1. зависимости ────────────────────────────────────────────────────────────
if [ ! -d "$HERE/node_modules/@agentclientprotocol/sdk" ]; then
  say "ставлю @agentclientprotocol/sdk@^1.3.0 (Apache-2.0)…"
  ( cd "$HERE" && npm init -y >/dev/null 2>&1 && npm i --silent --no-audit --no-fund '@agentclientprotocol/sdk@^1.3.0' ) \
    || die "не удалось поставить SDK"
fi

# ── 2. самопроверка мока (агент+клиент в одном процессе) ─────────────────────
say "режим=$MODE: самопроверка мока…"
node "$HERE/acp-mock-agent.mjs" --selftest || die "selftest мока красный"

# 2b. транспорт: тот же контракт поверх НАСТОЯЩЕГО stdio (spawn + NDJSON), а не in-memory
say "прогон поверх stdio (spawn мока как отдельного процесса)…"
KIMI_BIN="$HERE/kimi-as-agent" PROBE_PROMPT=0 ACP_CWD="$HERE" node "$HERE/acp-live-probe.mjs"   || die "stdio-транспорт/контракт красный"

if [ "$MODE" = "mock" ]; then
  say "OK (mock). ACP v1 подтверждён: 24 проверки in-memory + 23 поверх stdio."
  exit 0
fi

# ── 3. live: то же рукопожатие против настоящего `kimi acp` ──────────────────
command -v kimi >/dev/null 2>&1 || die "kimi не в PATH. Внутри proot: tools/guest-exec.sh …"
say "kimi --version: $(kimi --version 2>/dev/null || echo '?')"

# 3a. initialize/session/new против живого агента, сверяем матрицу capabilities
node "$HERE/acp-live-probe.mjs" || die "живой Kimi не ответил ожидаемо — сверяйся с docs/en/reference/kimi-acp.md"

say "OK (live)."
