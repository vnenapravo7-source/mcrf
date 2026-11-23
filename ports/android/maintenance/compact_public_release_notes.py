"""Human-requested body-only cleanup of the five currently retained releases."""
import datetime
import json
from pathlib import Path
import subprocess
import uuid

GH = 'C:/Program Files/GitHub CLI/gh.exe'
REPO = 'repos/vnenapravo7-source/mcrf'
NOTES = {
    'android-v0.1.0-preview.20261020.1': '''- Steer: правила маршрутизации, DNS, IPv6 и локальный HTTP/SOCKS5-прокси с авторизацией.
- Компактный WG/AWG 3.0/3.1: IP-правила, блокировка и DNS.
- Обновление Steer и WG/AWG из пререлиза приложения.
- Обновлены документация, обзор Android и исходники приложения и модулей.''',
    'android-v0.1.0-preview.20261019.2': '''- Исправлено автоматическое переключение VPN ↔ БС; добавлены уведомления о переходе.
- Исправлены автовыбор серверов, прямой выход и обработка IPv6; добавлена опция совместимости IPv4.
- Приложение предлагает обновление sing-box из своего пререлиза.
- В «Чертёже» струя стала постоянной, без привязки к скорости.''',
    'v1.0.11': '''- Единый интерфейс VPN, маршрутизации, WARP, Telegram WS и Zapret/ByeTube.
- Автодеплой и управление серверами.
- Дополнительные движки Windows и Android объединены в общий каталог.''',
    'v0.3.10': '''- Глобальные горячие клавиши для Zapret, WARP, Telegram WS и TUN.
- Повторный запуск открывает существующее окно приложения.
- Обновлены интерфейс, прокрутка и раскладка на небольших окнах.
- DNS отдельных правил, глобальные исключения приложений и условие «процесс И адреса».
- Улучшены голосовой фильтр Discord и переключение профилей Zapret; добавлен выбор ByeTube.
- Обновлены игровые шаблоны и генератор стратегий.''',
    'engines-2026-10-13-1': '''- Единый каталог дополнительных движков Windows и Android.
- Пакеты Windows в ZIP и модули Android в APK для разных архитектур.
- Добавлены исходники компонентов и контрольные суммы.''',
}

def api(path, body=None):
    cmd = [GH, 'api', f'{REPO}/{path}']
    if body is not None:
        cmd += ['--method', 'PATCH', '--input', '-']
    p = subprocess.run(cmd, input=None if body is None else json.dumps(body).encode(),
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return json.loads(p.stdout)

def identity(release):
    # Notes are the ONLY mutable field in this operation.
    return {k: v for k, v in release.items()
            if k not in ('body', 'updated_at', 'reactions')}

def main():
    stage = Path('D:/MCRF/Builds/Android') / ('release-notes-' + uuid.uuid4().hex)
    stage.mkdir()
    before = api('releases?per_page=100')
    (stage/'before.json').write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding='utf-8')
    live = {r['tag_name']: r for r in before}
    if set(live) != set(NOTES):
        raise RuntimeError('Release inventory changed; review it, never recreate deleted releases')
    changes = []
    for tag, notes in NOTES.items():
        current = api(f'releases/{live[tag]["id"]}')
        if identity(current) != identity(live[tag]) or current['body'] != live[tag]['body']:
            raise RuntimeError('Concurrent release change; stop before overwriting')
        if current['body'].strip() != notes.strip():
            api(f'releases/{current["id"]}', {'body': notes + '\n'})
        verified = api(f'releases/{current["id"]}')
        if identity(verified) != identity(current) or verified['body'].strip() != notes.strip():
            raise RuntimeError('Unexpected release mutation')
        changes.append({'id': current['id'], 'tag': tag, 'assets': len(current['assets']),
                        'beforeChars': len(current['body']), 'afterChars': len(verified['body'])})
    after = api('releases?per_page=100')
    if {(r['id'], r['tag_name']) for r in after} != {(r['id'], r['tag_name']) for r in before}:
        raise RuntimeError('Release inventory changed during cleanup')
    receipt = {'state': 'BODY_ONLY_UPDATED_VERIFIED', 'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'releases': changes, 'assetsChanged': False, 'createdOrDeleted': False}
    (stage/'after.json').write_text(json.dumps(after, ensure_ascii=False, indent=2), encoding='utf-8')
    (stage/'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'stage':str(stage), **receipt}, ensure_ascii=False))

if __name__ == '__main__':
    main()
