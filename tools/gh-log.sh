#!/bin/sh
# gh-log.sh <job-id> [out] — свежий лог джобы.
#
# Эндпоинт /actions/jobs/{id}/logs отвечает 303 на SAS-URL в
# *.blob.core.windows.net, который:
#   a) с -L у нас стабильно отваливается (http=000),
#   b) живёт 10 минут.
# Поэтому: сначала вытаскиваем Location, потом качаем его отдельно с ретраями.
# НИКОГДА не пишем в существующий файл — иначе при сетевом фаиле остаётся
# прошлый лог, и его читают как свежий (на этом я уже поймал ложный диагноз).
JID="$1"
OUT="${2:-/tmp/joblog.$1.txt}"
R=Flawl3ssss/agent-phone
rm -f "$OUT"
HDR=$(mktemp)
i=1
while [ $i -le 6 ]; do
  curl -sI --max-time 40 -H "Authorization: Bearer $GH_TOKEN" \
    "https://api.github.com/repos/$R/actions/jobs/$JID/logs" > "$HDR" 2>/dev/null
  LOC=$(awk 'tolower($1)=="location:"{print $2}' "$HDR" | tr -d '\r')
  [ -n "$LOC" ] && break
  i=$((i+1)); sleep 4
done
rm -f "$HDR"
[ -z "$LOC" ] && { echo "НЕ ПОЛУЧИЛ location (сеть?)"; exit 1; }
i=1
while [ $i -le 8 ]; do
  C=$(curl -s -o "$OUT" -w "%{http_code}" --max-time 60 "$LOC")
  if [ "$C" = "200" ] && [ -s "$OUT" ]; then
    echo "ok http=200 байт=$(wc -c < "$OUT") attempts=$i -> $OUT"
    exit 0
  fi
  i=$((i+1)); sleep 4
done
echo "ФАИЛ скачать лог (последний http=$C)"; exit 1
