# In-app runtime: Ubuntu и Kimi внутри одного APK

Запрос: всё в одном приложении — встроенный терминал, автоматическая установка Ubuntu и Kimi.
Никакого внешнего Termux и второго процесса-моста.

Ниже — то, что **проверено прогоном на этом железе 13.09.2026**, а не прочитано в документации.
Каждый пункт переживает смену кода, поэтому записан как факт, а не как гипотеза.

## 1. Проверенные факты

| # | Факт | Чем доказано |
|---|---|---|
| 1 | SEA-бинарь Kimi (180 МБ) не нужен: `@moonshot-ai/kimi-code@0.42.0` — чистый JS, `dist/main.mjs` 21 МБ, `engines.node >= 22.19.0` | `npm i --ignore-scripts`, запуск `node dist/main.mjs --version` → `0.42.0` |
| 2 | Нативные зависимости опциональны: `node-pty`, `@mariozechner/clipboard` в `optionalDependencies` | `package.json`; CLI работает без них |
| 3 | `postinstall.mjs` ничего не скачивает — он только переименовывает шим старого Python-Kimi | чтение `scripts/postinstall.mjs` |
| 4 | glibc-ный node исполняется на системе **без glibc** через встроенный загрузчик: `ld-linux-aarch64.so.1 --library-path <dirs> node` → v22.23.2 | прогон в musl-песочнице |
| 5 | Прямой `execve` glibc-ELF падает: `PT_INTERP = /lib/ld-linux-aarch64.so.1`, которого нет | `readelf -lW`, spawn из-под node → Command failed |
| 6 | `bash 5.2.21`, `coreutils 9.4`, `grep 3.11` из ubuntu-base работают через тот же загрузчик | прогон обёрток |
| 7 | Дочерние процессы node (`node`, `sh` через PATH) работают, если PATH указывает на обёртки | прогон: `node OK: v22.23.2`, `sh OK: Linux` |
| 8 | ACP-хендшейке живого Kimi: `protocolVersion 1`, `loadSession true`, `image true`, **`audio false`**, `embeddedContext true`, `mcp.http/sse true`, `auth.logout`, `authMethods=[{id:login,type:terminal,args:["--login"]}]`, `agentInfo "Kimi Code CLI" 0.42.0`; `session/new` без токена → `-32000 Authentication required` | живой зонд `probe.mjs` |
| 9 | `kimi acp --help` содержит `--region mainland-cn\|global` (в доке этого не было) | прогон |
| 10 | Trim-набор Kimi = 23 МБ без `dist-web` (34 МБ) и без `native/` (darwin/win32); хендшейк проходит | прогон trimmed-копии |
| 11 | Шебанг `main.mjs` — `#!/usr/bin/env node`, на Android такого пути нет → запускать строго `node <абсолютный путь>/main.mjs` | `head -1` |
| 12 | node нужно 7 библиотек, весь glibc-стек — **2,7 МБ**; `strip -s` бинаря node: 122 → 104 МБ, работает | `readelf -d`, `strip` |
| 13 | **W^X**: приложение с `targetSdk >= 29` не может `exec()` файлы из своего домашнего каталога, но может из read-only `/data/app/.../lib/arm64/` (`nativeLibraryDir`) при `android:extractNativeLibs="true"` | termux/termux-app#1072 + wiki «Termux and Android 10» |
| 14 | Собранный payload (файлы, которые поедут в APK) поднимает живой Kimi: `libldr.so --library-path assets/runtime/lib libnode.so …/main.mjs acp` → `initialize` с `Kimi Code CLI 0.42.0`, `audio:false`, `authMethods.args=["--login"]`; `session/new` без токена → `-32000 Authentication required` | `tools/runtime/payload-probe.mjs` |
| 15 | Цена раскладки: jniLibs 110 МБ + assets 38 МБ → **~53 МБ в APK** после deflate; 48 исполняемых ELF | `assemble.sh`, замеры |
| 16 | В `ubuntu-base` НЕТ `git`, `ssh`, `vi`, `less`, `wget`, `nc`, `ping` — минимальный образ. Для полноценного терминала их надо брать отдельными `.deb` (следующий шаг, см. §5) | прогон сборщика |
| 17 | `readlink -f` и `-e` не годятся для поиска файлов внутри rootfs: symlink-цели там абсолютны (`/etc/alternatives/awk`), без chroot они смотрят в файловую систему хоста. Нужен свой канонизатор с перепривязкой к корню + проверка `-L` наравне с `-e` (из-за этого `awk` сначала «пропал») | трассировка `sh -x` |
| 18 | AGP выносит из `jniLibs/<abi>/` только файлы вида `lib*.so` → исполняемые ELF храним как `libnode.so`, `libldr.so`, `libbash.so`; имена с soname (`libc.so.6`) копируем в `filesDir/runtime/lib` — **читать** там разрешено | то же issue + практика |

