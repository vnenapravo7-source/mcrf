# Android comparison with the Windows MCRF IP adapter

Source comparison, 23 October 2026. No new benchmark or runtime acceptance is
claimed. Windows source is read-only reference; its files are not modified.

| Area | Android accepted source | Change in the pending P3 source package |
|---|---|---|
| CSQTT RAW IP hop | `csqtt_bridge.go` creates netstack from MTU1300, then dials local UDP without buffer tuning | Clamp actual RAW device MTU to1280, retaining smaller server values; request4MiB receive/send only on the shared local socket |
| QWDTT RAW | In-process packet device, without the Windows extra UDP hop | Keep in-process design; apply1280 ceiling at actual device construction, not an extra adapter process |
| QWDTT/WDTT WG local IP hop | Shared local UDP socket requests625KiB | Request4MiB on this socket; retain625KiB per TURN worker and existing queue sizes |
| WDTT SOCKS |64 clients; accept then forcibly close overflow |512 slots, wait before Accept; preserve local auth, UDP association, cancellation and half-close code |
| CSQTT/QWDTT SOCKS | Authenticated `serveBridgeSOCKS`, no fixed64/reset gate | Keep it; do not replace with Windows server or add a lower ceiling |
| Calls | Android bridge already retains pools larger than32 | Preserve unlimited stored hash pool; no Windows32-call cap |
| Empty route group | Explicit carrier final outbound survives `ProfileConfig` and `RouteRules`; explicit direct/block remain supported | Add protocol-parity regression checks, not a blanket force-proxy override |
| Fake-IP / IPv6 / DNS | Different native sing-box/Steer DNS and routing pipeline, not Windows synthetic-address bridge | Keep native handling; address-family parity remains a separate P3 acceptance task |

Android kernel buffer requests may be clamped. Failure to enlarge an optional
buffer produces a warning and keeps the working socket; no privileged socket
options or global sysctl changes. The main Android TUN MTU and other protocols'
MTU are not changed. WDTT's existing WireGuard MTU1280 is retained.

`Apply-PerformanceSource.ps1` only rewrites a new owned source copy, with exact
anchors. It never patches accepted build inputs, upstream/pristine source, or
the Windows client. `Prepare-TurnSource.ps1` copies and hashes the exact retained
CSQTT/QWDTT/WDTT Go source; CSQTT2.5 preparation uses the same overlay. Historical
CSQTT packages must consume the new shared Go wrapper when repackaged, preserving
their distinct Rust/native generations and ownership contracts.

Queued new checks, not run: MTU bounds, owned socket options, admission queue
resume/cancellation, actual bulk TCP upload over paired RAW netstacks, real WDTT
listener512+queued513, empty-route carrier parity and explicit direct/block.
Actual Android socket limits, native/APK checks and controlled upload/download
measurements remain for the common verification batch. Windows throughput numbers
are not Android acceptance, and an upstream TURN limit cannot be repaired by
claiming the local adapter has been measured faster.
