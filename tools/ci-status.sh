#!/bin/sh
# Опрос прогонов CI. Дисциплина: файл всегда удаляется перед загрузкой, HTTP-код
# проверяется, sha сверяется с ожидаемой — иначе «успешный» отчёт оказывается
# чужим прошлым прогоном (уже обжигалась).
EXPECT=$1
URL="https://api.github.com/repos/Flawl3ssss/agent-phone/actions/runs?per_page=6"
for i in 1 2 3 4 5 6; do
  rm -f /tmp/runs.json
  code=$(curl -sS --connect-timeout 12 --max-time 40 -w '%{http_code}' -o /tmp/runs.json "$URL" 2>/tmp/c.err)
  if [ "$code" = "200" ] && [ -s /tmp/runs.json ]; then break; fi
  echo "попытка $i: http=$code $(head -c 60 /tmp/c.err 2>/dev/null | tr '\n' ' ')"
  sleep 6
done
[ -s /tmp/runs.json ] || { echo "API недоступно"; exit 1; }
python3 - "$EXPECT" <<'PY'
import json, sys
exp = sys.argv[1] if len(sys.argv) > 1 else ''
d = json.load(open('/tmp/runs.json'))
rows = []
for r in d.get('workflow_runs', []):
    mark = ' <== мой sha' if r['head_sha'][:7] == exp else ''
    rows.append(f"{r['name']:<12} #{r['run_number']:<4} {r['status']:<12} {str(r.get('conclusion')):<10} {r['head_sha'][:7]}{mark}")
print('\n'.join(rows[:6]))
if exp and not any(r['head_sha'][:7] == exp for r in d.get('workflow_runs', [])):
    print(f"ВНИМАНИЕ: прогона на {exp} в списке нет (ещё не создан или не дошёл)")
PY
