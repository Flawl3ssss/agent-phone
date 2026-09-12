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

REPO=Flawl3ssss/agent-phone
REF=main
# raw-домен иногда вообще не резолвится (проверено на этом же устройстве),
# поэтому он у нас третий по приоритету, а не единственный.
REPO_RAW="https://raw.githubusercontent.com/$REPO/$REF"
WORK="$HOME/agent-phone"
BIN="$WORK/node_modules/.bin"

check_only=0
[ "${1:-}" = "--check" ] && check_only=1

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
bad() { printf '\033[31m!! %s\033[0m\n' "$*" >&2; }
ok() { printf '\033[32m   %s\033[0m\n' "$*"; }

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
say "3/5  Инструменты из $REPO@$REF"
mkdir -p "$HOME/agent-phone" "$HOME/agent-phone/tools"
cd "$HOME/agent-phone"

# Один источник — это точка отказа. Здесь их три, и каждый с ретраями: DNS рвёт
# выборочно (на этом же устройстве api.github.com работал весь день, а
# raw.githubusercontent.com не резолвился ни разу; через минуту наоборот).
curl_retry() {
    local n=1
    while [ $n -le 4 ]; do
        if curl -fsSL --connect-timeout 15 --max-time 120 "$@"; then return 0; fi
        sleep 3; n=$((n + 1))
    done
    return 1
}

got_all=0
# 1) весь tools/ одной загрузкой архива — меньше запросов, атомарнее
if curl_retry -o /tmp/ap.tar.gz "https://codeload.github.com/$REPO/tar.gz/refs/heads/$REF" \
   && tar -tzf /tmp/ap.tar.gz >/dev/null 2>&1; then
    top=$(tar -tzf /tmp/ap.tar.gz | head -1 | cut -d/ -f1)
    if [ -n "$top" ] && tar -xzf /tmp/ap.tar.gz -C /tmp && [ -d "/tmp/$top/tools" ]; then
        cp -r "/tmp/$top/tools/." "$HOME/agent-phone/tools/" && got_all=1
        ok "весь tools/ взят из архива"
    fi
    rm -rf "/tmp/$top" /tmp/ap.tar.gz
fi

# 2) и 3) по файлам: сначала API, потом raw
fetch_one() {
    local rel="$1" dst="tools/$1"
    [ "$got_all" = 1 ] && [ -s "$dst" ] && return 0
    mkdir -p "$(dirname "$dst")"
    rm -f "$dst"
    if curl_retry -H "Accept: application/vnd.github.raw" -o "$dst" \
          "https://api.github.com/repos/$REPO/contents/tools/$rel?ref=$REF" && [ -s "$dst" ]; then
        return 0
    fi
    rm -f "$dst"
    curl_retry -o "$dst" "$REPO_RAW/tools/$rel" && [ -s "$dst" ]
}

for f in acp-mock-agent.mjs acp-live-probe.mjs termux/kimi-acp-bridge.mjs termux/README.md; do
    if fetch_one "$f"; then printf '  ok   tools/%-32s %s Б\n' "$f" "$(wc -c < "tools/$f")"
    else bad "tools/$f не скачался ни с одного из трёх источников"; exit 1; fi
done
# опциональный WS-мост: его отсутствие — не поломка
fetch_one termux/kimi-acp-bridge-ws.mjs \
    || printf '  (WS-вариант моста не скачан — он только для отладки из браузера)\n'

# Скачанное ОБЯЗАНО читаться: curl при обрыве оставляет предыдущий файл, и он
# выглядит свежим, а битый .mjs упал бы потом невнятной ошибкой выполнения.
for f in tools/acp-mock-agent.mjs tools/acp-live-probe.mjs tools/termux/kimi-acp-bridge.mjs; do
    node --check "$f" 2>/dev/null || { bad "$f не читается как JS — удалите каталог и повторите"; exit 1; }
done
printf 'синтаксис всех скачанных файлов ok\n'
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

  1) Проверить протокол против НАСТОЯЩЕГО kimi (тратит токены):
        cd $WORK
        KIMI_BIN=$BIN/kimi ACP_CWD=$HOME node tools/acp-live-probe.mjs

     Имя переменной — KIMI_BIN (не ACP_AGENT_CMD: такой в пробе нет, и проба
     молча запустила бы `kimi acp` из PATH). Probe сама дёрнет ACP-authenticate,
     поэтому вход обычно происходит прямо здесь: Kimi покажет ссылку подтверждения.

     Если дойдёте до «✔ session/new» — протокол с живым Kimi сходится, можно
     в приложение. Если проба откажет по авторизации — войдите явно:
        $BIN/kimi --login
     (именно `--login`: это terminal-метод, который настоящий Kimi 0.42.0
      объявляет в authMethods, — проверено живым прогоном, а не догадкой).

Потом — мост и приложение:
        node $WORK/tools/termux/kimi-acp-bridge.mjs        # держать Termux открытым
  а в Agent Phone выбрать режим «kimi · Termux» (чип в шапке).

ВАЖНО ДЛЯ XIAOMI/MIUI: система убивает фоновые процессы, и мост умрёт молча.
Держите Termux открытым (терминал с запущенным мостом) либо включите:
        termux-wake-lock            # не давать устройству уходить в глубокий сон
и в настройках: Батарея → Termux → «Без ограничений» + автозапуск.
EOF
