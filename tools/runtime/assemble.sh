#!/bin/sh
# assemble.sh — собирает in-app рантайм (Ubuntu-бинарники + node + Kimi) в дерево APK.
#
# Запускается и локально (aarch64), и в CI (x86_64, кросс-инструменты). Результат:
#   app/src/main/jniLibs/arm64-v8a/lib<имя>.so   — исполняемые ELF и загрузчик:
#       только оттуда разрешён exec() при targetSdk >= 29 (W^X), а AGP из jniLibs
#       выносит файлы исключительно вида lib*.so — поэтому имена такие.
#   app/src/main/assets/runtime/lib/<soname>     — читаемые библиотеки под настоящими
#       soname (их не исполняют, читать в filesDir после распаковки можно).
#   app/src/main/assets/runtime/kimi/…           — замыкание npm без optional-зависимостей.
#   app/src/main/assets/runtime/MANIFEST.json    — версии + sha256 каждого файла; сверяется
#       с SPEC_VERSION на устройстве, расхождение => переустановка.
#
# Идемпотентно: скачанное лежит в .runtime-cache и повторно не тянется.
set -e

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CACHE=${CACHE:-$ROOT/.runtime-cache}
WORK=$WORK_DIR
: "${WORK:=$CACHE/work}"
: "${UBUNTU_VERSION:=24.04.4}"
: "${NODE_VERSION:=v22.23.2}"
: "${KIMI_VERSION:=0.42.0}"
: "${SPEC_VERSION:=1}"

NATIVE=$ROOT/app/src/main/jniLibs/arm64-v8a
ASSETS=$ROOT/app/src/main/assets/runtime
UB=$WORK/ubuntu
NODE_DIR=$WORK/node
KIMI=$WORK/kimi

UB_SHA=04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2
NODE_SHA=fff4078c5def658577f92c88db7db3bc0072924bfb93fe52c1e744a54e94abb8

# Исполняемые, которые должны существовать в jniLibs. Имя файла = имя в PATH.
BINS="ldr bash sh node"
# Ubuntu-утилиты для терминала: пропуски допустимы (в base-image чего-то нет),
# всё найденное уезжает отдельными ELF — busybox не нужен, и ловушка с argv[0] тоже.
TOOLS="git ssh ssh-keygen ls cat cp mv rm mkdir rmdir ln chmod chown touch echo pwd printf head tail wc
sort uniq cut tr grep sed awk find xargs stat du df tar gzip base64 md5sum sha256sum
date sleep kill ps uname whoami id vi less more top free ping wget nc"

say() { printf '%s\n' "$*"; }
die() { printf 'ОСТАНОВ: %s\n' "$*" >&2; exit 1; }

fetch() {
    # fetch <url> <sha256> <куда>
    if [ -f "$3" ] && printf '%s  %s\n' "$2" "$3" | sha256sum -c - >/dev/null 2>&1; then
        say "  в кэше: $(basename "$3")"; return 0
    fi
    rm -f "$3" "$3.part"
    i=1
    while [ $i -le 6 ]; do
        if curl -sS --connect-timeout 15 --max-time 900 -o "$3.part" "$1"; then
            if printf '%s  %s\n' "$2" "$3.part" | sha256sum -c - >/dev/null 2>&1; then
                mv "$3.part" "$3"; say "  скачано и сверено: $(basename "$3")"; return 0
            fi
            say "  ! контрольная сумма не совпала (попытка $i)"; rm -f "$3.part"
        else
            say "  ! сеть (попытка $i)"
        fi
        i=$((i + 1)); sleep 5
    done
    die "не удалось получить $1"
}

elf_needed() {
    readelf -d "$1" 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'
}

