# Native carrier stack — development overlay

Not production integrated or advertised. This additive factory uses the pinned
core's existing userspace IP device without WireGuard encryption, a kernel TUN,
a SOCKS listener, a Go-wrapper netstack or another transport child.

It is intended to preserve the normal native dial path, including ordered
routing and sniffing, over the same original carrier packet endpoint. Native
ports start at 16000, disjoint from the earlier raw-selector experiment. The
device owns IP fragmentation/reassembly and closes on owner cancellation.

`overlay.json` now records the complete 27-file closure over the pinned baseline,
including unchanged required p3 files. The p3 directory and frozen source tree
remain unchanged. `native-core/raw-overlay.py --overlay-kind p4` verifies this
closure and executed evidence before/after an explicit candidate build.

The ordinary SOCKS outbound wrapper deliberately does not implement FlowOutbound.
It preserves normal native routing/sniffing and profile DNS while replacing the
selected endpoint's dial/listen device. The device owns fragmentation/reassembly;
opaque packet transport preserves native DF/MF flags. Peer loss reports one fixed
internal failure and cannot silently retry through SOCKS.

The additive native check validates a borrowed FD using a temporary duplicate
without closing it or changing its shared file-status flags. Android's pending
module-owned duplicate is validated before the Go adoption ACK and registered
with CommandServer before service startup. The new adapter/Binder source compiles,
but its capability marker is still absent and the production bridge/session
activation and installed-module FD acceptance remain unfinished.

Fifteen Android-kernel package tests exercise native TCP, fragmented UDP, DNS,
registry bounds and FD/lifecycle behavior. A separate combined native/Go/UDP
fixture also passes; none measures CSQTT speed or contacts a CSQTT provider.
The old `test-receipt.json` describes only the earlier factory tests; use the
complete overlay and newer evidence for current source provenance.
