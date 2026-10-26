# Slipstream optional Android module — source in progress

Wire protocol: EndPositive/Slipstream. The selected compatibility backend is
Apache-2.0 Mygod/slipstream-rust 0.1.1, pinned to
`7de506b222c83ae97a19a618e33fe26899af1bd5`, with its own picoquic revision
`d4a865c54929cfeb2a61902a0340b8758ad5492e`. This backend was archived on
28 August 2026; do not describe it as actively maintained. No SlipNet app code is
copied. MasterDnsVPN remains a separate requested protocol, not a substitution.

The original C client exposes an unauthenticated raw TCP listener and does not
verify the server certificate when no root store is configured. MCRF must not
ship those defaults. The pinned Rust backend supports exact leaf pinning; MCRF
makes it mandatory and replaces local raw TCP ingress with a filesystem Unix
socket in an owner-only directory. Its QUIC/DNS framing is not changed.

The Go frontend offers only authenticated loopback SOCKS5. Local credentials are
fresh per process, distinct from optional remote SOCKS credentials. Remote SOCKS
authentication is negotiated through the pinned tunnel before `MCRF_READY`.
Other local apps cannot reach the raw core through loopback or an abstract socket.
512 admission slots wait before accepting another client. Half-close and worker
cancellation are preserved. STOP, stdin EOF and SIGTERM terminate the owned core;
Linux/Android parent-death SIGKILL also prevents an orphan if the wrapper is killed.

Profiles use explicit JSON, not an invented upstream share URI:

```json
{
  "type": "slipstream",
  "name": "Slipstream",
  "domain": "t.example.test",
  "resolver": "1.1.1.1:53",
  "certificate": "<one PEM-encoded server leaf certificate>",
  "keep_alive_ms": 1000
}
```

Optional `username` and `password` refer to the server-side SOCKS5 service and
must be supplied together. The local listener/backend path cannot be imported.
Unknown, duplicate, null and trailing configuration values are rejected by the
native frontend. Domains and numeric IPv4/IPv6 resolver endpoints are validated.
No user-supplied argv, authoritative high-QPS mode, TLS bypass, SIP003 environment
override, inherited SSL key log or raw upstream diagnostics are accepted.

Keep-alive defaults to 1000 ms, configurable as 0 (off) or 400–60000 ms. Off is
not a claim of zero QUIC/DNS traffic; active traffic and protocol control remain.
Certificate mismatch is a terminal named error, not a silent insecure retry.
Remote authentication/protocol refusal is terminal too. Other diagnostics use a
closed vocabulary and child exit code, never the full config or credential text.

`Prepare-Source.ps1` verifies a clean exact Git checkout and copies tracked regular
files into a fresh owned source stage. Changes use unique exact anchors. The
shared MCRF RFC1929 implementation is copied from the VayDNS overlay, changing only
the Go package name. Source preparation has **not** been invoked yet.

New checks prepared, **not run**: three strict-config tests, three Linux private
relay/auth/half-close/cancel tests, two real owned QUIC/DNS tests (data and wrong
leaf rejection), two Linux parent-death/stdin-EOF tests, and three JVM
module/import/diagnostic tests. Wire fixtures
require coordinator-supplied pinned server/core binaries and fail if absent;
they never skip or connect to an external destination. The owned SOCKS endpoint
handles only a synthetic test address locally.

Still required before delivery: frozen native dependency/notice stages and
producer, Unix fixture runner and common Android acceptance. Gradle/integration
packaging now has source-only stage validation, not a delivered module. Existing upstream TCP test fixtures are
not claimed as passing after the private-UDS substitution. No native/APK has been
built or executed; the main app must not embed either native binary.
