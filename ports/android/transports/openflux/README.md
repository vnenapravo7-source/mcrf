# OpenFlux Android bridge

Current contour C (31 October 2026): **official pinned v0.17.1**, repository
`p1neappleXpress/OpenFlux`, commit `f492a1a33b7581ad8f88118f97e0b866adda3cdc`.
The tag object's SHA is distinct: `5131040c49d42719c822ff297fa204404480a739`.
`v0171/pending-receipt.json` explicitly records **no new native/APK build**.
Historical binaries/receipts below do not attest this revision.

Active producer: `v0171/build.ps1`, reached through `build.ps1`. The three-ABI
output requires `-Popenflux0171Stage=<absolute stage>` for module packaging;
Gradle verifies producer inputs, dependency locks, source manifest, test logs
and actual native hashes. It cannot relabel the old libraries. HostOnly cannot
produce an acceptable native receipt. No producer was run for this source change.

Official JS runs through the actual upstream `script` registry and full goja
host API (HTTP/WS/UDP/WebRTC/httpserver/crypto/concurrency). The adapter preserves
session context, negotiation, names, priority and `params.settings`. It embeds
only pinned `.flux` packages from `v0171/scripts-lock.json`, verifies SHA and the
official Ed25519 signature before loading, and never executes imported paths or
accepts an imported author key. Known official `.js`/`.flux` paths are resolved
to package identity, not read from the other device. This is an MCRF extension
of `openflux://v1/`: upstream `share.Config` itself cannot preserve script params.
Custom-author enrollment and an automatic live transport updater are not advertised.
Package C adds an explicit `--mcrf-script-config` status/check/apply/rollback
boundary linked from the Android engine update UI; see [package C contract and
acceptance](v0171/PACKAGE-C.md). Its production capability2 requires the new
source-bound receipt and module. Source integration is complete; no native/APK
build or live updater execution was performed for these changes.

Native yandex/telemost and both legacy/v2 packed Cups/Avito room lists preserve
their exact wire values. Room lists are validated, including grouped lists and
`?rooms=` wrappers (`?room=` is CUPS-only). CUPS/Avito endpoint metadata names
`interview.cups.online`/`code.avito.ru`; Telemost retains its conference hostname.
These fields describe the carrier, not a bypass for the destination `yandex.ru`.
The dedicated editor places mode/negotiation together, folds rare fields and
spaces transport actions. Its JS presets match the ten pinned signed packages;
source-only mtslink-webrtc/vkteams are rejected before a connection starts.
Fork-only FluxFast is rejected with an explanation. Settings/Geo/QR and compact
auto-BS/auto-select/status controls are outside this change.

Pending: published packages for mtslink-webrtc/vkteams are absent from the
pinned update index; all runtime/provider/ABI/device checks await a permitted
build. The newer prompt API still needs a separately reviewed setup/CAPTCHA UI
adapter; finite connection-card errors expose that limitation without leaking
pages, credentials or cookies. The JS registry/host is real; source wiring and
signature verification do not prove provider connectivity.