Артефакты и пины:

- `ubuntu-base-24.04.4-base-arm64.tar.gz` — 29 870 567 Б,
  sha256 `04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2`
- `node-v22.23.2-linux-arm64.tar.xz` — 30 246 708 Б,
  sha256 `fff4078c5def658577f92c88db7db3bc0072924bfb93fe52c1e744a54e94abb8`
- `kimi-code-0.42.0.tgz` — integrity
  `sha512-WNm2/j/7lVqK27379wwWlFrYpg6qpe20kTE8cA0fZLcrbHT2msM54MPDKwA53F6uGrKqPqUBqgX8O7BqTfCc3Q==`

## 2. Раскладка

```
nativeLibraryDir/            (apk_data_file, exec разрешён)
  libldr.so                  = ld-linux-aarch64.so.1
  libnode.so                 = node ( stripped )
  libbash.so                 = Ubuntu bash
  libls.so, libcat.so, …     = ubuntu-утилиты, по одной на команду (48 файлов)
  libkexec.so                = наш Bionic-диспетчер (см. §3)
  libagentpty.so             = PTY для терминала
filesDir/runtime/
  lib/                       soname-файлы: libc.so.6, libm.so.6, libstdc++.so.6, libtinfo.so.6, ...
  bin/                       симлинки sh, ls, cat, grep, node → ../libkexec.so (для PATH)
  kimi/node_modules/…        main.mjs + qrcode/ws/dijkstrajs/pngjs/yargs
  home/  tmp/  work/         HOME / TMPDIR / рабочая директория сессии
  READY                      версия спеки: смена пина => переустановка рантайма
```

## 3. Диспетчер `libkexec.so`

Node спавнит дочерние команды **по имени через PATH** (это и есть «shell-тулза» Kimi).
Имя должно существовать в PATH, а исполнить glibc-ELF напрямую нельзя (п.5).
Отсюда единственная деталь, требующая нативного кода:

1. `basename(argv[0])` → таблица соответствия (`sh`→busybox, `bash`→bash, `node`→node, `ls`→busybox…).
2. `execv(nativeDir/libldr.so, ["ld.so", "--library-path", <filesDir/runtime/lib>, <target>, …])`.
3. Каждая утилита — отдельный ELF (`libls.so`, `libgrep.so`, `libawk.so` …), поэтому
   хитрости с `argv[0]` из busybox не нужны: `ld.so` может спокойно переустанавливать
   `argv[0]` на путь цели. Busybox из плана исключён сознательно.

Симлинки в `filesDir/runtime/bin` безопасны: SELinux проверяет метку целевого файла
(`apk_data_file`), а не ссылки. Это утверждение — единственное, что останется проверить
на устройстве; для этого и существует `doctor`-шаг (см. §5).

## 4. Два режима исполнения — общая абстракция

