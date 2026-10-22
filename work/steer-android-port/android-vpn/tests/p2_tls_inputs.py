"""Source-bound reuse of unchanged P049 TLS inputs in the whole P2 producer.

Not an entrypoint, compiler or test runner. The caller's ALL-P2 gate applies.
Reusing a compiled helper is NOT Android/runtime acceptance or an engine swap.
"""
import json
import hashlib
import pathlib
import shutil
import subprocess
import os
import re
import uuid


def used_module_proof(root, origin, receipt, plain, sha):
    """Do not confuse unused go.mod/go.sum entries with linked source changes.
    Prove MVS still selects every ACTUALLY LINKED module/version/checksum in
    both original Android ELFs. Unknown replacements/directives are not ignored.
    No helper compilation, TLS test repetition or canonical module edit."""
    canonical = root / 'ports/android/transports/steer-tls49'
    mod = (canonical / 'go.mod').read_text('utf-8')
    if re.search(r'(?m)^\s*(replace|exclude|retract|toolchain|godebug|tool)\b', mod):
        raise ValueError('New module directives require a fresh reviewed TLS build')
    stage = pathlib.Path('D:/MCRF/Builds/Android') / ('p2-tls-metadata-' + uuid.uuid4().hex)
    stage.mkdir()
    pins = {}
    for name in ('go.mod', 'go.sum'):
        path = plain(canonical / name, root); pins[name] = sha(path)
        shutil.copyfile(path, stage / ('canonical-' + name))
        shutil.copyfile(path, stage / name)
    go = root / 'work/toolchains/go1.26.8/go/bin/go.exe'
    env = dict(os.environ, GOPATH=str(root / 'work/toolchains/go-home'), GOPROXY='off',
               GOSUMDB='off', GOTOOLCHAIN='local', GOWORK='off', GOENV='off', GOFLAGS='')
    def run(*args):
        result = subprocess.run([str(go), *args], cwd=stage, env=env, capture_output=True, text=True, timeout=40)
        count = len(list(stage.glob('metadata-command-*.txt')))
        (stage / ('metadata-command-' + str(count) + '.txt')).write_text(result.stdout + result.stderr, encoding='utf-8')
        if result.returncode: raise ValueError('Readonly TLS module metadata proof failed; no dependency fallback')
        return result.stdout
    run('mod', 'edit', '-replace=github.com/refraction-networking/utls=' + (origin / 'tls-source/utls').as_posix())
    # Query precisely the four linked modules plus main, not every unlinked
    # optional/test dependency (which can be absent from the offline cache).
    graph = run('list', '-mod=readonly', '-m', '-json', 'mcrf.local/steertls49',
                'github.com/cloudflare/circl', 'github.com/refraction-networking/utls',
                'golang.org/x/crypto', 'golang.org/x/sys')
    (stage / 'selected-module-graph.txt').write_text(graph, encoding='utf-8')
    modules = {}; rest = graph.strip(); decoder = json.JSONDecoder()
    while rest:
        row, end = decoder.raw_decode(rest); modules[row['Path']] = row; rest = rest[end:].strip()
    main = [row for row in modules.values() if row.get('Main')]
    if len(main) != 1 or main[0]['Path'] != 'mcrf.local/steertls49' or main[0].get('GoVersion') != '1.26.0':
        raise ValueError('TLS main module/build language identity changed')
    sums = set((stage / 'canonical-go.sum').read_text('utf-8').splitlines())
    linked = {}
    for abi, row in receipt['abis'].items():
        binary = plain(pathlib.Path(row['path']), origin)
        if sha(binary) != row['sha256']: raise ValueError('Original linked TLS ELF changed')
        info = run('version', '-m', str(binary)); deps = []
        (stage / ('android-buildinfo-' + abi + '.txt')).write_text(info, encoding='utf-8')
        if ': go1.26.8\n' not in info or '\tpath\tmcrf.local/steertls49\n' not in info:
            raise ValueError('Exact original TLS build identity required')
        for line in info.splitlines():
            parts = line.split()
            if not parts or parts[0] != 'dep': continue
            if len(parts) not in (3, 4): raise ValueError('Unknown linked module metadata')
            name, version = parts[1:3]; selected = modules.get(name)
            if selected is None or selected.get('Version') != version:
                raise ValueError('A linked TLS dependency version really changed; fresh build required')
            if name == 'github.com/refraction-networking/utls':
                if pathlib.Path(selected.get('Replace', {}).get('Dir', '')).resolve() != (origin / 'tls-source/utls').resolve():
                    raise ValueError('Private TLS source replacement changed')
            elif (len(parts) != 4 or selected.get('Sum') != parts[3] or
                  ' '.join(parts[1:]) not in sums or selected.get('Replace')):
                raise ValueError('A linked TLS dependency checksum/source really changed')
            deps.append(parts[1:])
        if len(deps) != 4: raise ValueError('Review complete linked TLS dependency closure')
        linked[abi] = deps
    if linked['arm64-v8a'] != linked['x86_64']: raise ValueError('TLS ABI module closure differs')
    if any(sha(canonical / name) != value for name, value in pins.items()): raise ValueError('Canonical metadata changed during proof')
    proof = dict(sourceStable=True, reusedBinary=True, compiled=False, testsRerun=False,
                 canonicalMetadata=pins, linkedModules=linked, goToolSha256=sha(go), moduleGraphSha256=hashlib.sha256(graph.encode()).hexdigest())
    path = stage / 'used-module-proof.json'; path.write_text(json.dumps(proof, indent=2), encoding='utf-8')
    return stage


