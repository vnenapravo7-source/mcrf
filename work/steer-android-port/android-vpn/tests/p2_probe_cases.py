"""Prepare owned ephemeral PKI and exact P2 native-wire cases AFTER the common
source gate opens. Does not execute native code, control Android, install roots,
contact a provider, or declare any case passed. Private test keys stay in the
new caller-owned directory; receipts contain hashes, never key material.

Run on Linux or Windows with an explicitly SHA-pinned OpenSSL binary.
Preparation alone is NOT permission to run NDK binaries on a phone. The common
runner must separately prove binary/source identity and actual process death.
"""
import argparse
import hashlib
import json
import os
import pathlib
import secrets
import subprocess
import sys

from p2_probe_peer import exclusive_json, gate, ordinary


CASES = ('ok', 'wrong-root', 'wrong-host', 'redirect', 'truncated', 'body', 'malformed', 'trickle')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def protect_new_directory(path):
    """Only a fresh empty owned output; never edit a home/workspace ACL."""
    if not path.is_dir() or any(path.iterdir()):
        raise ValueError('Fresh empty private output required')
    if os.name != 'nt':
        os.chmod(path, 0o700)
        return
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    advapi = ctypes.WinDLL('advapi32', use_last_error=True)
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    advapi.OpenProcessToken.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE)]
    advapi.GetTokenInformation.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
    advapi.ConvertSidToStringSidW.argtypes = [ctypes.c_void_p, ctypes.POINTER(wintypes.LPWSTR)]
    advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(wintypes.DWORD)]
    advapi.SetFileSecurityW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.c_void_p]
    token = wintypes.HANDLE()
    if not advapi.OpenProcessToken(kernel.GetCurrentProcess(), 8, ctypes.byref(token)):
        raise ctypes.WinError(ctypes.get_last_error())
    sid_text, descriptor = wintypes.LPWSTR(), ctypes.c_void_p()
    try:
        size = wintypes.DWORD()
        advapi.GetTokenInformation(token, 1, None, 0, ctypes.byref(size))
        if not 0 < size.value < 65536:
            raise RuntimeError('Bounded owner token required')
        data = ctypes.create_string_buffer(size.value)
        if not advapi.GetTokenInformation(token, 1, data, size, ctypes.byref(size)):
            raise ctypes.WinError(ctypes.get_last_error())
        sid = ctypes.cast(data, ctypes.POINTER(ctypes.c_void_p))[0]
        if not advapi.ConvertSidToStringSidW(sid, ctypes.byref(sid_text)):
            raise ctypes.WinError(ctypes.get_last_error())
        sddl = 'D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;' + sid_text.value + ')'
        if not advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, 1, ctypes.byref(descriptor), None):
            raise ctypes.WinError(ctypes.get_last_error())
        if not advapi.SetFileSecurityW(str(path), 0x80000004, descriptor):
            raise ctypes.WinError(ctypes.get_last_error())
    finally:
        if descriptor:
            kernel.LocalFree(descriptor)
        if sid_text:
            kernel.LocalFree(ctypes.cast(sid_text, ctypes.c_void_p))
        kernel.CloseHandle(token)


