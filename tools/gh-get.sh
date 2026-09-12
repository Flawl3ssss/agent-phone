#!/bin/sh
# gh-get <path-with-query> [out-file]
# Ретраит плавающий DNS/TLS в песочнице, пишет JSON в файл, печатает http-код.
PATH_Q="$1"
OUT="${2:-/tmp/gh.json}"
i=1
while [ $i -le 6 ]; do
  CODE=$(curl -s --max-time 45 -o "$OUT" -w "%{http_code}" \
    -H "Authorization: Bearer $GH_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com$PATH_Q")
  if [ "$CODE" != "000" ] && [ -s "$OUT" ]; then
    echo "http=$CODE attempts=$i -> $OUT"
    exit 0
  fi
  i=$((i+1)); sleep 5
done
echo "http=${CODE:-000} attempts=$((i-1)) ФАССЕД"
exit 1
