#!/usr/bin/env python3
"""Проверка текста исходников. В CI — до компиляции, стоит доли секунды.

Две ловушки, обе — мои реальные баги этой сессии: в русские комментарии вставались
CJK-символы и латинские огрызки («на部分 устройствах», «иobtaining ответ»). Компилятор
на такое не жалуется (это комментарий), review глазами — тоже пропускает, потому что
текст выглядит «почти нормально». Ловится только автоматом.
"""
import pathlib
import re
import sys

CJK = re.compile(r'[\u3000-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff01-\uff60]')
MIXED = re.compile(r'[а-яёА-ЯЁ][a-zA-Z]|[a-zA-Z][а-яёА-ЯЁ]')
# Файлы/строки, где смешение алфавитов легально: экранирования (\nКирилл), домены, URL.
ALLOW = re.compile(r'\\[ntrb$]\S*[а-яё]|\bhttps?://|@|\.(com|ru|ai|dev|io)\b')

# Имя теста в backticks — это имя метода JVM: точки, скобки и слэши в нём компилируются
# в «Name contains illegal characters» и валят прогон целиком. Дешевле поймать здесь,
# чем тратить цикл CI (реальный случай: `… + main.mjs + acp` в названии теста).
FUN_NAME = re.compile(r'fun `([^`]*)`')
ILLEGAL_IN_NAME = re.compile(r'[.;:\[\]<>{}]')

def main(root: str) -> int:
    bad = 0
    for f in sorted(pathlib.Path(root).rglob('*.kt')) + sorted(pathlib.Path(root).rglob('*.kts')):
        if 'build' in f.parts:
            continue
        for i, line in enumerate(f.read_text(encoding='utf-8').splitlines(), 1):
            hits = []
            if CJK.search(line):
                hits.append('CJK')
            if MIXED.search(line) and not ALLOW.search(line):
                hits.append('mixed-alphabet')
            m = FUN_NAME.search(line)
            if m and ILLEGAL_IN_NAME.search(m.group(1)):
                hits.append('illegal chars in test name')
            if hits:
                print(f'{f}:{i}: [{",".join(hits)}] {line.strip()[:110]}')
                bad += 1
    if bad:
        print(f'НАЙДЕНО строк с посторонними символами: {bad}', file=sys.stderr)
        return 1
    print('текст чист')
    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else 'app'))
