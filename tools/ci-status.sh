#!/bin/sh
# Статус прогонов CI по sha. curl вместо gh: в песочнице gh не установлен,
# и молча проваленный опрос (2>/dev/null) выглядит как «ещё идёт».
sha=$1
[ -n "$GH_TOKEN" ] || { echo "GH_TOKEN не задан — статус не получить"; exit 2; }
curl -sS --retry 5 --retry-delay 2 --connect-timeout 20 --max-time 60 \
  -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/Flawl3ssss/agent-phone/commits/$sha/check-runs" \
| python3 -c '
import json,sys
raw=sys.stdin.read()
try: d=json.loads(raw)
except Exception: print("не JSON (ответа нет):", raw[:160]); sys.exit(1)
runs=d.get("check_runs") or []
if not runs: print("прогонов пока нет"); sys.exit(0)
for r in runs:
    print("%-28s %-11s %s" % (r["name"][:28], r["status"], r.get("conclusion") or "-"))
'
