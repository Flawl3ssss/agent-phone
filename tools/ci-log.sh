#!/bin/sh
# Лог последнего прогона для sha. Двухшаговость обязательна: /logs отвечает 302
# на временный SAS-URL, который сам по себе живёт ~10 минут и не берётся с -L.
sha=$(git rev-parse "$1^{commit}" 2>/dev/null)
[ -n "$sha" ] || sha=$1
api=https://api.github.com/repos/Flawl3ssss/agent-phone
H="-H Authorization:Bearer\ $GH_TOKEN"
[ -n "$GH_TOKEN" ] || { echo "нет GH_TOKEN"; exit 2; }
run=$($api_run_id)
id=$(curl -sS --retry 4 --connect-timeout 20 --max-time 60 -H "Authorization: Bearer $GH_TOKEN" \
  "$api/actions/runs?head_sha=$sha&per_page=1" | python3 -c 'import json,sys; r=json.load(sys.stdin).get("workflow_runs") or []; print(r[0]["id"] if r else "")')
[ -n "$id" ] || { echo "прогона для $sha нет"; exit 1; }
echo "run=$id"
job=$(curl -sS --retry 4 --connect-timeout 20 --max-time 60 -H "Authorization: Bearer $GH_TOKEN" \
  "$api/actions/runs/$id/jobs" | python3 -c '
import json,sys
for j in json.load(sys.stdin).get("jobs",[]):
    if j.get("conclusion")=="failure":
        print(j["id"]); break
')
[ -n "$job" ] || { echo "упавших джобов нет"; exit 1; }
rm -f /tmp/ci.log
loc=$(curl -sS -o /dev/null -w '%{redirect_url}' --retry 4 --connect-timeout 20 --max-time 60 \
  -H "Authorization: Bearer $GH_TOKEN" "$api/actions/jobs/$job/logs")
[ -n "$loc" ] || { echo "редиректа нет"; exit 1; }
curl -sS --connect-timeout 20 --max-time 120 "$loc" -o /tmp/ci.log && echo "лог: $(wc -l </tmp/ci.log) строк"
