# P3 original-protocol helper — source package, not accepted binaries

For a single server, Steer remains the TUN/DNS/route owner. Explicit
multi-server routing retains MCRF's existing external dispatcher ownership;
each compact output has its own helper plus native Steer route frontend.
One separately installed,
same-signer `protocols` module runs the original TUIC, AnyTLS, Naive/Cronet or
SSH outbound from pinned sing-box-lx `3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d`.
There is no protocol substitution, imported TUN/API or anonymous loopback entry.
Naive requires actual statically linked CGO Cronet; omitting its build tag or
using the pure-Go alternative fails the producer.

## One common verification phase, after the whole P3/P4 source package

1. `Prepare-Source.ps1` freezes the SDK and four exact original Gitlinks,
   frontend, bridge types and physical-network overlay in a fresh owned stage.
2. `Prepare-CronetNotices.ps1` reads the pinned original notice-source checkout.
   It preserves a conservative source-notice superset, original copyright
   notices and referenced licence texts. Missing shipped references block it.
   The separate protobuf licence reference does **not** assert which protobuf
   version is linked. The Chromium source version must match the actual three
   static Cronet archive inputs recorded by the native producer.
3. `Build-Native.ps1` verifies dependency intake and frozen source, compiles the
   three Android ABIs plus the x86 owned Go fixture. It does not execute them
   or admit the result to APK packaging.
4. `Complete-NativeNotices.ps1` combines actual linked Go-module notices with
   the original static source notices. Missing notices remain a packaging
   blocker. This step does not recompile or replay completed native cases.
5. The opt-in `protocols-owned-fixture` project uses
   `protocolsOwnedFixture=true` and `protocolsFixtureStage=...` to package the
   exact native fixture for a disposable x86 emulator. It obtains an actual
   opaque Android physical-network handle; it does not invent a netId-derived
   handle, create a VPN or modify user connections.
6. `Invoke-OwnedFixture.ps1` runs only missing new cases. Four cases transfer
   bytes through actual original TUIC/AnyTLS/Naive/SSH client/server protocols.
   Other cases cover strict schema, trust, local authentication, pinned UDP
   source ports, cancellation, private diagnostics and actual Android socket
   binding. Passed cases/raw logs are retained. No old P1/P2 suites are replayed.
7. Only complete legal closure and all15 new native cases permit
   `COMPILED_OWNED_FIXTURE_VERIFIED`. Pass that stage to the product Gradle via
   `protocolsNativeStage` and `protocolsNativeGate=true`; it rechecks bytes,
   source/evidence hashes, all three ELF inputs and packaged notice files.

The fixture runs on x86 only; the two ARM ABIs are compiled, not represented as
executed. A descriptor/listener READY is not a successful remote tunnel. The
parent requires an actual HTTPS transfer and continued child/network ownership.
The Android31+ fixture does not raise the production module's Android29 target.

## Network ownership

Only the child binds its process/Cronet to the captured physical network. Go
uses raw socket syscalls, so the SDK overlay additionally binds each TCP/UDP
descriptor before connect/send. System bootstrap DNS uses
`android_getaddrinfofornetwork`, not the default network. Explicit DNS retains
its original endpoint. The main application stays inside the Steer VPN for
its tunnel-health check; the helper cannot silently fall back to that VPN.

The parent requires Steer's separately verified source-port-aware SOCKS module
before starting the helper. Existing Steer14 cannot advertise that capability.
Manual connection, serialized automatic ranking, explicit catalogue probes and
independent multi-route helper ownership are source-wired, not runtime accepted.
Ranking helpers are retired one at a time. The selected new helper is freshly
probed; final admission matches that same owner, original logical fingerprint,
private ingress, marks, module, network and completion time. A closed ranking
helper cannot lend its proof to a replacement. Phone acceptance is still
pending; source files here alone do not close P3.
