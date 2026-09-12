#!/bin/sh
# ci-watch.sh — ждёт финал последнего прогона и печатает вердикт + ошибки, если упал.
R=Flawl3ssss/agent-phone
RUN=""
for i in $(seq 1 40); do
  /var/minis/workspace/agent-phone/tools/gh-get.sh "/repos/$R/actions/runs?per_page=1" /tmp/run.json >/dev/null 2>&1 || true
  RUN=$(python3 -c "
import json
try: d=json.load(open('/tmp/run.json'))
except Exception: print(''); raise SystemExit
r=d['workflow_runs'][0]
print(r['id'], r['status'], r['conclusion'] or '-', r['head_sha'][:7])
" 2>/dev/null)
  set -- $RUN
  [ -z "$1" ] && { sleep 15; continue; }
  ID=$1; ST=$2; CO=$3; SHA=$4
  echo "[$((i*15))с] run=$ID sha=$SHA status=$ST conclusion=$CO"
  case "$ST" in completed) break;; esac
  sleep 15
done
[ "$ST" != "completed" ] && { echo "ТАЙМАУТ — прогон всё ещё жив, проверю позже"; exit 2; }

echo "=== ФИНАЛ: $CO (sha $SHA) ==="
/var/minis/workspace/agent-phone/tools/gh-get.sh "/repos/$R/actions/runs/$ID/jobs" /tmp/jobs.json >/dev/null
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
  curl -sL --max-time 90 -o /tmp/job.log -H "Authorization: Bearer $GH_TOKEN" \
    "https://api.github.com/repos/$R/actions/jobs/$JID/logs"
  echo "=== ошибки компиляции ==="
  grep -o "e: file:///home/runner/work/agent-phone/agent-phone/.*" /tmp/job.log \
    | sed 's|file:///home/runner/work/agent-phone/agent-phone/||' | sort -u | head -40
  echo "=== tests ==="
  grep -iE "FAILED|tests? completed|expected:|AssertionError" /tmp/job.log | head -20
  echo "=== what went wrong ==="
  grep -A6 "What went wrong" /tmp/job.log | head -25
else
  echo "=== артефакты ==="
  /var/minis/workspace/agent-phone/tools/gh-get.sh "/repos/$R/actions/runs/$ID/artifacts" /tmp/art.json >/dev/null
  python3 -c "
import json
d=json.load(open('/tmp/art.json'))
for a in d.get('artifacts',[]):
    print(a['name'], a['size_in_bytes'], 'байт  id=', a['id'])
"
fi
