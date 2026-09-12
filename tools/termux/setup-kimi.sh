#!/usr/bin/env bash
# Ставит Node + Kimi Code CLI внутри Termux, качает ACP-инструменты Agent Phone
# и ПРОВЕРЯЕТ мост до всякого Kimi (selftest на моке, без токенов).
#
#   bash setup-kimi.sh            # полная установка + selftest
#   bash setup-kimi.sh --check    # только сверить версии и ещё раз прогнать selftest
#
# Запускать ВНУТРИ Termux. Из песочницы Minis Termux недоступен: у него свой uid,
# а межприложенческий exec в Android запрещён — тот же запрет, из-за которого вообще
# понадобился мост.
set -uo pipefail

REPO_RAW="https://raw.githubusercontent.com/Flawl3ssss/agent-phone/main"
WORK="$HOME/agent-phone"
BIN="$WORK/node_modules/.bin"

check_only=0
[ "${1:-}" = "--check" ] && check_only=1

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
bad() { printf '\033[31m!! %s\033[0m\n' "$*" >&2; }

# ── 0. мы точно в Termux? ────────────────────────────────────────────────────
# Сверяем путь префикса, а не наличие команды `pkg`: функция pkg может быть и в
# других оболочках, а $PREFIX/../com.termux выдумать неоткуда.
if [ -z "${PREFIX:-}" ] || [ ! -d "$PREFIX/../com.termux" ]; then
    bad "Это не Termux (PREFIX='${PREFIX:-пусто}'). Откройте приложение Termux и запустите скрипт там."
    exit 1
fi

cd "$HOME" || exit 1

if [ "$check_only" = 1 ]; then
    say "Проверка окружения"
    printf 'node : %s\n' "$(node --version 2>/dev/null || echo НЕТ)"
    printf 'npm  : %s\n' "$(npm --version 2>/dev/null || echo НЕТ)"
    [ -x "$BIN/kimi" ] && printf 'kimi : %s\n' "$("$BIN/kimi" --version 2>&1 | head -1)" || bad "kimi не установлен"
    if [ -f "$WORK/tools/termux/kimi-acp-bridge.mjs" ]; then
        say "Selftest моста против мока"
        (cd "$WORK" && node tools/termux/kimi-acp-bridge.mjs --selftest 2>&1 | tail -16)
    fi
    exit 0
fi

# ── 1. Node ──────────────────────────────────────────────────────────────────
say "1/5  Ставим Node"
yes | pkg update 2>&1 | tail -2
# берём LTS: свежий nodejs в Termux иногда ломает нативные модули после обновления
if ! pkg install -y nodejs-lts 2>&1 | tail -2; then
    bad "nodejs-lts не ставится, пробую nodejs"
    pkg install -y nodejs || { bad "Node не установился. Смените зеркало: termux-change-repo"; exit 1; }
fi
node --version || { bad "node не виден в PATH — перезапустите Termux"; exit 1; }
# ACP SDK требует Node >= 20. Проверяем числом: наличие бинаря ничего не гарантирует.
node_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
if [ "$node_major" -lt 20 ]; then
    bad "Node $node_major < 20 — @agentclientprotocol/sdk@1.4.0 не заработает. Обновите Termux."
    exit 1
fi
printf 'Node %s подходит (нужен >= 20)\n' "$node_major"

# ── 2. npm-пакеты ────────────────────────────────────────────────────────────
say "2/5  Ставим kimi + ACP SDK в $WORK"
mkdir -p "$WORK/tools/termux" && cd "$WORK" || exit 1
[ -f package.json ] || npm init -y >/dev/null 2>&1
# Локально, а не `npm i -g`: глобальные установки в Termux упираются в свои баги с
# prefix, а локальный node_modules/.bin работает всегда и не трогает систему.
npm install --no-audit --no-fund @moonshot-ai/kimi-code @agentclientprotocol/sdk 2>&1 | tail -4
if [ -x "$BIN/kimi" ]; then
    printf 'kimi CLI: %s\n' "$("$BIN/kimi" --version 2>&1 | head -1)"
else
    bad "kimi не появился в node_modules/.bin — установка не удалась (лог выше)"
    exit 1
