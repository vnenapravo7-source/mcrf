"""INACTIVE P2 owned native SOCKS/TLS fixture runner. Never an APK installer.

All-five common source/build evidence AND a fresh P2 main-device grant are
required before ANY adb command. Old P1 grants are deliberately inadmissible.
Only synthetic destinations over one owned loopback reverse are used. Native
stdin is its cancellation capability: EOF terminates ONLY that fixture itself.
No PID signal, wake/unlock, foreground change, VPN stop, profile or provider IO.
Wire IPv6 here is NOT physical IPv6, TUN routing or Android original-UID proof.
"""
import argparse
import concurrent.futures
import ctypes
import hashlib
import json
import os
import pathlib
import re
import secrets
import subprocess
import sys
import threading
import time
import uuid
import zipfile

from p2_probe_peer import gate, ordinary, exclusive_json
from p2_probe_cases import CASES, protect_new_directory

ROOT = pathlib.Path('D:/MCRF/Projects/MCRF')
BUILDS = pathlib.Path('D:/MCRF/Builds/Android')
DEVICE = 'R5CX73QYN3D'
THREAD = '01a13a9b-6754-7b13-af52-422a6eb5579c'
AUTHORITY = 'human-p2-whole-owned-native'
CURRENT = ROOT / 'ports/android/coordination/current.json'
LEASE = CURRENT.with_name('device-lease.json')
PREFIX = 'work/steer-android-port/android-vpn/tests/'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path, maximum=1048576):
    return json.loads(ordinary(path, maximum).read_text(encoding='utf-8-sig'))


def child(root, relative, maximum=67108864):
    parts = pathlib.PurePosixPath(relative.replace('\\', '/'))
    if parts.is_absolute() or not parts.parts or any(p in ('..', '.') or ':' in p for p in parts.parts):
        raise ValueError('Contained ordinary artifact path required')
    result = ordinary(root.joinpath(*parts.parts), maximum)
    result.relative_to(root)
    return result


class Drain:
    """Bounded drain, including the readiness/PID first line; never pipe deadlock."""
    def __init__(self, stream):
        self.first = concurrent.futures.Future()
        self.http = concurrent.futures.Future()
        self.data = bytearray()
        self.overflow = False
        self.thread = threading.Thread(target=self.run, args=(stream,), daemon=False)
        self.thread.start()

    def run(self, stream):
        try:
            first = stream.readline(4097)
            self.first.set_result(first)
            self.add(first)
            while True:
                part = stream.read1(4096)
                if not part:
                    break
                self.add(part)
        except Exception as error:
            if not self.first.done():
                self.first.set_exception(error)

    def add(self, data):
        if len(self.data) + len(data) > 262144:
            self.overflow = True
        self.data.extend(data[:max(0, 262144 - len(self.data))])
        if not self.http.done() and b'P2_PEER_TLS_REQUEST\n' in self.data.replace(b'\r\n', b'\n'):
            self.http.set_result(True)

    def joined(self):
        self.thread.join(5)
        if self.thread.is_alive() or self.overflow:
            raise RuntimeError('Fixture output not bounded and fully drained')
        return bytes(self.data)


