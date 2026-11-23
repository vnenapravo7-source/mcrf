# Owned QUIC fixture

This fixture uses the QUIC module already pinned by P4. It verifies QUIC v1,
TLS 1.3, the `mcrf-owner-quic-v1` ALPN and one exact 32-character nonce echo.

Build on Windows with the repository's restored Go 1.26.8 cache and Android
NDK r27c. Run `py -3.14 -B build.py --output D:/MCRF/Builds/Android/owner-quic-<id>`
from this directory during the authorized native batch. The producer verifies
dependencies, runs the owned loopback QUIC test, then builds the Windows peer
and three Android PIEs. The scope excludes provider servers, HTTP/3 and production use.

## Control protocol

Use `server --listen 127.0.0.1:0` for the Android P4 fixture. Keep stdin open.
The first line is a JSON `ready` event with `port`, `certificate` (unpadded
base64url DER) and `certificateSha256` fields. The one-session TLS private key
stays in server memory. Each completed nonce stream produces an `accepted`
event with `nonce`, `quicVersion`, `tls` and `alpn`. `SNAPSHOT` followed by a
newline requests a `snapshot` event with `accepted` and `datagrams` counters.
`STOP` followed by a newline retires the server and emits the final `stopped`
counter event. EOF or the 70-second deadline also retires the server.

The Android CLI accepts `client --network <handle> --certificate <DER>`
and requires `--certificate-sha256 <SHA-256> --nonce <32 lowercase hex>`.
It binds its UDP socket to the supplied VPN handle and the fixture's local
`172.19.0.1` address before dialing `198.18.0.2:18444`. It performs no DNS or
route substitution. The fixture supplies the UDP route from that synthetic
destination to the loopback server port. The optional `--lane wg` uses the
existing WireGuard test addresses `10.77.0.2` and `10.77.0.1:18444`.

The client emits a single JSON `client_result` line with a `status`. Exit 0
with `QUIC_COMPLETE` includes the exact nonce and negotiation parameters.
Exit 20 with `QUIC_BIND_DENIED` reports Android rejecting the VPN socket bind.
An unavailable local source address is a fixture setup failure. Exit 21
with `QUIC_HANDSHAKE_FAILED` reports that a bound socket did not negotiate
QUIC. Unexpected setup and stream failures cannot count as a denial pass.

The test-only helper exposes the caller-UID-checked message 9 with `nonce`,
`network`, `certificate` and `certificateSha256` fields. The only optional
field is `lane` with value `wg`. Message 9 replies
with `nonce`, `uid` and `code`. Message 4 confirms retirement. When the
producer stage and receipt SHA are supplied via `ownerQuicFixtureStage` and
`ownerQuicFixtureSha256` with the strict-owner source gate, the helper APK
uses version code 2 and extracts the exact executable into its library directory.