| | **LOADER** (по умолчанию) | **PROOT** (опция) |
|---|---|---|
| targetSdk | 35 (можно в Google Play) | ≤ 28 (только сайдлоад) |
| Что даёт | Ubuntu-бинарники поверх Android-файловой системы | настоящая Ubuntu: `apt`, `/etc`, ЧСВ |
| ptrace | не нужен | нужен (Xiaomi/HyperOS это умеет, но рискуют OEM-политики) |
| Kimi | работает ✓ | работает ✓ |
| `apt install` | ✗ | ✓ |

`RuntimeLayout` намеренно не выбирает режим: `agentCommand()` собирает и тот, и другой.

## 5. Порядок сборки

1. ✅ `RuntimeSpec` + `RuntimeLayout` — чистая логика, JVM-тесты (прогон #27 зелёный).
1. ⚠️ Расхождение, которое надо убрать следующим коммитом: `RuntimeSpec.BUSYBOX_APPLETS`
   и `Dispatch.prefix` устарели — источник истины должен быть `MANIFEST.json`,
   который генерирует `assemble.sh`, иначе спецификация в Kotlin и дерево в APK
   разъедутся (половина контракта не проверена).
2. `tools/runtime/assemble.sh` — собирает `jniLibs/arm64-v8a/*` и payload из pinned-архивов;
   запускается и локально, и в CI.
3. NDK: `libkexec.so` (диспетчер) и `libagentpty.so` (`forkpty` + `TIOCSPTLCK`), сборка в CI.
4. `RuntimeInstaller` — распаковка, verification sha256, симлинки, `READY`; прогресс в UI.
5. Терминал: `xterm.js` в WebView + мост к PTY; вкладка «Агент» остаётся ACP-клиентом
   поверх **того же** рантайма (сокет-мост Termux больше не нужен).
6. `Doctor`- экран: ровно те проверки, которые нельзя доказать без устройства
   (exec из `nativeLibraryDir`, симлинки, `/dev/ptmx`, `kimi acp --login`, `--region`).


## 6. Решения, принятые 13.09 (по просьбе «решай сама»)

1. **git/vim/less — да.** Без git кодинг-агент в терминале бесполезен. Берём `.deb`-ами
   из ubuntu-ports: сборщик скачивает индекс `Packages.xz`, разворачивает зависимости
   и распаковывает пакеты в то же дерево, после чего замыкание `NEEDED` подхватывает
   новые библиотеки автоматически. Это НЕ apt: список пакетов закрыт и зафиксирован.
2. **Всё внутри APK** (~53 МБ после deflate). Нулевая настройка, работает офлайн,
   не зависит от доступности зеркала в момент первого запуска.
3. **Никакого PRoot и apt.** Настоящий chroot потребовал бы ptrace и `targetSdk <= 28`
   (иначе W^X блокирует exec из `filesDir`) — это ломает установку на Xiaomi-прошивках
   и закрывает путь в Play. Вместо «Ubuntu как системы» — Ubuntu как набор бинарников.

## 7. Что показал прогон сборщика (payload spec 2)

- 48 исполняемых ELF (110,5 МБ), 21 библиотека (14,7 МБ), 330 файлов замыкания Kimi,
  бандл корневых сертификатов (121) — всего 148,9 МБ на диске.
- **node не читает `SSL_CERT_FILE`**: с `SSL_CERT_FILE=/nonexistent` fetch даёт HTTP 200 —
  у официального node встроенный магазин Mozilla. Сертификаты нужны только `git`/`curl`,
  поэтому `missingPieces()` требует бандл лишь когда в наборе есть git: ложная тревога
  в диагностике хуже молчания.
- **`git`, `ca-certificates` в `ubuntu-base` отсутствуют** — их приносит `.deb`-шаг (п.1).
- Единственный источник истины по составу — `runtime/MANIFEST.json` (копируется в git),
  `MANIFEST.json` в assets читает установщик; в Kotlin остались только версии и минимум.
  Кросс-тест `реальный манифест из сборки соответствует kotlin-спецификации` сверяет
  константы `assemble.sh` и `RuntimeSpec` — разъезд имён падает тестом, а не телефоном.
