"""Pinned private source overlay; never edits the Go cache or production binaries."""
from pathlib import Path
import hashlib, json, shutil, sys

root = Path(__file__).resolve().parent
module = Path(sys.argv[1]).resolve()
destination = Path(sys.argv[2]).resolve()
if root not in destination.parents:
    raise SystemExit("Overlay must stay inside optional-cores")
source = module / "common/dialer/fragment.go"
expected = "18b746395e68f7acd090ffd01766751e3153ef95c6d83defb495da0e00d3ef51"
if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
    raise SystemExit("Pinned upstream fragment source mismatch")
tls_source=module/'common/tls/utls_client.go'
reality_source=module/'common/tls/reality_client.go'
tls_expected='0d36e4cb61ad7da66a37427b2d1db853066c408a5dafd490a15705e101bfa8b7'
reality_expected='cb4fcbd0f76347282cd31e630271c02bb3c5273205b005f47987804e2adc4742'
if hashlib.sha256(tls_source.read_bytes()).hexdigest()!=tls_expected or hashlib.sha256(reality_source.read_bytes()).hexdigest()!=reality_expected:
    raise SystemExit('Pinned upstream TLS/Reality source mismatch')
if destination.exists():
    raise SystemExit("Use a fresh unique overlay directory")
shutil.copytree(module, destination)
default_relative = Path("common/dialer/default.go")
default_source = (module / default_relative).read_bytes()
if hashlib.sha256(default_source).hexdigest() != "c972b6cf339b8f711f4193337f1cf98895a4e426efb67783d738f6771039d744":
    raise SystemExit("Pinned TLS default dialer source mismatch")
anchor = b"\tif networkManager != nil {\n\t\tinterfaceFinder = networkManager.InterfaceFinder()"
if default_source.count(anchor) != 1:
    raise SystemExit("Pinned TLS default dialer control anchor differs")
controls = (b"\tif physical := mcrfPhysicalControl(ctx); physical != nil {\n"
            b"\t\tdialer.Control = control.Append(dialer.Control, physical)\n"
            b"\t\tlistenConfig.Control = control.Append(listenConfig.Control, physical)\n\t}\n")
default_target = destination / default_relative
default_target.chmod(0o600)
default_target.write_bytes(default_source.replace(anchor, controls + anchor))
shutil.copyfile(root.parent / "transports/protocols/overlay/mcrf_physical.go", default_target.with_name("mcrf_physical.go"))
shutil.copyfile(root / "patches/tls-physical-controller_test.go.txt", default_target.with_name("mcrf_physical_test.go"))
target = destination / "common/dialer/fragment.go"
text = target.read_text()
start = text.index("func (c *fragmentConn) writeFragments(")
end = text.index("\nfunc (c *fragmentConn) Write(", start)
text = text[:start] + (root / "patches/fragment-write.txt").read_text() + text[end:]
target.chmod(0o600)
target.write_text("// MCRF optional-core adaptation of throneproj/sing-box (GPL-3.0-or-later).\n" + text, newline="\n")
(destination / "common/dialer/fragment_mcrf_test.go").write_text((root / "patches/fragment_test.go.txt").read_text(), newline="\n")
tls_target=destination/'common/tls/utls_client.go'
tls_text=tls_source.read_text()
tls_start=tls_text.index('func (c *utlsALPNWrapper) HandshakeContext(')
tls_end=tls_text.index('\nfunc NewUTLSClient(',tls_start)
tls_text=tls_text[:tls_start]+(root/'patches/utls-handshake49.go.txt').read_text()+tls_text[tls_end:]
old='if len(options.ALPN) > 0 {\n\t\ttlsConfig.NextProtos = options.ALPN\n\t}'
assert tls_text.count(old)==1,'Pinned ALPN constructor changed'
tls_text=tls_text.replace(old,'if options.ALPN != nil {\n\t\ttlsConfig.NextProtos = options.ALPN\n\t}')
tls_target.chmod(0o600);tls_target.write_text(tls_text,newline='\n')
reality_target=destination/'common/tls/reality_client.go'
reality_text=reality_source.read_text()
# Preserve the actual selected template, including its hybrid share. The pinned
# fork removed MLKEM unconditionally, which makes modern Reality peers reject an
# otherwise valid Chrome profile. Do not replace the template or retry another.
filter_start=reality_text.index('\tfor _, extension := range uConn.Extensions {')
filter_end=reality_text.index('\n\tif len(uConfig.NextProtos) > 0 {',filter_start)
filtered=reality_text[filter_start:filter_end]
assert filtered.count('utls.X25519MLKEM768')==2,'Pinned Reality filter shape changed'
reality_text=reality_text[:filter_start]+reality_text[filter_end:]
unused='\t"github.com/sagernet/sing/common"\n'
assert reality_text.count(unused)==1
reality_text=reality_text.replace(unused,'')
old_key='\tecdheKey := keyShareKeys.Ecdhe\n\tif ecdheKey == nil {\n\t\treturn nil, E.New("nil ecdheKey")\n\t}'
new_key='\tecdheKey := keyShareKeys.Ecdhe\n\tif ecdheKey == nil || ecdheKey.Curve() != ecdh.X25519() {\n\t\tecdheKey = keyShareKeys.MlkemEcdhe\n\t}\n\tif ecdheKey == nil || ecdheKey.Curve() != ecdh.X25519() {\n\t\treturn nil, E.New("Reality fingerprint has no TLS1.3 X25519 share")\n\t}'
assert reality_text.count(old_key)==1,'Pinned Reality auth key shape changed'
reality_text=reality_text.replace(old_key,new_key)
alpn_start=reality_text.index('\tif len(uConfig.NextProtos) > 0 {')
alpn_end=reality_text.index('\n\thello := uConn.HandshakeState.Hello',alpn_start)
reality_text=reality_text[:alpn_start]+'\tif err := mcrfApplyALPN49(uConn, uConfig.NextProtos); err != nil {\n\t\treturn nil, err\n\t}\n'+reality_text[alpn_end:]
reality_target.chmod(0o600);reality_target.write_text(reality_text,newline='\n')
(destination/'common/tls/mcrf_alpn49.go').write_text((root/'patches/utls-alpn49.go.txt').read_text(),newline='\n')
# This older metacubex fork has the same "hybrid was always sent first" HRR
# assumption as refraction. Randomized identities invalidate that assumption.
# Apply the same bounded real-key helper to a PRIVATE exact source clone only.
meta_source=Path(sys.argv[3]) if len(sys.argv)>3 else Path('D:/MCRF/Projects/MCRF/work/toolchains/go-home/pkg/mod/github.com/metacubex/utls@v1.8.7')
meta_hash='1c4585ded101f8492b0a7ec0f17b10398930648ddcae164e8c71843ef96615c2'
if meta_source.name!='utls@v1.8.7' or hashlib.sha256((meta_source/'handshake_client_tls13.go').read_bytes()).hexdigest()!=meta_hash:
    raise SystemExit('Pinned metacubex HRR source differs')
