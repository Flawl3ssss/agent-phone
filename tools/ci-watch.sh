#!/bin/sh
# ci-watch.sh [sha] — ждёт финал прогона именно этого коммита (по умолчанию HEAD).
#
# Без привязки к sha watcher врёт: если новый прогон ещё не зарегистрировался,
# per_page=1 возвращает СТАРЫЙ, он уже completed/failure — и скрипт уверенно
# докладывает чужие ошибки как свои. Такое случилось один раз; больше не должно.
R=Flawl3ssss/agent-phone
G=/var/minis/workspace/agent-phone/tools
SHA="${1:-$(cd /var/minis/workspace/agent-phone && git rev-parse --short HEAD)}"
echo "жду прогон коммита $SHA"
ID=""
for i in $(seq 1 40); do
  "$G/gh-get.sh" "/repos/$R/actions/runs?per_page=10" /tmp/run.json >/dev/null 2>&1 || true
  LINE=$(python3 -c "
import json,sys
try: d=json.load(open('/tmp/run.json'))
except Exception: print('NONE - - -'); raise SystemExit
m=[r for r in d['workflow_runs'] if r['head_sha'].startswith('$SHA')]
if not m: print('NONE - - -'); raise SystemExit
r=m[0]
print(r['id'], r['status'], r['conclusion'] or '-', r['run_number'])
" 2>/dev/null)
  set -- $LINE
  ID=$1; ST=$2; CO=$3; NUM=$4
  [ "$ID" = "NONE" ] && { echo "[$((i*15))с] прогона за $SHA ещё нет"; sleep 15; continue; }
  echo "[$((i*15))с] run=$ID #$NUM status=$ST conclusion=$CO"
  [ "$ST" = "completed" ] && break
  sleep 15
done
[ "$ST" != "completed" ] && { echo "ТАЙМАУТ — прогон жив; следующий шаг: проверить позже"; exit 2; }
echo "=== ФИНАЛ run#$ID: $CO ==="

"$G/gh-get.sh" "/repos/$R/actions/runs/$ID/jobs" /tmp/jobs.json >/dev/null
python3 -c "
import json
d=json.load(open('/tmp/jobs.json'))
for j in d['jobs']:
    print('JOB', j['name'], '=>', j['conclusion'])
    for s in j['steps']:
        if s['conclusion'] not in ('success','skipped',None):
            print('   ШАГ-ПРОВАЛ:', s['name'])
"
JID=$(python3 -c "import json;print(json.load(open('/tmp/jobs.json'))['jobs'][0]['id'])")

if [ "$CO" = "failure" ]; then
  LOG=/tmp/joblog.$ID.txt
  "$G/gh-log.sh" "$JID" "$LOG" || exit 3
  grep -q "$SHA" "$LOG" || echo "ВНИМАНИЕ: sha $SHA не упоминается в логе — сомнительная свежесть"
  echo "=== ошибки компиляции ==="
  grep -o "e: file:///home/runner/work/agent-phone/agent-phone/.*" "$LOG" \
    | sed 's|file:///home/runner/work/agent-phone/agent-phone/||' | sort -u | head -40
  echo "=== падающие тесты ==="
  grep -iE "FAILED|tests completed|expected:|AssertionError" "$LOG" | head -25
  echo "=== what went wrong ==="
  grep -A6 "What went wrong" "$LOG" | head -25
else
  echo "=== артефакты ==="
  "$G/gh-get.sh" "/repos/$R/actions/runs/$ID/artifacts" /tmp/art.json >/dev/null
  python3 -c "
import json
d=json.load(open('/tmp/art.json'))
a=d.get('artifacts',[])
if not a: print('артефактов нет')
for x in a: print(x['name'], x['size_in_bytes'], 'байт  download_url=', x['archive_download_url'])
"
  echo "RUN_ID=$ID"
fi
