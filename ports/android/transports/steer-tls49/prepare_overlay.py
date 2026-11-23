"""Source-only private uTLS overlay; called by the future COMMON P049 freezer.

No compiler, resolver, certificates, tests, APKs or device operations here.
Never edits GOPATH or accepts an arbitrary unverified upstream TLS revision.
"""
from hashlib import sha256
from pathlib import Path
import shutil

PIN = 'v1.8.3-0.20260301010127-aa6edf4b11af'
HASHES = {
    'u_handshake_client.go': 'd3cd2f1f75a260bcebcf447da4e1aa61a0ceb0755c57c0b78e21b5188eff20b8',
    'conn.go': 'af67f8af9883076a98e2727a5849d467eca760feff175e05d1c1844786d1adce',
    'u_conn.go': '0f2ec1cd1b540d27f0cc81348a5bf9679a95ca36d02287fd157f36bae044e3ce',
    'u_handshake_messages.go': 'fc2fdbd35366813c037a7f2ba9193b196bed79d3fd02f01579e25f269c1da68b',
    'handshake_client_tls13.go': '3fed39a7d5ce8758063a5011243026ea6300575d896a7b5a97ec5b2efb781722',
}


def prepare(upstream: Path, destination: Path, assets: Path) -> dict:
    source = upstream.resolve(strict=True)
    target = destination.resolve()
    build_root = Path('D:/MCRF/Builds/Android').resolve(strict=True)
    if not target.is_relative_to(build_root) or target == build_root:
        raise ValueError('private TLS clone must be inside an explicit Android stage')
    if source.name != 'utls@' + PIN or target.exists():
        raise ValueError('wrong pinned uTLS source or occupied private destination')
    for name, expected in HASHES.items():
        if sha256((source / name).read_bytes()).hexdigest() != expected:
            raise ValueError('pinned uTLS source changed: ' + name)
    if any(p.is_symlink() for p in source.rglob('*')):
        raise ValueError('upstream source contains links')
    shutil.copytree(source, target)
    handshake = target / 'u_handshake_client.go'
    text = handshake.read_text('utf-8')
    start = text.index('func (hs *clientHandshakeStateTLS13) decompressCert(')
    end = text.index('\n// to be called in', start)
    text = text[:start] + (
        'func (hs *clientHandshakeStateTLS13) decompressCert(m utlsCompressedCertificateMsg) (*certificateMsgTLS13, error) {\n'
        '\treturn hs.mcrfDecompressCert49(m)\n}\n'
    ) + text[end:]
    for item in ('\t"bytes"\n', '\t"compress/zlib"\n',
                 '\t"github.com/andybalholm/brotli"\n', '\t"github.com/klauspost/compress/zstd"\n'):
        if text.count(item) != 1:
            raise ValueError('unexpected decompressor import shape')
        text = text.replace(item, '')
    handshake.chmod(0o600)
    handshake.write_text(text, encoding='utf-8', newline='\n')
    for name in ('utls-buffer49', 'utls-compression49', 'utls-hrr49', 'utls-buffer49_test'):
        (target / (name + '.go')).write_bytes((assets / (name + '.go.txt')).read_bytes())
    # The pinned reader otherwise accepts bytes after the compressed vector.
    messages = target / 'u_handshake_messages.go'
    text = messages.read_text('utf-8')
    start = text.index('func (m *utlsCompressedCertificateMsg) unmarshal(')
    end = text.index('\ntype utlsEncryptedExtensionsMsgExtraFields', start)
    part = text[start:end]
    if part.count('\treturn true\n') != 1:
        raise ValueError('unexpected compressed-message parser')
    text = text[:start] + part.replace('\treturn true\n', '\treturn len(s) == 0\n') + text[end:]
    messages.chmod(0o600)
    messages.write_text(text, encoding='utf-8', newline='\n')
    # The pinned ECH marshaller discards its helper's returned error. Never
    # report a successful build of an ECH Hello that could not be encrypted.
    conn = target / 'u_conn.go'
    text = conn.read_text('utf-8')
    old = '\t\tuconn.computeAndUpdateOuterECHExtension(inner, ech, true)'
    if text.count(old) != 1:raise ValueError('unexpected pinned ECH marshaller shape')
    text = text.replace(old, '\t\tif err := uconn.computeAndUpdateOuterECHExtension(inner, ech, true); err != nil {\n\t\t\treturn err\n\t\t}')
    conn.chmod(0o600);conn.write_text(text,encoding='utf-8',newline='\n')
    retry=target/'handshake_client_tls13.go'
    text=retry.read_text('utf-8')
    start=text.index('\t\t// Note: we don\'t support selecting X25519MLKEM768 in a HRR')
    end=text.index('\n\t}\n\n\tif len(hello.pskIdentities)',start)
    old=text[start:end]
    if old.count('hs.keyShareKeys = &keySharePrivateKeys{curveID: curveID, ecdhe: key}')!=1:
        raise ValueError('unexpected pinned retry key schedule shape')
    text=text[:start]+('\t\tkeys, shares, err := mcrfRetryKeys49(c.config, curveID)\n'
        '\t\tif err != nil {\n\t\t\tc.sendAlert(alertInternalError)\n\t\t\treturn err\n\t\t}\n'
        '\t\ths.keyShareKeys = keys\n\t\thello.keyShares = shares')+text[end:]
    retry.chmod(0o600);retry.write_text(text,encoding='utf-8',newline='\n')
    names = [*HASHES, 'utls-buffer49.go', 'utls-compression49.go', 'utls-hrr49.go', 'utls-buffer49_test.go']
    return {'pin': PIN, 'upstreamUnmodified': True,
            'files': {n: sha256((target/n).read_bytes()).hexdigest() for n in names}}


def verify_registry(xray_source: Path, assets: Path) -> None:
    # A tiny extracted registry avoids linking Xray runtime/config/dispatch into
    # lightweight Steer. Prove the FULL registry source stays upstream-exact.
    source = xray_source / 'transport/internet/tls/tls.go'
    if sha256(source.read_bytes()).hexdigest() != 'a6f1f93540fe13bed196d91b38883dcbe013d79f43284e508d6fb8452ffbd238':
        raise ValueError('pinned upstream fingerprint registry changed')
    body = source.read_text('utf-8')
    local = (assets/'registry49.go').read_text('utf-8')
    marker = '\nfunc init() {'
    if body[body.index(marker):].strip() != local[local.index(marker):].strip():
        raise ValueError('Steer fingerprint registry differs from pinned upstream')
    core = xray_source / 'core/core.go'
    if sha256(core.read_bytes()).hexdigest() != 'bd82bca5ca984bccdf5449d34d68bcfae0225822d7d8326bb4a8ddcb69d516fc':
        raise ValueError('pinned Reality version source changed')