meta_target=destination.parent/'metautls49'
if meta_target.exists() or any(p.is_symlink() for p in meta_source.rglob('*')):raise SystemExit('Unsafe private metacubex destination/source')
shutil.copytree(meta_source,meta_target)
meta_file=meta_target/'handshake_client_tls13.go';meta_text=meta_file.read_text('utf-8')
start=meta_text.index('\t\t// Note: we don\'t support selecting X25519MLKEM768 in a HRR')
end=meta_text.index('\n\t}\n\n\tif len(hello.pskIdentities)',start)
if meta_text[start:end].count('hs.keyShareKeys = &keySharePrivateKeys{curveID: curveID, ecdhe: key}')!=1:
    raise SystemExit('Pinned metacubex retry key shape differs')
meta_text=meta_text[:start]+('\t\tkeys, shares, err := mcrfRetryKeys49(c.config, curveID)\n'
    '\t\tif err != nil {\n\t\t\tc.sendAlert(alertInternalError)\n\t\t\treturn err\n\t\t}\n'
    '\t\ths.keyShareKeys = keys\n\t\thello.keyShares = shares')+meta_text[end:]
meta_file.chmod(0o600);meta_file.write_text(meta_text,encoding='utf-8',newline='\n')
retry_helper=(root.parent/'transports/steer-tls49/utls-hrr49.go.txt').read_text('utf-8')
if retry_helper.count('"crypto/mlkem"')!=1:raise SystemExit('shared retry helper import shape differs')
# This pinned fork intentionally vendors its OWN mlkem implementation. Keep its
# exact existing private-key type, not an incompatible stdlib replacement.
retry_helper=retry_helper.replace('"crypto/mlkem"','"github.com/metacubex/utls/internal/mlkem"')
(meta_target/'mcrf_hrr49.go').write_text(retry_helper,encoding='utf-8',newline='\n')
(destination/'common/tls/mcrf_hrr49_test.go').write_bytes((root/'patches/tls-hybrid-hrr49_test.go.txt').read_bytes())
print(json.dumps({"source_sha256":expected,"patched_sha256":hashlib.sha256(target.read_bytes()).hexdigest(),"directory":str(destination),
    'physical_dialer_sha256':hashlib.sha256(default_target.read_bytes()).hexdigest(),
    'tls_source_sha256':tls_expected,'tls_patched_sha256':hashlib.sha256(tls_target.read_bytes()).hexdigest(),
    'reality_source_sha256':reality_expected,'reality_patched_sha256':hashlib.sha256(reality_target.read_bytes()).hexdigest(),
    'tls_alpn49_sha256':hashlib.sha256((destination/'common/tls/mcrf_alpn49.go').read_bytes()).hexdigest(),
    'metacubex_hrr_source_sha256':meta_hash,'metacubex_private':str(meta_target),
    'metacubex_hrr_patched_sha256':hashlib.sha256(meta_file.read_bytes()).hexdigest()}))
