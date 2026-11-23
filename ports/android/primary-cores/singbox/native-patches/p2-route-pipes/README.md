# P2 private route-pipe source patch — UNCOMPILED / UNRUN

No live upstream, public native library, phone or profile is modified by these
files. The patch must be applied only to an exact pinned frozen sing-box source
copy. Whole-P2 readiness remains false. Mechanical schema presence is not native,
Android, routing or WG/AWG acceptance.

## Roles

One existing external sing-box dispatcher owns the Android TUN, original-app
classification, route priority and DNS. Steer-selected protocol connections use
the actual external Steer private authenticated stream backend. Compact WG/AWG
connections use the actual compact WG worker for encryption/endpoint sockets.
Its private IP datagram channel is adapted to flows by the network stack already
present in the external dispatcher. This adds neither native code to the base
APK nor gVisor/full-singbox dependencies to the compact WG module. It creates no
second Android TUN and never substitutes sing-box protocol encryption for a
selected native backend. Runtime planning/UI and main-session wiring remain OPEN.

The existing exported `wireguard.NewDevice(System=false)` supplies only its
stack-device API here; no `device.NewDevice`/WG protocol engine is constructed in
this helper. The assigned host addresses and MTU are captured from the chosen
WG profile. Addresses are never replaced with their masked networks.

## Private admission and lifetime

`McrfAttachPacketPipe` accepts only an out-of-band borrowed AF_UNIX DGRAM
descriptor with exact kernel peer PID/UID, nonblocking status, generation, tag
and fresh nonce. It duplicates the fd, never changes shared flags, and does not
resolve names or dial an Internet destination. Numeric destinations are required
by the stack client. Destination DNS must be resolved by the chosen route before
entering this backend; bootstrap DNS remains a separate captured physical path.

`config.go` changes one registry call. The `mcrf_packet_pipe` outbound cannot be
used by a public imported profile without the authenticated native attachment.
Repeated `CheckConfig` constructs/closes unstarted wrappers without consuming the
packet owner. Only actual `StartStateStart` claims the exact slot once and seals
new attachment admission. A failed slot remains a tombstone; it cannot fall back
to another server or be resurrected. STOP joins constructors and stack/packet
goroutines, but main must still observe actual fresh worker death before releasing
platform, files and module leases.

The optional Kotlin adapter requires exact signed-module metadata
`org.mcrf.engine.packet-route-bridge=owned_pipe_stack_v1` AND the new native schema.
Old/public metadata do not gain the capability. Native methods are reflected, so
the ordinary adapter can still compile against an old AAR. Attach uses private
Binder fd transfer, original parent PID and exact settings fingerprint; it is
pre-START only. Dropping a failed output never reroutes its traffic.

## Verification still required

Queued sources: 3 pure address/frame policy cases, 3 real stack/private-channel
cases (dual-family UDP, actual pending TCP cancellation, ownership/name refusal),
and 5 registry/lifecycle model cases. A model is not packet or encryption proof.
Real stack cases use only a fake private peer; they are NOT WG handshake tests.

Source-bound preparation/build runner, native API/ABI assertions, candidate-only
metadata packaging and the full mixed-session integration still need completion.
Never reuse P0/P1 device authority or rerun unchanged closed suites as new proof.
# Numeric protocol bridge continuation (SOURCE ONLY)

`mcrf_numeric_socks` is a closed authenticated loopback-only schema for the
actual external Steer protocol workers. Ordinary SOCKS5 forwards FQDNs as-is;
route resolve actions alone do not cover direct outbound calls such as URLTest.
The wrapper therefore resolves through the EXISTING dispatcher's DNS router,
retaining metadata, before numeric TCP/UDP dialing. No kernel/default-DNS or
alternate-server fallback exists in this wrapper. Numeric targets never query.
Three added resolver/metadata model cases are NOT native protocol acceptance.
The native producer now requires 14 Linux cases and `mcrfNumericSocksSchema`.
Optional APK marker/actual generated API must both agree before admission;
public/base/installed module metadata remain unchanged. Packaging/runtime OPEN.

