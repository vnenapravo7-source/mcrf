# SSTP optional Android module — pending common verification

Source: the SwiftSSTP carrier in ttrroyy/qThroneMobile PR #5, exact commit
`8463d3397b0a8522f92811f5c0698679ab8efc77`, `carriers/sstp`, MIT license.
The original protocol/PPP/crypto and virtual network stack are retained.
No code or prebuilt APK is taken from an unrelated client.

MCRF replaces the standalone command frontend. Profiles contain an address,
port (443 by default), username, password and optional SNI. Standard TLS chain
and hostname verification are required. An optional PEM CA bundle allows a
private server without disabling certificate validation. Insecure links fail
with an explanation rather than silently changing their meaning.

Import supports explicit `type: "sstp"` JSON and the upstream URI shape:
`sstp://user:password@vpn.example.test:443?sni=cert.example.test#name`.
URI credentials are percent-decoded without treating literal `+` as a space.
The scheme is registered for Android and accepted by the common import gate;
complete SSTP links are redacted from logs. Local listener/auth credentials,
arbitrary TLS modes and file paths cannot be supplied through imported profiles.

The separate module exposes only RFC1929-authenticated loopback SOCKS5. Local
credentials are mandatory and fresh per process. Admission is bounded to 512
workers with pre-Accept waiting. The native JSON reader rejects oversized,
duplicate, unknown, null and trailing values, invalid endpoints and CA data.

STOP, EOF and SIGTERM cancel setup immediately, including the post-TLS HTTP and
SSTP control handshake, which the upstream CLI did not bind to caller cancellation.
A close extension retains an SSTP Disconnect attempt but unblocks a stalled
packet writer within one second. Owned local sockets, virtual stack and session
workers are closed/drained. PPP setup preserves timeout errors in the error chain.
IPv6 is offered but used by the stack only if actually negotiated by the server.

Only finite, adapter-authored diagnostic codes are exposed: certificate trust,
hostname mismatch, TLS, HTTP, SSTP control, authentication, timeout, local-stack
or session failure. Original log output and credentials are not forwarded.

Preparation, host and three-ABI native producer scripts are source-only, gated
by the complete P3/P4 common batch. Source/hash inputs are frozen; Android ELF
identity/16KiB alignment and notices for actual Go dependencies are required.
Gradle and integration packaging are wired but disabled without a complete stage.
Neither native payload is embedded into the main application.

New checks prepared, not run: three config/error tests; cancellation during an
owned post-TLS handshake; a stalled Disconnect; and a local TLS/SSTP/MS-CHAPv2/
PPP/IP exchange with actual crypto-binding verification. The owned peer echoes
synthetic IP data only inside its TLS socket and never forwards to the Internet.
The host recipe also includes this newly introduced engine's original crypto,
PPP and wire cases; these are not already accepted MCRF checks being replayed.
Five new JVM tests cover module/config, URI import, TLS policy and log redaction.

No producer, host test, compiler, native payload or APK has been executed yet.
Real Android module, UI and traffic acceptance remain for the common batch.