class Runner:
    def __init__(self, args, ready, output, native, native_sha, prep, tls_helper):
        self.args, self.ready, self.output = args, ready, output
        self.native, self.native_sha, self.prep = native, native_sha, prep
        self.tls_helper = tls_helper
        self.job = 'P2OwnedNative:' + uuid.uuid4().hex
        self.remote = '/data/local/tmp/mcrf-p2-wire-' + uuid.uuid4().hex
        self.made, self.copied, self.mapping, self.pending = False, [], None, None
        self.mapping_confirmed, self.cleanup_blocked = False, False
        pinned = (ready, native, tls_helper, pathlib.Path(args.adb), pathlib.Path(__file__).absolute(),
            pathlib.Path(__file__).with_name('p2_probe_peer.py'), pathlib.Path(__file__).with_name('p2_probe_cases.py'),
            pathlib.Path(args.producer_receipt), pathlib.Path(args.common_artifacts), prep / 'preparation-receipt.json')
        self.pins = {path: sha(path) for path in pinned}
        for row in read_json(prep / 'preparation-receipt.json')['pkiInventory']:
            path = child(prep, row['file'], 65536)
            self.pins[path] = row['sha256']
        self.state = dict(priority=2, passed=False, sourceReadySha256=args.source_ready_sha256,
            producerReceiptSha256=sha(pathlib.Path(args.producer_receipt)),
            commonArtifactsSha256=sha(pathlib.Path(args.common_artifacts)),
            preparationReceiptSha256=sha(prep / 'preparation-receipt.json'),
            runnerSha256=sha(pathlib.Path(__file__).absolute()), adbSha256=args.adb_sha256,
            nativeSha256=native_sha, abi='arm64-v8a', assertionsEnabled=True, cases=[],
            cleanupPending=True, installed=False, providerAccess=False, originalUidProven=False,
            physicalIpv6Proven=False, fullP2Accepted=False)

    def authority(self):
        current, lease = read_json(CURRENT), read_json(LEASE)
        if current.get('activeBatch') != 'p2-progress-2026-10-20.json':
            raise ValueError('Current P2 batch required')
        live = read_json(CURRENT.with_name(current['activeBatch']))
        if (live.get('allowCommonDeviceVerification') is not True or
            live.get('deviceVerificationAuthority') != AUTHORITY or
            live.get('deviceSourceReadySha256') != self.args.source_ready_sha256 or
            any(live.get(flag) is not True for flag in ('sourceReady', 'readyForBuild', 'allowCommonVerification', 'nativeSourceReady', 'allowNativeCandidateBuild')) or
            sorted(item.get('id', -1) for item in live.get('items', [])) != [2, 6, 7, 11, 20] or
            any(item.get('sourceReady') is not True for item in live['items'])):
            raise ValueError('Fresh live P2 device authority bound to this immutable common source required')
        if (lease.get('device') != DEVICE or lease.get('owner_thread') != THREAD or
            lease.get('state') != 'occupied_main_fixture_acceptance' or lease.get('priority') != 2 or
            lease.get('authority') != AUTHORITY or lease.get('sourceReadySha256') != self.args.source_ready_sha256):
            raise ValueError('Fresh exact P2 main lease required; consumed P1 authority refused')
        jobs = current.get('jobs', {})
        if any(jobs.get(key) for key in ('build', 'hostTest', 'emulator')) or jobs.get('device') not in (None, self.job):
            raise ValueError('Another producer/device owner is active')
        if sha(self.ready) != self.args.source_ready_sha256 or sha(pathlib.Path(self.args.adb)) != self.args.adb_sha256:
            raise ValueError('Pinned source or adb changed')
        if any(sha(ordinary(path, 134217728)) != expected for path, expected in self.pins.items()):
            raise ValueError('Owned fixture source/binary/evidence changed')
        return current

    def adb(self, *arguments, timeout=12, cleanup=False):
        # Cleanup is limited to already captured private capabilities and does
        # not require a revoked test grant. No new fixture may start via it.
        if sha(ordinary(self.args.adb, 134217728)) != self.args.adb_sha256:
            raise ValueError('Pinned cleanup adb changed; retain job and evidence')
        if not cleanup:
            self.authority()
        result = subprocess.run([self.args.adb, '-s', DEVICE, *arguments], stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=False,
            creationflags=subprocess.CREATE_NO_WINDOW)
        if result.returncode or len(result.stdout) + len(result.stderr) > 1048576:
            raise RuntimeError('Owned Android command failed; do not infer cleanup')
        return result.stdout.decode('utf-8', errors='strict').strip()

    def idle(self):
        self.authority()
        if 'mWakefulness=Awake' not in self.adb('shell', 'dumpsys', 'power'):
            raise ValueError('Fresh awake screen required; no wake/unlock action')
        if not re.search(r'^\s+showing=false\s*$', self.adb('shell', 'dumpsys', 'window', 'policy'), re.M):
            raise ValueError('Fresh unlocked screen required')
        if self.adb('shell', 'getprop', 'ro.product.model') != 'SM-F741B' or self.adb('shell', 'am', 'get-current-user') != '0':
            raise ValueError('Exact Samsung owner device required')
        if self.adb('shell', 'getprop', 'ro.product.cpu.abi') != 'arm64-v8a':
            raise ValueError('Exact native ABI required')
        vpn = self.adb('shell', 'dumpsys', 'vpn_management')
        if 'Active vpn type:' not in vpn or re.search(r'Active vpn type: [01]\b', vpn):
            raise ValueError('User VPN active or state unknown; never stop it')
        if re.search(r'isForeground=true|startRequested=true|/\.McrfVpnService\b', self.adb('shell', 'dumpsys', 'activity', 'services', 'org.mcrf.client')):
            raise ValueError('App services not idle')

    def job_state(self, claim):
        raw = ordinary(CURRENT, 262144).read_bytes()
        current = json.loads(raw.decode('utf-8-sig'))
        if claim:
            self.authority()
            if current['jobs'].get('device') is not None:
                raise ValueError('Device job raced with another owner')
            current['jobs']['device'] = self.job
        elif current['jobs'].get('device') == self.job:
            current['jobs']['device'] = None
        else:
            raise ValueError('Exact device job owner lost; preserve state')
        if CURRENT.read_bytes() != raw:
            raise RuntimeError('Coordination changed; no stale overwrite')
        temporary = CURRENT.with_name('current-p2-' + uuid.uuid4().hex + '.tmp')
        with temporary.open('xb') as stream:
            stream.write((json.dumps(current, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
            stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, CURRENT)

    def upload(self, source, name, executable=False):
        if name not in ('host_probe_tls_test', 'libmcrf_utls49.so', 'root-a.pem'):
            raise ValueError('Closed owned upload allowlist')
        target = self.remote + '/' + name
        self.copied.append(target) # Own partial push too; only exact files below.
        self.adb('push', str(source), target)
        if not self.adb('shell', 'sha256sum', target).startswith(sha(source) + ' '):
            raise RuntimeError('Uploaded fixture SHA differs')
        self.adb('shell', 'chmod', '700' if executable else '600', target)

    def identity(self, pid, birth, require_alive):
        # Numeric PID is diagnostic only. No kill/stop request uses it. Birth,
        # executable and UID must ALL match before granting the start byte.
        if not re.fullmatch(r'[1-9][0-9]{0,8}', pid) or not re.fullmatch(r'[1-9][0-9]{0,19}', birth):
            raise ValueError('Invalid own native identity')
        text = self.adb('shell', '-T', 'if [ -r /proc/' + pid + '/stat ]; then cat /proc/' + pid +
            '/stat; readlink /proc/' + pid + '/exe; cat /proc/' + pid + '/status; else echo OWN_GONE; fi', cleanup=True)
        if text == 'OWN_GONE':
            if require_alive:
                raise RuntimeError('Own native exited before start')
            return True
        lines = text.splitlines()
        stat = lines[0].rsplit(')', 1)[1].split() if lines else []
        if len(stat) < 20:
            raise RuntimeError('Native process birth not proven')
        if stat[19] != birth:
            if require_alive:
                raise RuntimeError('Native identity changed before start')
            return True # A reused numeric PID is not our original process.
        if require_alive:
            if len(lines) < 3 or lines[1] != self.remote + '/host_probe_tls_test' or not re.search(r'^Uid:\s+2000\s+2000\s+2000\s+2000\s*$', text, re.M):
                raise RuntimeError('Exact own executable and shell UID required')
            return False
        return False

    def drop_mapping(self):
        if self.mapping is None:
            return
        port, host_port = self.mapping
        rows = [line.split() for line in self.adb('reverse', '--list', cleanup=True).splitlines()]
        exact = [row for row in rows if len(row) == 3 and row[1] == 'tcp:' + str(port)]
        if exact and exact != [[exact[0][0], 'tcp:' + str(port), 'tcp:' + str(host_port)]]:
            raise RuntimeError('Owned reverse mapping was replaced; do not remove another mapping')
        if exact and not self.mapping_confirmed:
            raise RuntimeError('Reverse creation acknowledgement lost; ownership not proven')
        if exact:
            self.adb('reverse', '--remove', 'tcp:' + str(port), cleanup=True)
        if any(line.split()[1:2] == ['tcp:' + str(port)] for line in self.adb('reverse', '--list', cleanup=True).splitlines()):
            raise RuntimeError('Owned reverse still present')
        self.mapping = None
        self.mapping_confirmed = False

    def case(self, family, case, cancel=False):
        self.idle()
        case_id = 'target' + str(family) + '-' + case
        plan = read_json(self.prep / 'cases' / case_id / 'case.json')
        expected_certificate = 'untrusted' if case == 'wrong-root' else 'wrong-name' if case == 'wrong-host' else 'trusted'
        if (plan.get('family') != family or plan.get('case') != case or plan.get('sourceReadySha256') != self.args.source_ready_sha256 or
            plan.get('certificate') != 'pki/' + expected_certificate + '.pem' or plan.get('privateKey') != 'pki/' + expected_certificate + '.key'):
            raise ValueError('Exact prepared case required')
        nonce = secrets.token_hex(16) if cancel else read_json(self.prep / 'cases' / case_id / 'private-input.json')['nonce']
        if not re.fullmatch(r'[0-9a-f]{32}', nonce):
            raise ValueError('Fresh private fixture nonce required')
        result_id = case_id + ('-cancel' if cancel else '')
        directory = self.output / result_id; directory.mkdir()
        peer_output = directory / 'peer'; peer_output.mkdir()
        command = [sys.executable, str(pathlib.Path(__file__).with_name('p2_probe_peer.py')),
            '--source-ready-receipt', str(self.ready), '--source-ready-sha256', self.args.source_ready_sha256,
            '--certificate', str(child(self.prep, plan['certificate'])), '--private-key', str(child(self.prep, plan['privateKey'])),
            '--nonce', nonce, '--case', case, '--family', str(family), '--output-directory', str(peer_output), '--ready-stdout', '--events-stdout']
        peer = native = None
        peer_out = peer_err = native_out = native_err = None
        identity = None
        passed, cleanup_ok = False, True
        try:
            peer = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                creationflags=subprocess.CREATE_NO_WINDOW)
            peer_out, peer_err = Drain(peer.stdout), Drain(peer.stderr)
            bound = json.loads(peer_out.first.result(5).decode('utf-8'))
            if (bound.get('host') != '127.0.0.1' or bound.get('family') != family or bound.get('case') != case or
                bound.get('sourceReadySha256') != self.args.source_ready_sha256 or type(bound.get('port')) is not int or not 1024 <= bound['port'] <= 65535):
                raise ValueError('Exact ready loopback peer required')
            host_port, device_port = bound['port'], 20000 + secrets.randbelow(40000)
            if any(line.split()[1:2] == ['tcp:' + str(device_port)] for line in self.adb('reverse', '--list').splitlines()):
                raise RuntimeError('Random fixture port already owned; no rebind')
            self.mapping = device_port, host_port # Retain intent even if adb acknowledgement is lost.
            self.adb('reverse', '--no-rebind', 'tcp:' + str(device_port), 'tcp:' + str(host_port))
            self.mapping_confirmed = True
            self.idle()
            timeout_ms = 10000 if cancel else 800 if case == 'trickle' else 3000
            args = [self.remote + '/host_probe_tls_test', str(device_port), self.remote + '/root-a.pem',
                'owned.example.test', str(family), '1' if case == 'ok' else '0', str(timeout_ms), nonce, 'owned-control']
            # ALL remote tokens are fixed literals or generated bounded alphanumerics.
            self.authority()
            native = subprocess.Popen([self.args.adb, '-s', DEVICE, 'shell', '-T', 'export LD_LIBRARY_PATH=' + self.remote + '; ulimit -c 0; exec ' + ' '.join(args)],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=subprocess.CREATE_NO_WINDOW)
            self.pending = ('UNKNOWN', 'UNKNOWN') # Lost first line must NEVER release the device owner.
            native_out, native_err = Drain(native.stdout), Drain(native.stderr)
            first = native_out.first.result(5).decode('ascii').strip()
            match = re.fullmatch(r'P2_NATIVE_SELF ([1-9][0-9]{0,8}) ([1-9][0-9]{0,19})', first)
            if not match:
                raise RuntimeError('Own native start identity missing')
            identity = match.groups(); self.pending = identity
            self.identity(*identity, require_alive=True)
            self.authority()
            native.stdin.write(b'G'); native.stdin.flush()
            if cancel:
                peer_out.http.result(5) # Cancel actual blocked TLS-header IO, not a not-yet-started mock.
                native.stdin.close()
            code = native.wait(12)
            peer_code = peer.wait(15)
            out, err = native_out.joined(), native_err.joined()
            exclusive_json(directory / 'native-result.json', dict(exitCode=code, stdout=out.decode('utf-8'),
                stderr=err.decode('utf-8'), nativeSha256=self.native_sha, pid=identity[0], birth=identity[1]))
            evidence = read_json(peer_output / 'peer-receipt.json')
            marker = b'P2_PROBE_TLS204_OK' if case == 'ok' else b'P2_PROBE_REFUSAL_OK'
            expected_lines = [first.encode('ascii')] if cancel else [first.encode('ascii'), marker]
            if code != (124 if cancel else 0) or peer_code or out.splitlines() != expected_lines or err:
                raise RuntimeError('Actual native assertion result/peer completion refused')
            if (evidence.get('case') != case or evidence.get('family') != family or
                evidence.get('nonceSha256') != hashlib.sha256(nonce.encode('ascii')).hexdigest() or
                evidence.get('certificateSha256') != sha(child(self.prep, plan['certificate'])) or
                evidence.get('sourceReadySha256') != self.args.source_ready_sha256 or
                any(evidence.get(key) is not True for key in ('socks_connect', 'sni_matches', 'peer_closed', 'sourceStable'))):
                raise RuntimeError('Exact native/peer case evidence required')
            if case not in ('wrong-root', 'wrong-host') and any(evidence.get(key) is not True for key in ('tls13', 'https_request')):
                raise RuntimeError('HTTP negative case never reached actual trusted TLS')
            if case == 'ok' and evidence.get('response_sent') is not True:
                raise RuntimeError('Positive peer response missing')
            passed = True
        finally:
            # First revoke ONLY the already captured native stdin capability.
            # Closing adb stdin is not signalling a guessed/reused PID.
            if native is not None:
                if native.stdin:
                    try: native.stdin.close()
                    except OSError: pass
                try:
                    native.wait(8)
                    if identity is None or not self.identity(*identity, require_alive=False):
                        raise RuntimeError('Original native process death unproven')
                    self.pending = None
                except Exception:
                    cleanup_ok = False
                for drain in (native_out, native_err):
                    if drain:
                        try: drain.joined()
                        except Exception: cleanup_ok = False
            if peer is not None:
                if peer.poll() is None:
                    peer.terminate() # Exact owned Windows process handle, no child tree.
                try:
                    peer.wait(8)
                    for drain in (peer_out, peer_err):
                        if drain: drain.joined()
                except Exception:
                    cleanup_ok = False
            try: self.drop_mapping()
            except Exception: cleanup_ok = False
            self.state['cases'].append(dict(id=result_id, passed=passed and cleanup_ok,
                nativeDeathProven=identity is not None and self.pending is None, peerDeathProven=peer is not None and peer.poll() is not None,
                reverseRemoved=self.mapping is None))
            if not cleanup_ok:
                self.cleanup_blocked = True
                raise RuntimeError('Owned native cleanup pending; retain exact job, do not start another case')

    def run(self):
        self.idle(); self.job_state(True)
        try:
            self.adb('shell', 'mkdir', '-m', '700', self.remote); self.made = True
            if self.adb('shell', 'stat', '-c', '%u:%a:%F', self.remote) != '2000:700:directory':
                raise RuntimeError('Exact private remote directory ownership required')
            self.upload(self.native, 'host_probe_tls_test', True)
            self.upload(self.tls_helper, 'libmcrf_utls49.so', True)
            self.upload(child(self.prep, 'pki/root-a.pem'), 'root-a.pem')
            for family in (4, 6):
                for case in CASES:
                    self.case(family, case)
                self.case(family, 'trickle', cancel=True)
            self.authority()
            self.state['passed'] = len(self.state['cases']) == 18 and all(row['passed'] for row in self.state['cases'])
        finally:
            try:
                if self.pending is not None or self.cleanup_blocked:
                    raise RuntimeError('Native process retirement pending; retain remote evidence')
                self.drop_mapping()
                for path in self.copied:
                    if path not in (self.remote + '/host_probe_tls_test', self.remote + '/libmcrf_utls49.so', self.remote + '/root-a.pem'):
                        raise ValueError('Exact own remote cleanup allowlist required')
                    self.adb('shell', 'rm', '-f', path, cleanup=True)
                if self.made:
                    self.adb('shell', 'rmdir', self.remote, cleanup=True)
                self.state['cleanupPending'] = False
                self.job_state(False)
            finally:
                self.state['sourceStable'] = sha(self.ready) == self.args.source_ready_sha256
                self.state['passed'] = self.state['passed'] and self.state['sourceStable'] and not self.state['cleanupPending']
                exclusive_json(self.output / 'receipt.json', self.state)


def main():
    parser = argparse.ArgumentParser()
    for name in ('source-ready-receipt', 'source-ready-sha256', 'producer-receipt', 'common-artifacts',
                 'preparation-directory', 'adb', 'adb-sha256', 'output-directory'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    ready = gate(args.source_ready_receipt, args.source_ready_sha256) # FIRST, no subprocess or writes before it.
    if sys.platform != 'win32':
        raise ValueError('Explicit owned Windows host required')
    adb = ordinary(args.adb, 134217728)
    if not re.fullmatch(r'[0-9a-f]{64}', args.adb_sha256) or sha(adb) != args.adb_sha256:
        raise ValueError('Explicit SHA-pinned adb required')
    producer_file = ordinary(args.producer_receipt, 4194304)
    producer = read_json(producer_file, 4194304)
    stage = pathlib.Path(producer.get('primaryStage', '')).absolute()
    stage.relative_to(BUILDS)
    receipt = child(stage, 'receipt.json')
    if (producer.get('priority') != 2 or producer.get('state') != 'compiled_candidate_not_runtime_accepted' or
        producer.get('sourceStable') is not True or producer.get('readinessSha256') != args.source_ready_sha256 or
        producer.get('primaryStageReceiptSha256') != sha(receipt)):
        raise ValueError('Exact completed P2 native source producer required')
    origin = read_json(receipt)
    source_zip = child(stage, origin['sourceArchive']['file'])
    if sha(source_zip) != origin['sourceArchive']['sha256']:
        raise ValueError('Native source archive changed')
    if origin.get('nativeFixturesAssertionsEnabled') is not True:
        raise ValueError('Actual assertion-enabled native recipe required')
    with zipfile.ZipFile(source_zip) as archive:
        for name in ('p2_probe_native_android.py', 'p2_probe_peer.py', 'p2_probe_cases.py'):
            path = ordinary(pathlib.Path(__file__).with_name(name), 1048576)
            archived_sha = hashlib.sha256(archive.read(PREFIX + name)).hexdigest()
            if archived_sha != producer['inputs'][PREFIX + name]['sha256']:
                raise ValueError('Archived native-source controller origin changed')
            if sha(path) != archived_sha and name == 'p2_probe_native_android.py':
                revision = read_json(CURRENT.with_name('p2-runtime-authority.json'))
                if (revision.get('authority') != 'human-20261021-p2-common-samsung-install-check-restore' or
                    revision.get('probeControllerSha256') != sha(path) or revision.get('probeControllerAncestorSha256') != archived_sha or
                    revision.get('probeControllerNativeProducerSha256') != sha(producer_file)):
                    raise ValueError('Explicit source-only controller revision required; native compile is retained, not relabelled')
            elif sha(path) != archived_sha:
                raise ValueError('Runner/peer/preparer not exact compiled native source')
    fixtures = [row for row in origin.get('nativeFixtures', []) if row.get('abi') == 'arm64-v8a' and row.get('name') == 'host_probe_tls_test']
    if len(fixtures) != 1 or fixtures[0]['file'] != 'fixture-native/arm64-v8a/host_probe_tls_test':
        raise ValueError('Exact actual compiled assertion fixture required')
    native = child(stage / 'steer', fixtures[0]['file'])
    if sha(native) != fixtures[0]['sha256'] or native.stat().st_size != fixtures[0]['bytes']:
        raise ValueError('Native executable changed')
    dependencies=fixtures[0].get('dependencies',[])
    if len(dependencies)!=1 or dependencies[0].get('file')!='fixture-native/arm64-v8a/libmcrf_utls49.so':
        raise ValueError('Exact production TLS fixture dependency required')
    dependency=dependencies[0];tls_helper=child(stage/'steer',dependency['file'])
    tls_descriptor=origin['modules']['steer'].get('tls49',{})
    if (sha(tls_helper)!=dependency['sha256'] or tls_helper.stat().st_size!=dependency['bytes'] or
        tls_descriptor.get('library')!='libmcrf_utls49.so' or
        tls_descriptor.get('abiSha256',{}).get('arm64-v8a')!=dependency['sha256']):
        raise ValueError('Fixture TLS helper must equal the production packaged helper')
    common_file = ordinary(args.common_artifacts, 1048576)
    common = read_json(common_file)
    built = read_json(common_file.with_name('build-result.json'))
    jvm = common_file.with_name('p2-jvm-evidence.json')
    if (common_file.name != 'p2-candidate-artifacts.json' or common.get('priority') != 2 or
        common.get('readinessSha256') != args.source_ready_sha256 or built.get('exitCode') != 0 or built.get('sourceStable') is not True or
        common.get('sourceManifestSha256') != built.get('sourceManifestSha256') or common.get('jvmEvidenceSha256') != sha(ordinary(jvm, 4194304)) or
        common.get('steerOrigin', {}).get('producerReceiptSha256') != sha(producer_file) or
        common.get('steerOrigin', {}).get('primaryStageReceiptSha256') != sha(receipt) or
        not {'host', 'steer', 'wg', 'sing-box-dispatcher', 'csqtt', 'csqtt25'}.issubset({row.get('kind') for row in common.get('artifacts', [])})):
        raise ValueError('Completed WHOLE common build plus affected JVM evidence required')
    if sha(ordinary(common_file.with_name('source-manifest.json'), 16777216)) != common['sourceManifestSha256'] or read_json(jvm, 4194304).get('passed') is not True:
        raise ValueError('Actual common source/JVM evidence changed')
    for row in common['artifacts']:
        if sha(child(common_file.parent, row['path'], 536870912)) != row['sha256']:
            raise ValueError('Common artifact changed')
    prep = pathlib.Path(args.preparation_directory).absolute()
    prep.relative_to(BUILDS)
    prepared = read_json(prep / 'preparation-receipt.json')
    expected_ids = ['target' + str(family) + '-' + case for family in (4, 6) for case in CASES]
    if (prepared.get('state') != 'cases_prepared_NOT_RUN' or prepared.get('sourceReadySha256') != args.source_ready_sha256 or
        prepared.get('sourceStable') is not True or prepared.get('opensslStable') is not True or prepared.get('privateOutput') is not True or
        prepared.get('preparerSha256') != sha(pathlib.Path(__file__).with_name('p2_probe_cases.py')) or prepared.get('preparedCases') != expected_ids):
        raise ValueError('Exact private prepared PKI/cases required')
    for row in prepared['pkiInventory']:
        if sha(child(prep, row['file'], 65536)) != row['sha256']:
            raise ValueError('Fixture PKI changed')
    output = pathlib.Path(args.output_directory).absolute()
    if output.parent != BUILDS or not re.fullmatch(r'p2-native-wire-[0-9a-f]{32}', output.name) or output.exists():
        raise ValueError('New explicit owned build-output directory required')
    runner = Runner(args, ready, output, native, fixtures[0]['sha256'], prep, tls_helper)
    runner.authority() # Fresh P2 authority BEFORE output or ANY adb; never sets it.
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    from ctypes import wintypes
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
    kernel.CreateMutexW.restype = wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    kernel.ReleaseMutex.argtypes = [wintypes.HANDLE]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    mutex = kernel.CreateMutexW(None, False, 'Local\\MCRF-P2-Owned-Native')
    if not mutex:
        raise ctypes.WinError(ctypes.get_last_error())
    acquired = False
    try:
        acquired = kernel.WaitForSingleObject(mutex, 0) in (0, 0x80)
        if not acquired:
            raise RuntimeError('Another exact P2 fixture runner is active')
        output.mkdir(); protect_new_directory(output)
        runner.run()
    finally:
        if acquired: kernel.ReleaseMutex(mutex)
        kernel.CloseHandle(mutex)
    if not runner.state['passed']:
        raise RuntimeError('Owned native cases not accepted; retain receipt')
    print('P2_NATIVE_WIRE_RECEIPT=' + str(output / 'receipt.json'))


if __name__ == '__main__':
    main()
