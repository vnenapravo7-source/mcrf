"""Prepare/verify the exact recorded raw-port overlay without changing baseline trees.

Candidate provenance only: this does not enable an Android module capability.
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess

BASE = '3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d'
SUBMODULES = {
    'submodules/gvisor': '117243aa02fa2915cb53d1d4549bfca3a3ebf238',
    'submodules/sing-tun': '6f56eca9cb2e81d1c233b6104618ff30500e9f39',
    'submodules/utls': '59e89bb121d8cc7a0a1c87a930864122b5685d00',
    'submodules/wireguard-go': '64065e6826ad82aa28429dd829c1c65f29b7f6c9',
}
OVERLAYS = {
    'p3': ('p3-native-packet-port', ['with_lx_raw_packet', 'with_lx_raw_packet_port']),
    'p4': ('p4-native-carrier-stack', ['with_lx_raw_packet', 'with_lx_raw_packet_port', 'with_lx_carrier_stack']),
}
OVERLAY = pathlib.Path(__file__).resolve().parent.parent / 'primary-cores/singbox/native-patches/p3-native-packet-port'
TAGS = OVERLAYS['p3'][1]


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.PIPE).decode('utf-8').rstrip('\r\n')


def digest(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError('Regular immutable source file required')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inside(root, name):
    rel = pathlib.PurePosixPath(name)
    if rel.is_absolute() or '..' in rel.parts or '\\' in name or ':' in name:
        raise ValueError('Unsafe overlay path')
    result = root.joinpath(*rel.parts)
    if not result.resolve().is_relative_to(root.resolve()):
        raise ValueError('Overlay path escapes its root')
    return result


def manifest(expected, evidence):
    proof = OVERLAY / 'overlay.json'
    if digest(proof) != expected:
        raise ValueError('Overlay receipt SHA mismatch')
    doc = json.loads(proof.read_text(encoding='utf-8'))
    if doc['baseline'] != BASE or doc['state'] != 'STAGED_NOT_PRODUCTION_INTEGRATED' or doc['published'] or doc['productionIntegration']:
        raise ValueError('Only the recorded non-production candidate is accepted')
    if doc['tags'] != TAGS:
        raise ValueError('Unexpected candidate tags')
    rows = doc['files']
    if not rows or len({r['file'] for r in rows}) != len(rows):
        raise ValueError('Duplicate or empty source closure')
    for row in rows:
        if digest(inside(OVERLAY / 'source', row['file'])) != row['sha256']:
            raise ValueError('Canonical overlay source changed')
    for row in doc['tests']:
        if digest(inside(evidence, row['file'])) != row['sha256']:
            raise ValueError('Executed evidence changed')
    return doc


def check_baseline(root):
    if git(root, 'rev-parse', 'HEAD') != BASE:
        raise ValueError('Unexpected native baseline')
    for path, commit in SUBMODULES.items():
        sub = root / path
        if git(sub, 'rev-parse', 'HEAD') != commit or git(sub, 'status', '--porcelain', '--untracked-files=all'):
            raise ValueError('Native submodule missing, changed or unrecorded')


def verify(root, expected, evidence):
    doc = manifest(expected, evidence)
    check_baseline(root)
    allowed = {row['file'] for row in doc['files']}
    # All changed and new source paths must belong to the exact recorded closure.
    dirty = git(root, 'status', '--porcelain', '--untracked-files=all').splitlines()
    changed = set()
    for line in dirty:
        if line[:2] not in (' M', '??') or line[3:] not in allowed:
            raise ValueError('Unrecorded native source change: ' + line)
        changed.add(line[3:])
    if changed != allowed:
        raise ValueError('Native changed-path closure does not match overlay')
    for row in doc['files']:
        path = inside(root, row['file'])
        if digest(path) != row['sha256']:
            raise ValueError('Prepared native source changed: ' + row['file'])
        tracked = git(root, 'ls-files', '--', row['file'])
        if row['baselineGitBlob'] is None:
            if tracked:
                raise ValueError('New source replaces an existing baseline file')
        elif not tracked or git(root, 'rev-parse', 'HEAD:' + row['file']) != row['baselineGitBlob']:
            raise ValueError('Overlay baseline blob mismatch')
    return {'overlaySha256': expected, 'files': len(allowed), 'baseline': BASE,
            'tags': doc['tags'], 'productionIntegration': False}


def prepare(baseline, output, expected, evidence):
    doc = manifest(expected, evidence)
    check_baseline(baseline)
    # Clone only committed objects. Retained, untracked build files are never
    # copied; reject tracked/index changes rather than touching those files.
    if git(baseline, 'diff', 'HEAD', '--name-only', '--ignore-submodules=all'):
        raise ValueError('Unchanged retained baseline required')
    if output.exists():
        raise ValueError('Fresh candidate directory required')
    subprocess.run(['git', 'clone', '--shared', '--no-checkout', str(baseline), str(output)], check=True)
    subprocess.run(['git', '-C', str(output), 'checkout', '--detach', BASE], check=True)
    for path, commit in SUBMODULES.items():
        destination = output / path
        subprocess.run(['git', 'clone', '--shared', '--no-checkout', str(baseline / path), str(destination)], check=True)
        subprocess.run(['git', '-C', str(destination), 'checkout', '--detach', commit], check=True)
    for row in doc['files']:
        destination = inside(output, row['file'])
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(inside(OVERLAY / 'source', row['file']), destination)
    return verify(output, expected, evidence)


def main():
    global OVERLAY, TAGS
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['prepare', 'verify'])
    parser.add_argument('--source', type=pathlib.Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path)
    parser.add_argument('--overlay-kind', choices=OVERLAYS, default='p3')
    args = parser.parse_args()
    directory, TAGS = OVERLAYS[args.overlay_kind]
    OVERLAY = pathlib.Path(__file__).resolve().parent.parent / 'primary-cores/singbox/native-patches' / directory
    if args.mode == 'prepare':
        if args.output is None:
            parser.error('--output required for prepare')
        result = prepare(args.source.resolve(), args.output.resolve(), args.sha256, args.evidence.resolve())
    else:
        result = verify(args.source.resolve(), args.sha256, args.evidence.resolve())
    print(json.dumps(result))


if __name__ == '__main__':
    main()