canon() {
    # Разворачивает цепочку симлинков, НЕ ВЫХОДЯ за rootfs. readlink -f так не умеет:
    # awk -> /etc/alternatives/awk есть абсолютная ссылка, и она уводит в файловую
    # систему хоста (там ничего нет) вместо ubuntu-овского mawk.
    p="$1"; n=0
    while [ $n -lt 10 ]; do
        n=$((n + 1))
        if [ -L "$p" ]; then
            t=$(readlink "$p")
            case "$t" in
                /*) p="$UB$t" ;;
                *) p=$(dirname "$p")/$t ;;
            esac
            continue
        fi
        if [ -f "$p" ]; then printf '%s\n' "$p"; return 0; fi
        return 1
    done
    return 1
}

resolve_lib() {
    c=$(find "$UB" -name "$1" -type l 2>/dev/null | head -1)
    [ -z "$c" ] && c=$(find "$UB" -name "$1" -type f 2>/dev/null | head -1)
    [ -n "$c" ] && canon "$c"
}

resolve_bin() {
    # -e НЕ годится: цель ubuntu-овских симлинков абсолютна (/etc/alternatives/...),
    # и без chroot она смотрит в файловую систему хоста, где её нет. Проверяем сам
    # факт существования записи (-L) наравне с разрешённым файлом (-e).
    for d in bin usr/bin sbin usr/sbin; do
        if [ -L "$UB/$d/$1" ] || [ -e "$UB/$d/$1" ]; then
            if canon "$UB/$d/$1"; then return 0; fi
        fi
    done
    return 1
}

say "== 1. артефакты"
mkdir -p "$CACHE" "$WORK" "$NATIVE" "$ASSETS/lib"
fetch "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_VERSION/release/ubuntu-base-$UBUNTU_VERSION-base-arm64.tar.gz" \
      "$UB_SHA" "$CACHE/ubuntu-base.tar.gz"
fetch "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz" \
      "$NODE_SHA" "$CACHE/node.tar.xz"

say "== 2. распаковка"
if [ ! -d "$UB/bin" ]; then rm -rf "$UB"; mkdir -p "$UB"; tar -xzf "$CACHE/ubuntu-base.tar.gz" -C "$UB"; fi
if [ ! -f "$NODE_DIR/bin/node" ]; then rm -rf "$NODE_DIR"; mkdir -p "$NODE_DIR"; tar -xJf "$CACHE/node.tar.xz" -C "$NODE_DIR" --strip-components=1; fi

say "== 3. node: strip (экономия ~18 МБ) и копирование в jniLibs"
STRIPPER=$(command -v aarch64-linux-gnu-strip || command -v strip)
cp "$NODE_DIR/bin/node" "$NODE_DIR/bin/node.stripped"
if [ -n "$STRIPPER" ]; then
    chmod u+w "$NODE_DIR/bin/node.stripped"
    if "$STRIPPER" --strip-unneeded "$NODE_DIR/bin/node.stripped" 2>/dev/null; then
        say "  strip: $(wc -c < "$NODE_DIR/bin/node") -> $(wc -c < "$NODE_DIR/bin/node.stripped") Б"
    else
        say "  strip не сумел (кросс-окружение?) — беру исходный"
    fi
fi
cp "$NODE_DIR/bin/node.stripped" "$NATIVE/libnode.so"

say "== 4. исполняемые из ubuntu-base"
LDR=$(resolve_lib ld-linux-aarch64.so.1)
[ -n "$LDR" ] || die "в rootfs нет ld-linux-aarch64.so.1"
cp "$LDR" "$NATIVE/libldr.so"
for b in $BINS $TOOLS; do
    [ "$b" = "ldr" ] && continue
    [ "$b" = "node" ] && continue
    src=$(resolve_bin "$b" || true)
    if [ -z "$src" ] || [ ! -f "$src" ]; then say "  $b — нет в base, пропускаю"; continue; fi
    cp "$src" "$NATIVE/lib$b.so"
done

say "== 5. библиотеки: транзитивное замыкание по NEEDED"
SEEN=""; QUEUE=""
for f in $NATIVE/*.so; do QUEUE="$QUEUE $(elf_needed "$f" | tr '\n' ' ')"; done
while [ -n "$QUEUE" ]; do
    lib=""; rest=""
    for q in $QUEUE; do
        if [ -z "$lib" ] && [ -n "$q" ]; then lib="$q"; else rest="$rest $q"; fi
    done
    QUEUE="$rest"
    [ -n "$lib" ] || continue
    hit=0
    for s in $SEEN; do
        if [ "$s" = "$lib" ]; then hit=1; fi
    done
    if [ $hit -eq 1 ]; then continue; fi
    SEEN="$SEEN $lib"
    path=$(resolve_lib "$lib")
    if [ -z "$path" ] || [ ! -f "$path" ]; then say "  ! $lib не найдена в rootfs"; continue; fi
    cp "$path" "$ASSETS/lib/$lib"
    say "  + $lib ($(wc -c < "$path") Б)"
    QUEUE="$QUEUE $(elf_needed "$path" | tr '\n' ' ')"
done
# Загрузчик нужен и как файл, и как soname внутри library-path.
cp "$LDR" "$ASSETS/lib/ld-linux-aarch64.so.1"

say "== 6. замыкание Kimi без optional-зависимостей"
if [ ! -d "$KIMI/node_modules/@moonshot-ai/kimi-code" ]; then
    rm -rf "$KIMI"; mkdir -p "$KIMI"
    printf '{"name":"agent-phone-runtime","private":true}\n' > "$KIMI/package.json"
    # --omit=optional отрезает node-pty и clipboard: они нативные и нам не нужны.
    # --ignore-scripts: postinstall kimi только переименовывает чужие шимы, в контейнере
    # это лишнее действие (и ровно тот класс «постинсталляционных чудес», который нам не нужен).
    (cd "$KIMI" && npm install --omit=optional --ignore-scripts --no-audit --no-fund \
        "@moonshot-ai/kimi-code@$KIMI_VERSION") > "$CACHE/npm.log" 2>&1 \
        || die "npm install упал, см. $CACHE/npm.log"
fi
rm -rf "$ASSETS/kimi"
mkdir -p "$ASSETS/kimi/node_modules"
# dist-web (34 МБ) и native/ (darwin+win32) выкинуты: проверено, что `kimi acp`
# отвечает живым handshake и без них (docs/RUNTIME-PLAN.md, п.10).
for pkg in "$KIMI/node_modules/"*; do
    case "$pkg" in
        *@moonshot-ai) cp -r "$pkg" "$ASSETS/kimi/node_modules/" ;;
        *) bn=$(basename "$pkg")
           case "$bn" in node-addon-api|node-pty) say "  пропускаю $bn";; *) cp -r "$pkg" "$ASSETS/kimi/node_modules/";; esac ;;
    esac
done
KDIR="$ASSETS/kimi/node_modules/@moonshot-ai/kimi-code"
rm -rf "$KDIR/dist-web" "$KDIR/native"
chmod 644 "$KDIR/dist/main.mjs" 2>/dev/null || true

say "== 7. MANIFEST"
python3 - "$ROOT" "$SPEC_VERSION" "$UBUNTU_VERSION" "$NODE_VERSION" "$KIMI_VERSION" <<'PY'
import hashlib, json, os, sys
root, spec, ub, node, kimi = sys.argv[1:6]
def rows(d, base):
    out = []
    for dirpath, _, names in os.walk(d):
        for n in sorted(names):
            p = os.path.join(dirpath, n)
            h = hashlib.sha256(open(p, 'rb').read()).hexdigest()
            out.append({"path": os.path.relpath(p, base).replace(os.sep, "/"),
                        "sha256": h, "bytes": os.path.getsize(p)})
    return sorted(out, key=lambda r: r["path"])
nat = os.path.join(root, "app/src/main/jniLibs/arm64-v8a")
lib = os.path.join(root, "app/src/main/assets/runtime/lib")
kim = os.path.join(root, "app/src/main/assets/runtime/kimi")
man = {"spec_version": spec, "ubuntu_base": ub, "node": node, "kimi_version": kimi,
       "native": rows(nat, root), "runtime_lib": rows(lib, root), "kimi_files": rows(kim, root)}
open(os.path.join(root, "app/src/main/assets/runtime/MANIFEST.json"), "w").write(
    json.dumps(man, indent=1, sort_keys=True) + "\n")
for k in ("native", "runtime_lib"):
    print(f"  {k}: {len(man[k])} файлов, {sum(f['bytes'] for f in man[k]) / 1e6:.1f} МБ")
PY

say "== 8. размер"
du -sh "$NATIVE" "$ASSETS" 2>/dev/null
say "готово: jniLibs + assets собраны; спецификация имён — docs/RUNTIME-PLAN.md"
