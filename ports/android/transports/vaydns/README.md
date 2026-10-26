# VayDNS mobile adapter — source work, not yet released

Pinned original: net2share/vaydns v0.2.8, CC0-1.0; identity is recorded in
`../transport-lock.json`. No code from restrictive third-party Android forks.

`Prepare-Source.ps1` verifies the pinned ZIP SHA256 and each extracted file,
copies only verified upstream entries into a new build directory, then adds
the MCRF client extension and CLI. It does not alter the module cache, compile,
run transport code, install, publish, or touch a server. The copied upstream
module retains its explicit KCP replacement; importing it into another module
without that replacement is not equivalent.

The CLI takes `--mcrf-config <private JSON file>`. Config is bounded to 64 KiB;
unknown/duplicate fields and trailing objects are rejected. Fields: domain,
key (32-byte hex Noise public key), exactly one of doh/dot/udp, socks (numeric
127.0.0.1 with a nonzero port), socks_user/socks_pass, optional username/password
for the remote SOCKS backend. Resolver TLS verification is never disabled.
The server must forward streams to SOCKS5, not SSH or an arbitrary TCP service.
No undocumented public import-link format is asserted here.

The SDK extension replaces the raw local forwarder with an authenticated
bridge. Local RFC1929 authentication precedes opening any stream. READY follows
Noise session creation AND remote SOCKS negotiation, not merely listener bind.
Remote credential/protocol refusal is terminal; transient failures use bounded
backoff and log only stage categories. There is one session owner, at most512
local handlers with pre-Accept backpressure, no concurrent SDK Close/reset,
no invented smux half-close.
STOP, stdin EOF and SIGTERM cancel work; a two-second process-exit bound remains
when an upstream handshake has not yet returned. No periodic external health
requests or VK calls are introduced.

App source now includes explicit JSON import, the existing DNS editor controls,
feature visibility and a dedicated same-signer/source-pinned module requirement.
Its private config strips display/probe metadata and requires backend READY;
the legacy DNSTT module is never used as a fallback. No VayDNS APK is published.

`Invoke-HostChecks.ps1` queues16 NEW checks, including a real owned loopback
DNS/Noise/SOCKS peer and stdin-EOF cancellation. `Build-Native.ps1` requires that
source-bound result before compiling three Android ABIs, checks ELF16KiB, and
collects legal notices for the actually linked dependency modules. Missing legal
notices block packaging. Gradle flavor/manifest and integration snapshot/export
wiring are present but disabled without the explicit completed native stage.

All scripts and checks above are source work, NOT executed yet. Still required:
the whole-package common coordinator, host/native/APK acceptance and actual
profile/editor/import/TUN checks. Nothing from this module has been published.
