# MCRF optional cores

This directory contains source/build evidence for **separate, downloadable,
same-signer APK modules**. No worker is added to the main APK or production
engine libraries here. APK assembly, signing, download publication, installation
and device tests belong to the coordinator/engine owner.

`build.ps1 -Android` snapshots owned sources into a fresh private build directory,
verifies Go dependency checksums, applies the hash-checked TLS fragment patch,
runs host fixtures, and stages PIE executables for arm64-v8a, armeabi-v7a and
x86_64 using NDK 27.2.12479018, Android API 29 and 16-KiB ELF page alignment.
Its receipt is the authoritative candidate path/hash/size record. It never copies
into `engines/src/main/jniLibs`. `check-kotlin.ps1` compiles owned Kotlin against
cached app dependencies and runs OptionalCoreTest; this is not a Gradle APK test.

## Interface 1

* `--mcrf-describe` prints the JSON API/capability descriptor and exits.
* `--mcrf-check config.json` constructs and closes the native core, without
  starting listeners. Success is `MCRF_VALID`; invalid requests exit 1 with a
  generic message that does not quote private configuration.
* `--mcrf-config config.json` starts the core. Input is capped at 64 KiB and is
  `{api:1,socks:"127.0.0.1:PORT",socks_user:"...",socks_pass:"...",outbound:{...}}`.
  Credentials are 16–255 UTF-8 bytes each. Only one outbound is permitted.
* stdout `MCRF_READY` means the authenticated SOCKS listener is ready. It does
  **not** mean the upstream server has connected. The normal real HTTP probe is
  still required for server selection/connected status.
* stdin `STOP`, `NETWORK_CHANGED`, EOF, SIGTERM or SIGINT closes the core and
  listener. Network change requires recreation with fresh credentials.
* Xray exposes authenticated SOCKS5 TCP and UDP ASSOCIATE. UDP datagrams are
  bound to the authenticated TCP session's requested loopback source port; its
  dynamic loopback relay closes with that session. TLS core is TCP-only.
  No public ingress, user control API, log files,
  remote routing downloads, TUN or UDP relay is created.

`OptionalCoreProcess` checks Android signer, dedicated package metadata, native
API descriptor, exact upstream revision and native config before startup. Private
files are 0600 inside a fresh 0700 cache directory. The service must exclude its
own UID from the TUN when using a subprocess, including application allowlists;
the worker cannot use libbox's in-process FD protector. An explicit Boolean gate
prevents claiming VPN operation until that hook is supplied. Always close workers
on stop/failure/cancellation and recreate on underlying-network changes.

## Supported behavior and limits

Xray worker accepts canonical single-hop VLESS `vnext` with raw, XHTTP, WS,
HTTPUpgrade or gRPC; TLS/REALITY; optional Xray MUX. Vision requires raw TCP and
TLS/REALITY and cannot use MUX. Native validation follows the pinned qThrone Xray
fork; its short string IDs are converted by upstream to deterministic UUIDs.
Unsafe socket overrides, key log files, server REALITY settings, external
certificate files, multi-hop XHTTP downloadSettings and full arbitrary configs
are refused. Kotlin conversion rejects options that cannot be retained.

TLS worker uses the **actual qThrone-pinned sing-box fork**, including
`tls_fragment:{enabled:true,size:"10-100",sleep:"2-5"}` on the outbound and
`tls:{tls_tricks:{mixedcase_sni:true}}`. These are not `fragment_size`/`fragment_sleep`
fields on libbox lx.11. The native TLS implementation randomizes wire SNI through
its std/uTLS config while preserving the logical certificate hostname. No SNI
string replacement is performed by the MCRF Kotlin config builder.

The checked source overlay fixes upstream fragment writes returning wire byte
counts, ignoring short writes and overflowing fixed burst buffers. Sizes are
1–16384 bytes; pauses are 0–100 ms between groups of 2–5 records. Only ClientHello
record fragmentation is supported; the upstream `method:range` option is not
implemented and is refused. TCP Fast Open plus custom fragmentation is refused.
TCP TLS VLESS/VMess/Trojan/HTTP/SOCKS are supported by the wrapper schema; the host
fixture verifies a TLS HTTP CONNECT outbound. QUIC does not get TCP TLS knobs.

Xray plus custom fragment/mixedcase in a single selected hop is currently refused
explicitly. TLS worker does not implement Xray's XHTTP transport. A future
authenticated chain or a verified Xray dialer extension must close that combined
gate; neither module advertises it as available.

Use `worker.bridgeOutbound(tag)` so Xray keeps UDP support; the generic helper's
four-argument form defaults to the TCP-only TLS core. Public
`OptionalCoreProcess.check(context,request,cancelled)` verifies the module and
native configuration without starting listeners. Preflight every needed outbound
before accepting a launch, then retain one worker per referenced tag.

`OptionalCorePlan(source,settings)` now supplies that full-config preparation.
Check `plan.requiresWorkers` before applying app-UID VPN exclusion. Use
`OptionalCoreSession.check(context,plan,cancelled)` for launch preflight, then
`OptionalCoreSession.start(context,plan,cancelled,vpn,bypassConfigured)` from the
service/probe worker. It checks all requests before starting the first listener,
retains all workers, rolls them back on partial failure, and returns `session.config`
with only the planned output tags replaced. Main still applies its normal
route/DNS/TUN/core tuning and native validation. Close the session on every
failure/stop; network change closes it for recreation. Session config contains
generated SOCKS secrets and must not be logged.