fi
# `ws` нужен ТОЛЬКО опциональному WebSocket-мосту (для отладки из браузера).
# Не критично, если не поставится — основной путь работает на голом TCP.
npm install --no-audit --no-fund --no-save ws >/dev/null 2>&1 || \
    printf 'подсказка: ws не поставился — это блокирует только kimi-acp-bridge-ws.mjs, основной мост не трогай\n'

# ── 3. инструменты проекта ───────────────────────────────────────────────────
say "3/5  Качаем ACP-инструменты"
# Каждый файл — отдельной загрузкой в .tmp и с проверкой синтаксиса. curl при сетевом
# сбое оставляет ПРОШЛЫЙ файл на месте, и он выглядит свежим; а битый скачанный .mjs
# вылится невнятной ошибкой времени выполнения вместо «файл не докачался».
fetch() {
    local rel="$1"
    rm -f "tools/$rel.tmp"
    if curl -fsSL --connect-timeout 15 --max-time 90 --retry 3 --retry-delay 2 \
            -o "tools/$rel.tmp" "$REPO_RAW/tools/$rel"; then
        mv "tools/$rel.tmp" "tools/$rel"
        printf '  ok   tools/%-32s %s Б\n' "$rel" "$(wc -c < "tools/$rel")"
    else
        rm -f "tools/$rel.tmp"
        bad "tools/$rel скачать не удалось"
        return 1
    fi
}
for f in acp-mock-agent.mjs acp-live-probe.mjs termux/kimi-acp-bridge.mjs; do
    fetch "$f" || exit 1
done
fetch termux/kimi-acp-bridge-ws.mjs || printf '  (опциональный WS-мост не скачан — не страшно)\n'

for f in tools/acp-mock-agent.mjs tools/acp-live-probe.mjs tools/termux/kimi-acp-bridge.mjs; do
    node --check "$f" 2>/dev/null || { bad "$f не читается как JS — перескачайте"; exit 1; }
done
printf 'синтаксис всех файлов ok\n'

# ── 4. мост должен работать ЕЩЁ ДО Kimi ──────────────────────────────────────
say "4/5  Selftest моста против мока (без токенов и сети)"
# Порядок важен: сначала доказываем, что фрейминг/сокет/убийство процесса живы на этом
# конкретно железе и этой сборке node. Если падает здесь — Kimi виноват быть не может,
# и искать надо в Termux, а не в протоколе.
# Прогон ОДИН, вывод в файл: два прогона подряд маскируют нестабильность (второй может
# зелёным закрыть первый красный) и вдвое медленнее.
if node tools/termux/kimi-acp-bridge.mjs --selftest > /tmp/bridge-selftest.log 2>&1; then
    tail -15 /tmp/bridge-selftest.log
    printf 'мост на этом устройстве работает\n'
else
    bad "selftest моста в Termux НЕ прошёл. Полный лог: cat /tmp/bridge-selftest.log"
    tail -20 /tmp/bridge-selftest.log
    exit 1
fi

# ── 5. что делать дальше ─────────────────────────────────────────────────────
say "5/5  Готово. Дальше — два шага руками"
cat <<EOF
Оба интерактивные, скрипт за вас их не сделает.

  1) Войти в Kimi (откроется браузер со ссылкой подтверждения):
        $BIN/kimi login
     Статус проверить так:
        $BIN/kimi /login

  2) Проверить протокол против НАСТОЯЩЕГО kimi (тратит токены):
        cd $WORK
        PROBE_PROMPT=1 ACP_AGENT_CMD="$BIN/kimi --print-config plain acp" \\
            node tools/acp-live-probe.mjs

Потом — мост и приложение:
        node $WORK/tools/termux/kimi-acp-bridge.mjs        # держать Termux открытым
  а в Agent Phone выбрать режим «kimi · Termux» (чип в шапке).

ВАЖНО ДЛЯ XIAOMI/MIUI: система убивает фоновые процессы, и мост умрёт молча.
Держите Termux открытым (терминал с запущенным мостом) либо включите:
        termux-wake-lock            # не давать устройству уходить в глубокий сон
и в настройках: Батарея → Termux → «Без ограничений» + автозапуск.
EOF
