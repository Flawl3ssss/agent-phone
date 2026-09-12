#!/bin/sh
# tools/fetch-artifacts.sh — докачивает артефакты в ./localrepo обходным путём (curl/HTTP/1.1).
#
# Зачем: в этой песочнице JVM-клиент Gradle рвёт TLS/HTTP/2 к dl.google.com, а curl
# забирает те же файлы стабильно. Скрипт читает список "group:artifact:version:ext"
# из файла и раскладывает по maven-путям.
#
#   sh tools/fetch-artifacts.sh tools/missing.txt
set -eu
LIST="${1:-tools/missing.txt}"
DEST="${DEST:-localrepo}"
[ -f "$LIST" ] || { echo "нет файла $LIST"; exit 1; }
GOOGLE="https://dl.google.com/dl/android/maven2"
CENTRAL="https://repo1.maven.org/maven2"

while read -r spec; do
  [ -z "$spec" ] && continue
  case "$spec" in \#*) continue;; esac
  g=$(printf '%s' "$spec" | cut -d: -f1)
  a=$(printf '%s' "$spec" | cut -d: -f2)
  v=$(printf '%s' "$spec" | cut -d: -f3)
  e=$(printf '%s' "$spec" | cut -d: -f4)
  [ -n "$e" ] || e=aar
  gp=$(printf '%s' "$g" | tr '.' '/')
  base="$g/$a/$v"
  mkdir -p "$DEST/$base"
  # сам артефакт
  f="$DEST/$base/$a-$v.$e"
  if [ ! -s "$f" ]; then
    for repo in "$GOOGLE" "$CENTRAL"; do
      url="$repo/$gp/$a/$v/$a-$v.$e"
      if curl -sfL --http1.1 --max-time 180 -o "$f" "$url" 2>/dev/null && [ -s "$f" ]; then
        echo "OK  $spec  <- $(basename "$repo")"; break
      fi
      rm -f "$f"
    done
    [ -s "$f" ] || echo "MISS $spec"
  fi
  # pom обязателен, иначе Gradle не считает артефакт разрешённым
  p="$DEST/$base/$a-$v.pom"
  if [ ! -s "$p" ]; then
    for repo in "$GOOGLE" "$CENTRAL"; do
      if curl -sfL --http1.1 --max-time 60 -o "$p" "$repo/$gp/$a/$v/$a-$v.pom" 2>/dev/null && [ -s "$p" ]; then break; fi
      rm -f "$p"
    done
    [ -s "$p" ] || echo "MISS-POM $spec"
  fi
done < "$LIST"