For probes use pure `OptionalCoreProbe.needed(config,settings)` before queueing,
then `OptionalCoreProbe.start(context,config,settings,cancelled)` on the probe
worker. Its Closeable session exposes `config` and `neededKinds`, starts all
needed children with no TUN, and removes admin/custom inbound/periodic NTP state
from the copied base config. Close it in ProbeService's finally after closing the
base core service. The coordinator must retain the active-VPN physical-network
guard; this helper supplies no Android Network-handle socket binding.

Capture the same `CoreTuningSettings` for preflight, service and probe. Pass it as
the third argument to `OptionalCorePlan(config,optionalSettings,coreSettings)` or
the fourth argument to `OptionalCoreProbe.start(context,config,optionalSettings,
coreSettings,cancelled)`. The helper applies transport defaults before worker
creation, using original fragment flags for backend selection. Surface plan/probe
`notes`; replacing an output with SOCKS first would otherwise lose these defaults.

Native checks confirm uTLS/TLS-core insecure/sing-MUX shapes. The pinned Xray fork
has removed `allowInsecure`; Xray TLS with global/profile insecure is refused with
a clear validation error. REALITY uses its authenticated handshake/fingerprint.
Xray uses separate Xray MUX settings and its system CA/pins; Mozilla/built-in
sing-box fragment remain explicit Xray applicability limits. TLS core now
accepts optional root `certificate_store` (`system`/`mozilla`), advertises the
capability, and retains inline profile trust. The process checks capability
before enabling it; older signed modules need rebuilding from the new receipt.
TLS core built-in fragment defaults apply only without custom/explicit profile
fragmentation. NTP/control APIs remain in the base core; no extra timers/listeners.

`build.ps1 -Android -TestPeer` stages a debug-only `fixtures/<ABI>/liboptional_fixture.so`
alongside the new candidate receipt. Keep these fixture executables outside every
release APK/module. `OptionalCoreDeviceChecks.run(instrumentation,Consumer<String>)`
is the owned AndroidTest runner hook. It locates the peer only in the target/test
APK's extracted nativeLibraryDir, then verifies actual TLS record/SNI/uTLS traffic
and native Xray raw/MUX/XHTTP/REALITY/Vision peers on loopback. Missing debug peer
or missing new Mozilla capability reports explicit SKIP; those gates are not
passed. Coordinator owns its debug packaging and ADB execution.

`build-fixtures.ps1` refreshes only the debug peer in a unique staged directory,
leaving all current worker payloads and `current-candidate.json` unchanged.
`current-fixtures.json` identifies its receipt. The advanced helper now checks
1152-byte authenticated SOCKS UDP round trips for every Xray mode, refusal of a
foreign local source port, and relay shutdown when the TCP control socket closes.
Use this newer fixture instead of the old peer bundled beside the worker receipt;
an old peer without `udp_target` fails explicitly. Host receipt records eight HTTP
modes and five UDP modes; it does not establish Android traffic acceptance.
The current peer also reports completion and cumulative reception time of the
first ClientHello. The helper configures a fixed 20 ms fragment sleep and requires
at least 20 ms between the first received byte and complete ClientHello. This
corroborates delay during real TLS traffic; it does not measure individual packet
spacing. Exact per-burst sleep is verified separately by the native overlay test.
The latest separate fixture adds native WebSocket, gRPC and HTTP Upgrade peers:
eleven HTTP modes and eight Xray UDP modes. These transports emit upstream
deprecation warnings at the pinned commit; their behavior remains tested.
Kotlin-generated configurations for all three pass native preflight separately.
Kotlin checks use a unique output directory. Older frozen coordinator snapshots
retain their original peer coverage.

The UI card and encrypted `optionalCores` section are supplied as owned source,
but must be wired by the coordinator. Defaults are off. Explicit profile settings
take precedence and enabling a setting never downloads or installs a package.

## Provenance and redistribution

Lifecycle retirement now retries failed resources. Session close attempts every
worker and retains failures; process close retains its child/private directory
until death and safe cleanup are confirmed. `isAlive()==false` after a stop request
is not a cleanup acknowledgement. `isClosed()` reports completed retirement.
Local start/check/describe/rollback owners now block off the UI thread until
cleanup returns an actual ACK, using backoff from 25 ms capped at 500 ms without
an overall timeout that could lose the owner. Only then is the original error
rethrown, with interrupt status preserved. There is no additional pending-cleanup
exception contract for existing callers. Ordinary runtime close failures require
retaining and retrying the same assigned session/process before releasing external
TUN FDs or usage leases. Helpers never release main-owned FDs/leases themselves.
`check-kotlin.ps1 -PureOnly` compiles/tests in the configured D: scratch directory
and invokes no native process, Gradle, APK packaging, device or provider action.

qThroneMobile v1.4.2-beta.2 commit c0edb581a45b53c1064b927c25df80b2fd8a55dd
pins qThrone commit 93e4873c56c2285f647d0146a0df9c088f122910 in nb4a.properties.
Its core/go.mod pins the sing-box and Xray forks used here. `core-patches/mobile`
contains Android protection/geo/TrustTunnel adapters; it does not implement TLS
fragmentation or mixedcase. Those behaviors are in the pinned sing-box fork's
common/dialer/fragment.go, common/tls/std_client.go, utls_client.go and
common_extension.go. See source-lock.json and the complete transport go.sum files.

MCRF adapter/patch source is GPL-3.0-or-later. qThrone/qThroneMobile are GPL-3.0;
sing-box distribution requires its GPL-3.0-or-later license and the upstream
exception notice. Xray is MPL-2.0. Preserve the copied notices, attribution and
corresponding source/patch offer in each signed module. The pinned repositories
and module checksums must remain available alongside released artifacts.
