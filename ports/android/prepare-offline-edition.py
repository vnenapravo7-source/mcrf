"""Prepare immutable offline assets from already built, same-signer module APKs."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=pathlib.Path, required=True)
    parser.add_argument('--signer', required=True, help='Trusted main APK signer certificate SHA-256')
    parser.add_argument('--package', action='append', required=True, help='KIND:ABI=APK')
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    signer = args.signer.lower().replace(':', '')
    if not re.fullmatch(r'[0-9a-f]{64}', signer):
        raise ValueError('A pinned main signer is required')
    versions = sorted((args.sdk / 'build-tools').iterdir(), key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.name)))
    build_tools = versions[-1]
    aapt = build_tools / 'aapt.exe'
    apksigner = build_tools / 'apksigner.bat'
    rows = []
    sources = {}
    names = {'bs': 'org.mcrf.engines.bs', 'sing-box': 'org.mcrf.engines.singbox'}
    for spec in args.package:
        left, value = spec.split('=', 1)
        kind, abi = left.split(':', 1)
        if kind not in names or abi not in {'arm64-v8a', 'armeabi-v7a', 'x86_64'} or (kind, abi) in sources:
            raise ValueError('Invalid/duplicate offline package')
        source = pathlib.Path(value).resolve(strict=True)
        report = subprocess.check_output([str(apksigner), 'verify', '--print-certs', str(source)], text=True, encoding='utf-8')
        certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', report)
        if certs != [signer]:
            raise ValueError('Module signer does not match main APK')
        badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(source)], text=True, encoding='utf-8')
        info = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", badging)
        if not info or info[1] != names[kind]:
            raise ValueError('Wrong module package identity')
        abis = re.search(r"native-code: (.+)", badging)
        if not abis or abi not in re.findall(r"'([^']+)'", abis[1]):
            raise ValueError('Module lacks target ABI')
        rows.append({'kind': kind, 'abi': abi, 'version': info[3], 'versionCode': int(info[2]),
                     'bytes': source.stat().st_size, 'sha256': sha(source)})
        sources[kind, abi] = source
    for abi in {row['abi'] for row in rows}:
        if {row['kind'] for row in rows if row['abi'] == abi} != set(names):
            raise ValueError('Every ABI needs both BS and sing-box')
    # A fresh destination prevents accidental replacement of a protected/active build.
    args.output.mkdir(parents=True, exist_ok=False)
    folder = args.output / 'bundled-engines'
    folder.mkdir()
    for row in rows:
        destination = folder / f"{row['kind']}-{row['abi']}.apk"
        shutil.copy2(sources[row['kind'], row['abi']], destination)
        if sha(destination) != row['sha256']:
            raise ValueError('Source changed during copy')
    index = folder / 'index.json'
    index.write_text(json.dumps({'schema': 1, 'packages': rows}, ensure_ascii=False, indent=2), encoding='utf-8')
    (args.output / 'receipt.json').write_text(json.dumps({'signer': signer, 'indexSha256': sha(index),
                                                       'packages': rows}, indent=2), encoding='utf-8')
    print(f'Prepared {len(rows)} signed offline packages in {args.output}')


if __name__ == '__main__':
    main()
