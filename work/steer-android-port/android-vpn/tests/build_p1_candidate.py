"""Inactive whole-P1 external producer. No installation, publication or old-suite rerun.

Reads the CURRENT working tree (including unstaged source), freezes ordinary
files without git add/reset, then compiles ONE external runtime/adapter batch.
The original public capabilities remain untouched. Candidate declarations are
not runtime acceptance. Model evidence must match exact current model inputs.
"""
import argparse
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import subprocess
import uuid
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def plain(path, root, maximum=64 * 1024 * 1024):
    relative = path.relative_to(root)
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink() or current.resolve() != current.absolute():
            raise RuntimeError('Linked or redirected source refused')
    if not path.is_file() or not 0 <= path.stat().st_size <= maximum:
        raise RuntimeError('Ordinary bounded input required')
    return path


def write_zip(path, entries):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 10, 19, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)


def main():
    parser = argparse.ArgumentParser()
    for name in ('java-home', 'android-sdk', 'gradle-cache', 'model-evidence'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    recipe = pathlib.Path(__file__).absolute()
    project, root = recipe.parents[1], recipe.parents[4]
    current_file = plain(root / 'ports/android/coordination/current.json', root, 262144)
    current = json.loads(current_file.read_text(encoding='utf-8-sig'))
    batch = current.get('activeBatch', '')
    if not re.fullmatch(r'p1-progress-\d{4}-\d{2}-\d{2}\.json', batch):
        raise SystemExit('Whole-P1 must be active; no standalone candidate build')
    ready_file = plain(current_file.parent / batch, root, 262144)
    ready = json.loads(ready_file.read_text(encoding='utf-8-sig'))
    required = ('sourceReady', 'readyForBuild', 'allowCommonVerification', 'nativeSourceReady', 'allowNativeCandidateBuild')
    if ready.get('priority') != 1 or ready.get('verificationAuthority') != 'human-20261019-p1-whole-source-before-common-verification' or any(ready.get(flag) is not True for flag in required):
        raise SystemExit('Finish ALL P1 source tasks before compiler/native processes')
    evidence_file = pathlib.Path(args.model_evidence).absolute()
    builds = pathlib.Path('D:/MCRF/Builds/Android')
    plain(evidence_file, builds, 1048576)
    evidence = json.loads(evidence_file.read_text(encoding='utf-8-sig'))
    if evidence.get('passed') is not True or evidence.get('sourceStable') is not True or not evidence.get('inputs'):
        raise SystemExit('Exact completed common model evidence required; no implicit rerun')
    for relative, expected in evidence['inputs'].items():
        source = root / 'ports/android/app/src/main/assets/presets' / relative.removeprefix('host-presets/') if relative.startswith('host-presets/') else project / relative
        if sha(plain(source, root)) != expected:
            raise SystemExit('Model source differs from completed common evidence')
    if sha(plain(project / 'gradle/wrapper/gradle-wrapper.jar', root)) != '76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3':
        raise SystemExit('Pinned Gradle wrapper required')
    # Git inventories files only. It never stages, resets or rewrites user edits.
    names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=project.parent).decode().split('\0')
    inputs = set()
    forbidden = {'test-results', '.gradle', '.cxx', '.kotlin', '.git', 'outputs'}
    for name in names:
        if not name:
            continue
        relative = pathlib.PurePosixPath(name)
        if relative.is_absolute() or '..' in relative.parts:
            raise RuntimeError('Source inventory escaped repository')
        if any(part in forbidden for part in relative.parts) or name.endswith('local.properties'):
            continue
        if relative.parts[0] not in ('src', 'build', 'android-vpn', 'tests') and name not in ('Android.bp', 'LICENSE', 'COPYING'):
            continue
        if relative.parts[0] == 'tests' and relative.suffix not in ('.c', '.h'):
            continue
        if relative.parts[0] == 'android-vpn':
            sub = relative.parts[1:]
            allowed_roots = {'core', 'module-contract', 'module-spi', 'module-apk', 'integration-helper', 'primary-adapter', 'gradle'}
            allowed_files = {'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'p1-candidate.gradle.kts'}
            if not sub or (sub[0] not in allowed_roots and name not in {'android-vpn/' + value for value in allowed_files} and not (sub[0] == 'tests' and relative.suffix in ('.c', '.h'))):
                continue
        # Top-level build contains upstream build sources/patches, NOT outputs.
        if 'build' in relative.parts[1:]:
            continue
        inputs.add(plain(project.parent / relative, root))
    inputs.update((current_file, ready_file, recipe))
    manifest = {path.relative_to(root).as_posix(): {'sha256': sha(path), 'bytes': path.stat().st_size} for path in inputs}
    if len(manifest) < 100:
        raise RuntimeError('Incomplete native source inventory')
    stage = builds / ('p1-steer-candidate-' + uuid.uuid4().hex)
    stage.mkdir(parents=True, exist_ok=False)
    # Real short directory, NOT subst/junction/symlink or a live checkout.
    # Ninja also embeds absolute source names in object paths, so shortening
    # only .cxx is insufficient on Windows.
    frozen = builds / ('p1s-' + uuid.uuid4().hex[:12])
    frozen.mkdir(parents=False, exist_ok=False)
    for relative, row in manifest.items():
        copy = frozen / relative
        copy.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / relative, copy)
        if sha(copy) != row['sha256']:
            raise RuntimeError('Source changed while freezing candidate')
    state = {'state': 'building', 'sourceStable': False, 'inputs': manifest, 'frozenSourceDirectory': str(frozen), 'readinessSha256': sha(ready_file),
             'modelEvidenceSha256': sha(evidence_file), 'runtimeVerified': False, 'installed': False, 'published': False}
    result_file = stage / 'producer-receipt.json'
    def save():
        result_file.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
    save()
    try:
        frozen_project = frozen / project.relative_to(root)
        java = pathlib.Path(args.java_home).absolute()
        sdk = pathlib.Path(args.android_sdk).absolute()
        env = os.environ.copy()
        env.update(JAVA_HOME=str(java), ANDROID_HOME=str(sdk), GRADLE_USER_HOME=str(pathlib.Path(args.gradle_cache).absolute()))
        java_tmp = stage / 'tmp'; java_tmp.mkdir()
        if len(str(java_tmp)) > 85:
            raise RuntimeError('JDK socket path too long')
        env['JAVA_TOOL_OPTIONS'] = env.get('JAVA_TOOL_OPTIONS', '') + ' "-Djdk.net.unixdomain.tmpdir=' + str(java_tmp) + '"'
        command = [str(java / 'bin/java.exe'), '-classpath', 'gradle/wrapper/gradle-wrapper.jar', 'org.gradle.wrapper.GradleWrapperMain',
                   ':core:assembleRelease', ':primary-adapter:assembleRelease', ':module-spi:jar', ':core:writeP1RuntimeClasspath',
                   '-PsteerP1Candidate=true', '-Pp1SourceReadyReceipt=' + str(frozen / ready_file.relative_to(root)),
                   '-Pp1SourceReadySha256=' + state['readinessSha256'], '--offline', '--console=plain', '--no-daemon', '--max-workers=4', '--continue']
        command.append('-Pp1NativeScratch=' + str(frozen / 'cxx'))
        with (stage / 'build.log').open('wb') as log:
            process = subprocess.run(command, cwd=frozen_project, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=3600)
        if process.returncode:
            raise RuntimeError('Common external candidate compilation failed; retain original log')
        runtime_inputs = json.loads((frozen_project / 'core/build/p1-runtime-classpath.json').read_text())
        expected = json.loads((frozen_project / 'core/runtime-dependencies.json').read_text())['tested_runtime']
        if {row['coordinate']: row['sha256'] for row in runtime_inputs} != {row['coordinate']: row['sha256'] for row in expected}:
            raise RuntimeError('Actual runtime dependency graph differs from pinned inventory')
        delivery = stage / 'primary-stage'
        target = delivery / 'steer'; target.mkdir(parents=True)
        dependencies = target / 'bundled'; dependencies.mkdir()
        merged = {}; origins = []
        def merge_jar(data):
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                for entry in jar.infolist():
                    name = entry.filename
                    if entry.is_dir() or name == 'META-INF/MANIFEST.MF' or name.startswith('META-INF/versions/') or name.endswith('module-info.class'):
                        continue
                    if name.startswith('/') or '..' in pathlib.PurePosixPath(name).parts or entry.file_size > 16777216:
                        raise RuntimeError('Unexpected runtime JAR entry')
                    value = jar.read(entry)
                    if name in merged and merged[name] != value:
                        raise RuntimeError('Conflicting runtime classes/resources')
                    merged[name] = value
        core = frozen_project / 'core/build/outputs/aar/core-release.aar'
        with zipfile.ZipFile(core) as aar:
            merge_jar(aar.read('classes.jar'))
            payload = {entry.filename: aar.read(entry) for entry in aar.infolist() if not entry.is_dir() and entry.filename.startswith(('assets/', 'jni/'))}
            rules = aar.read('proguard.txt') if 'proguard.txt' in aar.namelist() else b''
        for row in runtime_inputs:
            source = pathlib.Path(row['file'])
            if sha(source) != row['sha256']:
                raise RuntimeError('Dependency changed after actual resolution')
            filename = row['coordinate'].replace(':', '_') + '.jar'
            shutil.copyfile(source, dependencies / filename)
            merge_jar(source.read_bytes())
            origins.append({'file': 'bundled/' + filename, 'coordinate': row['coordinate'], 'sha256': row['sha256'], 'bytes': source.stat().st_size})
        if any(name.startswith('org/mcrf/engine/spi/') for name in merged):
            raise RuntimeError('Duplicate parent SPI in child runtime')
        capabilities = json.loads(payload['assets/steer-capabilities.json'])
        if capabilities.get('mcrf_snapshot_model') != 2 or capabilities.get('candidate_only') is not True or capabilities.get('runtime_accepted') is not False:
            raise RuntimeError('Candidate capability recipe not compiled')
        abis = ['arm64-v8a', 'x86_64']
        if {name for name in payload if name.startswith('jni/')} != {f'jni/{abi}/libsteer_vpn.so' for abi in abis}:
            raise RuntimeError('Unexpected native payload set')
        reader = sdk / 'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
        native_rows = []
        for abi in abis:
            binary = stage / ('libsteer-' + abi + '.so')
            binary.write_bytes(payload[f'jni/{abi}/libsteer_vpn.so'])
            report = subprocess.check_output([str(reader), '-lW', str(binary)], text=True, timeout=30)
            loads = [line for line in report.splitlines() if re.match(r'^\s*LOAD\s', line)]
            if not loads or any(int(line.split()[-1], 16) < 16384 for line in loads):
                raise RuntimeError('Native library below16KiB load alignment')
            native_rows.append({'abi': abi, 'sha256': sha(binary), 'elf16KiB': True})
        fixtures = []
        for abi in abis:
            for name in ('ip6_packet_test', 'host_routing_test', 'host_dualstack_flow_test', 'host_dualstack_wire_test'):
                candidates = []
                for folder in (frozen / 'cxx', frozen_project / 'core/build/intermediates/cxx'):
                    if folder.exists():
                        candidates += [path for path in folder.rglob(name) if path.is_file() and abi in path.parts]
                if not candidates or len({sha(path) for path in candidates}) != 1:
                    raise RuntimeError('Actual queued native executable missing/ambiguous')
                source = candidates[0]
                destination = target / 'fixture-native' / abi / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
                header = destination.read_bytes()[:20]
                machine = int.from_bytes(header[18:20], 'little')
                if header[:4] != b'\x7fELF' or header[4:6] != b'\x02\x01' or machine != (183 if abi == 'arm64-v8a' else 62):
                    raise RuntimeError('Native fixture ABI differs')
                fixtures.append({'abi': abi, 'name': name, 'file': f'fixture-native/{abi}/{name}', 'sha256': sha(destination), 'bytes': destination.stat().st_size})
        jar_output = io.BytesIO(); write_zip(jar_output, merged)
        payload.update({'classes.jar': jar_output.getvalue(), 'proguard.txt': rules,
            'AndroidManifest.xml': b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.mcrf.engines.steer.runtime"><uses-sdk android:minSdkVersion="29"/></manifest>'})
        write_zip(target / 'runtime.aar', payload)
        with zipfile.ZipFile(frozen_project / 'primary-adapter/build/outputs/aar/primary-adapter-release.aar') as aar:
            adapter = {entry.filename: aar.read(entry) for entry in aar.infolist() if not entry.is_dir()}
        if any(name.startswith('jni/') for name in adapter):
            raise RuntimeError('Adapter unexpectedly embeds native runtime')
        with zipfile.ZipFile(io.BytesIO(adapter['classes.jar'])) as jar:
            classes = [name for name in jar.namelist() if name.endswith('.class')]
            if 'org/mcrf/engines/steer/SteerEngine.class' not in classes or not all(name.startswith('org/mcrf/engines/steer/') for name in classes):
                raise RuntimeError('Adapter class boundary differs from parent contract')
        adapter['AndroidManifest.xml'] = b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.mcrf.engines.steer.adapter"><uses-sdk android:minSdkVersion="29"/></manifest>'
        write_zip(target / 'adapter.aar', adapter)
        notices = target / 'licenses'; notices.mkdir()
        for source in (frozen_project / 'core/src/main/assets/licenses').iterdir():
            if source.is_file():
                shutil.copyfile(source, notices / source.name)
        def record(path):
            return {'file': path.name, 'bytes': path.stat().st_size, 'sha256': sha(path)}
        # Source ZIP contains only the original input manifest, never generated
        # runtime/private fixture files, compiler caches or unrelated worktrees.
        write_zip(delivery / 'source.zip', {relative: (frozen / relative).read_bytes() for relative in manifest})
        shutil.copyfile(frozen_project / 'module-spi/build/libs/module-spi.jar', delivery / 'spi.jar')
        for relative, row in manifest.items():
            if sha(frozen / relative) != row['sha256'] or sha(root / relative) != row['sha256']:
                raise RuntimeError('Canonical/frozen source changed during compilation')
        descriptor = {'spi': 1, 'api': 2, 'entry': 'org.mcrf.engines.steer.SteerEngine', 'versionCode': 6,
                      'moduleVersion': '0.3.3-p1-candidate.6', 'abis': abis, 'sourceRepository': 'https://github.com/splify2/steer',
                      'sourceVersion': 'whole-p1-working-tree', 'sourceSha256': sha(delivery / 'source.zip'),
                      'artifacts': [record(target / name) for name in ('runtime.aar', 'adapter.aar')],
                      'licenses': [record(path) for path in sorted(notices.iterdir())], 'bundledDependencies': origins,
                      'p1Candidate': True, 'p1ReadinessSha256': state['readinessSha256']}
        receipt = {'version': 1, 'modules': {'steer': descriptor}, 'sourceArchive': record(delivery / 'source.zip'),
                   'sharedSpi': record(delivery / 'spi.jar'), 'runtime_verified': False, 'published': False,
                   'nativeLibraries': native_rows, 'nativeFixtures': fixtures, 'nativeFixturesExecuted': False,
                   'scope': 'Source-bound external candidate only; Android JNI/UID/protect/DNS/proxy/STOP acceptance remains mandatory'}
        (delivery / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
        state.update(state='compiled_candidate_not_runtime_accepted', sourceStable=True, primaryStage=str(delivery), primaryStageReceiptSha256=sha(delivery / 'receipt.json'))
    except Exception:
        state['state'] = 'failed'
        raise
    finally:
        save()
    print('P1_STEER_CANDIDATE=' + str(delivery))


if __name__ == '__main__':
    main()
