# DNSTT lifecycle adapter — common verification pending

Upstream remains `www.bamsoftware.com/git/dnstt.git@v1.20260501.0`,
commit `0c5c52a57d899c05428c116898941761a2ed83c2`, CC0-1.0. The module archive
checksum and SHA-256 are pinned in `transport-lock.json`.

The original DNS/KCP/Noise/smux protocol is preserved. The TCP listener is
created before Noise setup, but authentication is not serviced until Noise
has authenticated the remote server. The existing local authenticated SOCKS
readiness check therefore does not by itself bypass Noise. New source bounds
this setup to 20 seconds and closes the listener when the smux session dies;
otherwise an idle child could remain alive and accept unusable streams.
STOP or stdin EOF still terminates the owned process and all of its sockets.

The app now emits a dedicated private native schema, without display/probe/
route metadata or device IDs. The native reader rejects unknown or differently
cased keys, duplicates, nulls, trailing JSON, oversized input, unsafe local
listeners, invalid resolver endpoints and unpaired remote credentials. Keys
remain private JSON fields, not operating-system command-line arguments.
Remote SOCKS negotiation has a deadline for both reads and writes. Existing
credential bytes, explicit resolvers and authenticated remote SOCKS behavior
are retained. Finite diagnostics distinguish config failure, setup timeout,
unspecified Noise handshake failure and session closure without dumping secrets
or claiming that every Noise failure means a wrong key.

`Prepare-Source.ps1` extracts only the pinned archive into a fresh owned tree;
`Apply-Adapter.ps1` checks exact upstream anchors. The old source-build entry
points delegate to the same patcher. No accepted stage or module cache is edited.
Host and native producers are gated on the complete P3/P4 source package.
Seven new Go config/auth/lifecycle checks and three JVM config/diagnostic checks
are queued. Accepted unchanged encrypted-DNS fixture receipts are retained,
not automatically replayed. New source-stage native/APK/device checks still
remain; no new binaries have been built or accepted.