def main():
    parser = argparse.ArgumentParser()
    for name in ('source-ready-receipt', 'source-ready-sha256', 'openssl', 'openssl-sha256', 'output-directory'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    # Gate BEFORE any artifact, key generation, external command or directory write.
    ready = gate(args.source_ready_receipt, args.source_ready_sha256)
    if not (sys.platform.startswith('linux') or sys.platform == 'win32'):
        raise ValueError('Explicit Linux/Windows host required; no implicit WSL or phone execution')
    openssl = ordinary(args.openssl, 67108864)
    if digest(openssl) != args.openssl_sha256 or not os.access(openssl, os.X_OK):
        raise ValueError('Exact executable OpenSSL inventory pin required')
    output = pathlib.Path(args.output_directory).absolute()
    if output.resolve() != output or not output.is_dir() or any(parent.is_symlink() for parent in (output, *output.parents)) or any(output.iterdir()):
        raise ValueError('Caller-created empty ordinary fixture directory required')
    if os.name == 'nt':
        import re
        if output.parent != pathlib.Path('D:/MCRF/Builds/Android') or not re.fullmatch(r'p2-probe-cases-[0-9a-f]{32}', output.name):
            raise ValueError('Dedicated new Windows PKI build-output directory required')
    protect_new_directory(output)
    pki = output / 'pki'
    cases_dir = output / 'cases'
    pki.mkdir(mode=0o700)
    cases_dir.mkdir(mode=0o700)
    evidence = dict(state='preparing_not_native_acceptance', sourceReadySha256=args.source_ready_sha256,
                    opensslSha256=args.openssl_sha256, preparerSha256=digest(pathlib.Path(__file__).absolute()),
                    pythonSha256=digest(ordinary(sys.executable, 134217728)), hostPlatform=sys.platform,
                    privateOutput=True, nativeExecuted=False, androidAccess=False,
                    installedTrust=False, providerAccess=False, executedCases=[], preparedCases=[])
    # No -CAcreateserial shared mutable serial file; every certificate gets a
    # fresh explicit positive serial. Keys are fixture-only and never exported.
    def command(label, *arguments):
        if digest(openssl) != args.openssl_sha256 or digest(ready) != args.source_ready_sha256:
            raise RuntimeError('Pinned fixture inputs changed before external command')
        try:
            completed = subprocess.run([str(openssl), *arguments], cwd=pki, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise RuntimeError('Bounded owned PKI command failed: ' + label) from None
        if completed.returncode:
            raise RuntimeError('Owned PKI command refused: ' + label)
        for path in pki.iterdir():
            if path.is_file():
                os.chmod(path, 0o600)

    def serial():
        return '0x' + secrets.token_hex(16)

    def root(name):
        command('ephemeral root', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '2',
                '-subj', '/CN=MCRF Owned P2 Fixture ' + name, '-set_serial', serial(),
                '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
                '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
                '-keyout', name + '.key', '-out', name + '.pem')

    def leaf(name, signer, hostname):
        extension = pki / (name + '.ext')
        with extension.open('x', encoding='ascii') as stream:
            stream.write('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n'
                         'extendedKeyUsage=serverAuth\nsubjectAltName=DNS:' + hostname + '\n')
        os.chmod(extension, 0o600)
        command('ephemeral leaf key', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-sha256',
                '-subj', '/CN=' + hostname, '-keyout', name + '.key', '-out', name + '.csr')
        command('ephemeral leaf certificate', 'x509', '-req', '-in', name + '.csr', '-CA', signer + '.pem',
                '-CAkey', signer + '.key', '-set_serial', serial(), '-days', '2', '-sha256',
                '-extfile', extension.name, '-out', name + '.pem')

    try:
        root('root-a')
        root('root-b')
        leaf('trusted', 'root-a', 'owned.example.test')
        leaf('untrusted', 'root-b', 'owned.example.test')
        leaf('wrong-name', 'root-a', 'other.example.test')
        for family in (4, 6):
            for case in CASES:
                case_id = 'target' + str(family) + '-' + case
                directory = cases_dir / case_id
                directory.mkdir(mode=0o700)
                # Auth nonce unique per fresh native process; never a user secret.
                exclusive_json(directory / 'private-input.json', dict(nonce=secrets.token_hex(16)))
                certificate = 'untrusted' if case == 'wrong-root' else 'wrong-name' if case == 'wrong-host' else 'trusted'
                exclusive_json(directory / 'case.json', dict(
                    schema=1, id=case_id, case=case, family=family,
                    physicalPeer='127.0.0.1', syntheticTarget='198.18.0.2' if family == 4 else 'fd89::2',
                    host='owned.example.test', path='/owned', expectedPositive=case == 'ok',
                    timeoutMs=800 if case == 'trickle' else 3000,
                    certificate='pki/' + certificate + '.pem', privateKey='pki/' + certificate + '.key',
                    trustRoot='pki/root-a.pem', privateInput='cases/' + case_id + '/private-input.json',
                    peerOutputMustBeFreshEmpty=True, nativeMustBeFreshProcess=True,
                    expectedPeerSni=True, nativeFdCountMustReturnToBaseline=True,
                    sourceReadySha256=args.source_ready_sha256,
                    acceptance='NOT_RUN'))
                evidence['preparedCases'].append(case_id)
        evidence['pkiInventory'] = [dict(file='pki/' + path.name, bytes=path.stat().st_size,
                                       sha256=digest(path), private=path.suffix in ('.key', '.csr'))
                                    for path in sorted(pki.iterdir())]
        evidence['state'] = 'cases_prepared_NOT_RUN'
        evidence['limitations'] = ('IPv6 is a synthetic SOCKS destination here, NOT physical IPv6/TUN/Android proof. '
                                   'Peer-side TLS refusal alone is not native acceptance. No runtime runner or device grant implied.')
    finally:
        evidence['sourceStable'] = digest(ready) == args.source_ready_sha256
        evidence['opensslStable'] = digest(openssl) == args.openssl_sha256
        exclusive_json(output / 'preparation-receipt.json', evidence)
    if not evidence['sourceStable'] or not evidence['opensslStable']:
        raise RuntimeError('Fixture inputs changed; retain artifacts, reject preparation')


if __name__ == '__main__':
    main()