Primary references: [release](https://github.com/p1neappleXpress/OpenFlux/releases/tag/v0.17.1),
[scripted transports](https://github.com/p1neappleXpress/OpenFlux/blob/f492a1a33b7581ad8f88118f97e0b866adda3cdc/docs/scripted-transports.md),
[official packages](https://github.com/p1neappleXpress/OpenFluxTransports/tree/9349c01a4f2dd707f33f5a316beb9b56b77e56bd).

## Historical evidence (not the active integration)

Release0.7 promotion,26Oct2026: ordinary module `org.mcrf.engines.openflux`
version14 uses pinned OpenFlux0.13.0/4f9c786cdd1d9fcd0c133e2e0fec0c5a12522610
through the explicit `openfluxFreshProduction`/`openfluxFreshAcceptance` gates.
The legacy default lock below is not the published module's source identity.
An actual installed module14 on Samsung Android16 passed encrypted8192byte
HTTP, mandatory SOCKS authentication, wrong-key refusal, STOP during handshake,
STOP after readiness and stdin EOF with process exit/stdout drain. Native
startup cancellation now returns through the dedicated executable boundary;
it does not claim an embedded-library goroutine drain. The exact APK/source
and phone receipts are in `D:/MCRF/Builds/Android/release07-245e566d90dd490bb31202c5c6612fc0`.
Experimental verification/ownHTTP/inline/script-enrollment capabilities remain
unadvertised. Live provider/CAPTCHA success and 32bit/x86 device execution are
not established by the owned direct-transport test. Historical notes follow.

The optional executable is built from pristine OpenFlux v0.4.2, commit `74cac6d47bf4c27947348ee957538a2c0728a485`. The source revision is locked in `../transport-lock.json`; dependencies are verified against upstream `go.sum`. The build copy is disposable and never changes the pristine checkout.

`mcrf_proxy.go` is compiled inside the upstream mobile module. It preserves the official share profile's secret, context, transport names, codec and strict negotiation. Stream-mode links use the upstream stream proxy. Android passes only the private JSON filename, not credentials, on the command line. The SOCKS listener is loopback-only with fresh session credentials. Readiness requires the encrypted carrier and authenticated listener, not merely an open port. After setup the monitor wakes once every two seconds.

Build: `powershell -NoProfile -ExecutionPolicy Bypass -File ports/android/transports/openflux/build.ps1`. `-TestServer` builds a separate debug-only exit fixture. `-HostOnly` compiles the host configuration validator and runs its tests. Android PIE executables use 16KB ELF LOAD alignment; execution on a 16KB-page device remains unverified.

Nine checks passed on Samsung SM-F741B / Android 16: official link key preservation, authenticated readiness, encrypted 8192-byte HTTP round trip, one intended origin, correct stop/cleanup and wrong-key refusal. The HTTP origin uses one real local interface address because the upstream gVisor stack treats 127/8 as its own loopback. Both tunnel endpoints are ephemeral fixture processes; no saved profile is replaced. This does not prove every provider, stream transport, system-VPN or remote deployment.

An isolated v0.7.3 candidate (`f12e981629a18c8c01007d59ac002254c02972bb`) is also built for all three ABIs, without changing the production lock or libraries. `-Commit <sha> -Version v0.7.3 -StageOnly -TestServer` stages candidate executables; the engine Gradle candidate properties allow only a separately identified debug APK, never a release artifact. Its source repository/version/commit are in the OpenFlux-only manifest. On 2026-10-14 the independent candidate passed a real encrypted 8192-byte HTTP round trip, authenticated SOCKS5, wrong-key readiness refusal and STOP/cleanup on the phone. The temporary package was removed, leaving the production engine unchanged. This is a compatibility gate, not a completed automatic-updater or provider/full-VPN certification.

GPL-3.0-or-later. Upstream license is included in the engine APK. Any public binary distribution must include the matching bridge/upstream source and dependency/license notices.

## Android update queue (source implementation, integration/device gates pending)

`EngineUpdates` resolves the newest numeric stable release in the original repository and resolves its tag through GitHub's commits API; `target_commitish=main` is never used as an immutable revision. The separate MCRF APK is obtained from the MCRF release channel. Download acceptance requires its exact GitHub size/digest, main-app signer, dedicated package identity, stdio interface, OpenFlux interface, ABI/native allowlist and signed manifest source version/commit. Native upstream executables or an OpenFlux Android application are never substituted. Signed APK source metadata is a release provenance contract, not proof of a reproducible build.

Checking/downloading every six hours defaults on **for a trusted installed dedicated OpenFlux module** (`MainActivity` preference `engineUpdatesAutomatic`, default true). A saved explicit false is preserved; absent modules do not trigger surprise downloads/jobs. A newly scheduled job also starts asynchronous discovery immediately; opening the app does not postpone an existing job. New verified APKs are retained in private `noBackup/engine-update-queue`, after an exact digest/size copy, file fsync and same-directory rename. Existing cached queue checkpoints remain readable. A foreground user action opens Android consent after connection/probe activity has stopped; it creates a newly verified read-only provider cache copy while retaining the durable original until the exact installed receipt. Android cache eviction therefore does not lose a new queued update. Receipt/cancellation removes only the registered queue/cache copy. No background job opens an installer. Cancellation does not accept an old installed APK; acceptance hashes the exact installed APK. A previous separate APK is retained privately for export. Android downgrade restrictions prevent a promised automatic APK rollback. No optional executables are added to the base APK. Filesystem power loss or a crash between file commit and preference checkpoint still needs an Android gate; host simulated eviction is not that proof.

Coordinator integration is mandatory: declare `EngineUpdateService` with `BIND_JOB_SERVICE`, call `EngineUpdateSchedule.schedule` and `EngineUpdates.recover` on startup/return from installation and schedule after package changes/restore/reset, register the boolean preference with the backup registry **default true**, and reject new connection/probe starts while `EngineUpdates.installationPending(context)` is true. `EngineUpdateUi.show(activity)` is already linked from the engine sheet. Final Gradle/minified/device/rotation/process-death/installer-cancel gates are centralized; isolated compilation is not a substitute.

Upstream v0.7.3 has a **separate** signed `.flux` carrier updater, exposed to its native mobile host as `CheckScriptUpdate`, `ApplyScriptUpdate` and `RollbackScript`. It checks pinned/official author keys, wire/API compatibility and preserves `.flux.prev`. Production MCRF v0.4.2's executable accepts only `--mcrf-config`, and the current Kotlin profile validator excludes `script` transports. Therefore this change deliberately reports `.flux` updates as unsupported by the installed bridge; it does not pretend that an APK check updates signed carriers. Supporting them needs a gated newer production module with an explicit stdio updater capability, persistent script directory/key registry and coordinated profile/runtime support. The tested v0.7.3 encrypted-direct candidate has not passed those gates or been promoted. No published compatible APK currently exists (GitHub checked 2026-10-14).

## Isolated signed-script candidate capability

`build.ps1 -HostOnly -StageOnly -ScriptUpdaterCandidate -Version v0.7.3 -Commit f12e981629a18c8c01007d59ac002254c02972bb` builds/tests the host overlay. Drop `-HostOnly` and supply the NDK path plus `-TestServer` to stage all three Android ABIs and encrypted-direct exit fixtures. The script updater overlay is refused for any other source identity or a non-staged build. Production source pins and JNI binaries remain untouched. The stage emits `script-updater-capability.json` with exact ABI executable digests; candidate engine packaging verifies all digests and advertises manifest capability1 only for that stage. Old candidates/production advertise0. All Gradle/device packaging remains coordinator-owned.

The candidate accepts `--mcrf-script-config <privateJSON>` with action `capabilities`, `inspect`, `check`, `apply`, `rollback` or `recover`, emitting one `MCRF_SCRIPT` JSON response. It delegates cryptographic verification, official key rotation, signed id/version/wire/API matching, atomic install and `.prev` rollback to the original upstream script package. Installed source/version/update URLs are derived from the **signature-verified current `.flux`**, rather than unsigned request/index metadata. Requests are bounded, filesystem targets reject path traversal/symlinks, network requests enforce HTTPS and bounded responses on every redirect, and stable index SHA-256 is mandatory. Active mutation is deferred, automatic apply requires upstream `AutoOK`, manual/third-party/wire changes need explicit consent, and rollback requires consent. This CLI never installs APKs or edits user profiles.

`EngineUpdateScripts.callCandidate` is an off-UI Kotlin adapter restricted to the exact same-signer candidate package/source/capability; it cannot activate this capability against production. It stores scripts in canonical private `noBackup` storage, provides bounded I/O/timeouts/cancellation, and exposes `mutationPending()` for coordinator connection/probe guards. The caller must supply already-pinned signed scripts and a real active-state callback. Native host signed update/rollback/negative trust tests and the host capability handshake passed; all three Android ABIs compiled. A device-local signed enrollment/key registry is implemented for the candidate; signed-file inspection never executes JavaScript. Actual phone capability/enrollment and script profile/runtime integration remain pending. The full original script package's93 test/subtest pass events were also recorded in the isolated test output; those are host fixture checks, not Android/provider E2E.

The staged updater now holds a **kernel lock** (Android `flock`, Windows `LockFileEx`) instead of treating existence of `.mcrf-update.lock` as ownership. A live owner is blocked; exit or forced process termination releases ownership without deleting another process's lock file. A real killed host subprocess gate passed. Explicit idle/consented action `recover` repairs only a missing-current rename gap from a signature-verified `.prev`, requiring the saved `expectedId` and `expectedWire`; it never adopts an unverified `.new`, and it never overwrites an existing current package. Recovery/rollback reject another signed transport ID or unapproved wire change; current-presence success is signature-verified. Only pinned keys or already-compiled official rotation keys are accepted; a changed official recovery key is returned as `newKey` for the future trust registry to persist. Host simulated interrupted-rename, bad signature/ID/wire, refusal while active, and idempotence gates passed. Actual Android crash/filesystem/power-loss durability remains pending; cancellation near the atomic commit boundary requires a subsequent verified receipt, rather than an unconditional claim that nothing changed.

`EngineUpdateGate` now provides nonblocking OS-backed shared runtime leases and exclusive APK/script mutation leases, with a durable marker that survives the installer parent process. Core/probe cleanup must precede shared-lease release. APK receipt, exact package absence after uninstall, or a matching system cancellation result clears the marker; elapsed time and focus do not. Candidate native mutations also hold the script kernel lock; stdin EOF cancels pending network work after parent death. The coordinator Android :probe lease/cleanup fixture passed five checks; external installer cancellation/process-death and candidate signed-script phone checks remain pending.

An interrupted candidate mutation can reacquire its existing exclusive application gate using the exact retained token. This preserves the barrier while explicit signed `.prev` recovery runs; it neither replaces the marker nor admits runtime usage. A failed launch, blocked recovery or invalid signature leaves it pending. `EngineUpdateScriptRegistry.updateCandidate(..., action="recover")` clears an interrupted barrier only after fresh native inspection of all registered signed packages and an atomic registry receipt. Host file-lock tests include a separate recovery JVM killed while holding the lease; the original marker remains. Android signed-script recovery acceptance remains pending.