def capture(root, ready, plain, sha):
    builds = pathlib.Path('D:/MCRF/Builds/Android').resolve()
    pin = ready['steerTlsSource']
    origin = pathlib.Path(pin['stage']).resolve(strict=True)
    if origin.parent != builds or not origin.name.startswith('p049-expanded-'):
        raise ValueError('Exact retained TLS source stage required')
    for name, key in (('preparation.json', 'preparationSha256'),
                      ('tls-inputs.json', 'sourceManifestSha256'),
                      ('tls-library.json', 'libraryReceiptSha256')):
        if sha(plain(origin / name, builds)) != pin[key]:
            raise ValueError('Retained TLS source/receipt changed')
    prep = json.loads((origin / 'preparation.json').read_text('utf-8'))
    receipt_bytes=(origin / 'tls-library.json').read_bytes()
    if hashlib.sha256(receipt_bytes).hexdigest()!=pin['libraryReceiptSha256']:
        raise ValueError('TLS receipt changed during capture')
    receipt = json.loads(receipt_bytes)
    if receipt['state'] != 'COMPILED_ANDROID_SHARED_NOT_RUNTIME_ACCEPTED' or receipt['inputsSha256'] != pin['sourceManifestSha256']:
        raise ValueError('Only actual source-bound compiled TLS helpers may be retained')
    rows = json.loads((origin / 'tls-inputs.json').read_text('utf-8'))
    source = {}; source_hashes = {}
    for row in rows:
        path = plain(origin / 'tls-source' / row['path'], origin / 'tls-source')
        if sha(path) != row['sha256']:
            raise ValueError('Retained TLS dependency/source changed')
        source['p049-tls/' + row['path']] = path
        source_hashes['p049-tls/' + row['path']] = row['sha256']
    # Canonical helper edits invalidate reuse. Only the freezer's one local
    # module replacement is removed for this comparison, never source code.
    canonical = []; metadata_changed = False
    for path in (root / 'ports/android/transports/steer-tls49').iterdir():
        if not path.is_file() or path.suffix not in ('.go', '.mod', '.sum', '.txt', '.py', '.c', '.h'):
            continue
        path = plain(path, root)
        old = (origin / 'tls-source/steer-tls49' / path.name).read_bytes().replace(b'\r\n', b'\n')
        if path.name == 'go.mod':
            old = old.replace(('\nreplace github.com/refraction-networking/utls => ' +
                               (origin / 'tls-source/utls').as_posix() + '\n').encode(), b'')
        if path.read_bytes().replace(b'\r\n', b'\n') != old.replace(b'\r\n', b'\n'):
            if path.name not in ('go.mod', 'go.sum'):
                raise ValueError('TLS helper code changed; include its new source in the whole P2 batch')
            metadata_changed = True
        canonical.append(path)
    if set(receipt['abis']) != {'arm64-v8a', 'x86_64'}:
        raise ValueError('Retained TLS ABI set differs')
    for row in receipt['abis'].values():
        if sha(plain(pathlib.Path(row['path']), origin)) != row['sha256']:
            raise ValueError('Retained compiled TLS helper changed')
    if metadata_changed:
        metadata = used_module_proof(root, origin, receipt, plain, sha)
        for name in ('used-module-proof.json', 'canonical-go.mod', 'canonical-go.sum', 'selected-module-graph.txt',
                     'android-buildinfo-arm64-v8a.txt', 'android-buildinfo-x86_64.txt'):
            key = 'p049-tls/reuse-metadata/' + name
            source[key] = metadata / name; source_hashes[key] = sha(source[key])
    archive_manifest = plain(origin / 'compression-inputs.json', origin)
    if sha(archive_manifest) != prep['manifestHashes']['compression']:
        raise ValueError('Pinned native/codec archive manifest changed')
    archives = []
    for row in json.loads(archive_manifest.read_text('utf-8')):
        path = plain(origin / 'compression-source' / row['path'], origin / 'compression-source')
        if sha(path) != row['sha256']:
            raise ValueError('Pinned native/codec archive changed')
        archives.append(path)
    return dict(origin=origin, receipt=receipt, source=source, sourceHashes=source_hashes,
                receiptBytes=receipt_bytes, canonical=canonical, archives=archives)


def freeze(value, stage, sha):
    folder = stage / 'retained-tls'; folder.mkdir()
    receipt = dict(value['receipt'], abis={}, retainedFrom=str(value['origin']), newExecution=False)
    for abi, row in value['receipt']['abis'].items():
        target = folder / abi / 'libmcrf_utls49.so'; target.parent.mkdir()
        shutil.copyfile(row['path'], target)
        if sha(target) != row['sha256']:
            raise ValueError('TLS helper changed during capture')
        receipt['abis'][abi] = dict(row, path=target.as_posix())
    manifest = folder / 'tls-library.json'
    manifest.write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    archives = folder / 'archives'; archives.mkdir()
    for path in value['archives']:
        shutil.copyfile(path, archives / path.name)
        if sha(archives / path.name) != sha(path):
            raise ValueError('Native archive changed during capture')
    return manifest, archives


def packaged_hashes(value, payload, stage, sdk, sha):
    result = {}
    strip = sdk / 'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
    for abi, row in value['receipt']['abis'].items():
        if sha(pathlib.Path(row['path'])) != row['sha256']:
            raise ValueError('Linked helper changed after capture')
        output = stage / ('tls-packaged-' + abi + '.so')
        subprocess.run([str(strip), '--strip-unneeded', '-o', str(output), row['path']], check=True)
        if output.read_bytes() != payload['jni/' + abi + '/libmcrf_utls49.so']:
            raise ValueError('Packaged TLS helper differs from exact pinned NDK transform')
        result[abi] = sha(output)
    return result
