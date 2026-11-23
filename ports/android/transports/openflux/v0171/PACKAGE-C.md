# OpenFlux package C — source change, 31 October 2026

Scope: requested items 3–5, 13, 17, 18, with the later explicit integration grant
for EnginePackages/EngineUpdates/EngineStartupUpdates, engine update UI/callers,
packaging scripts/manifests/tests and OpenFlux registry/updater sources. MainActivity, deployment
UI, routing and traffic sources were not edited. There is no canonical Git
metadata; review compares captured before/after file text, not a Git commit.
No compilation, test suite, native/APK build, device action or live update was run.
Concurrent external edits were observed in ten protected UI/deploy/routing/
traffic files during the session. This package neither wrote nor reverted them.

Changed files: `app/src/main/kotlin/org/mcrf/client/OpenFluxProfile.kt`,
`OpenFluxProfileEditor.kt`; `app/src/test/kotlin/org/mcrf/client/OpenFluxProfileTest.kt`;
`transports/openflux/README.md`; `v0171/build.ps1`, `mcrf_scripts.go`,
`script_update.go`, `script_update_cli.go`, `script_lock_unix.go`,
`script_lock_windows.go`, `script_update_test.go`, this document; and
`transports/openflux/tests/OpenFluxProfilePackageCTest.kt`.

Integration follow-up also changes `EnginePackages.kt`, `EngineUpdates.kt`,
`EngineStartupUpdates.kt`, `EngineUpdateUi.kt`, `EngineUpdatePolicy.kt`; adds
`EngineUpdateOpenFluxScripts.kt`, `EngineUpdateOpenFluxScriptPolicy.kt`,
`EngineUpdateOpenFluxScriptUi.kt` and `EngineUpdateOpenFluxScriptPolicyTest.kt`;
and changes `engines/build.gradle.kts` plus
`engines/src/openflux/AndroidManifest.xml`. All client names are under the
usual `app/src/main/kotlin/org/mcrf/client` or corresponding `src/test` directory.

## Profiles and compact editor

- Preserve Telemost conference URLs, context, names, priority, codec, secret,
  negotiation and parameters. Endpoint metadata preserves scheme/port instead
  of treating every URL as HTTPS/443.
- CUPS uses `interview.cups.online`, Avito uses `code.avito.ru` as endpoint
  metadata. Packed legacy and striped v2 lists, grouped lists and `?rooms=`
  wrappers are validated without repacking their original wire strings. CUPS
  also accepts upstream's single `?room=` input. Avito needs complete room pairs.
- The destination `yandex.ru` is never substituted for a conference or room
  list. Invalid room input fails before launching a provider. No domain-wide
  direct exception is added. Metadata/fixture inspection does not establish
  that an existing routing rule sends real `yandex.ru` traffic into the tunnel.
- Mode and negotiation share a row. Context/codec and transport name/priority/
  parameter JSON are folded. Direct's dial control and other carriers' URL
  control follow the selected protocol. Add/remove actions have separate
  margins and at least 48dp height. Expansion changes no saved profile value.
- Ten JS presets match `scripts-lock.json.packages`. Native Avito/Telemost
  remain separate transport types. Unpublished mtslink-webrtc/vkteams cannot
  be imported or selected as JS packages. JSON settings remain editable.

## Explicit upstream script updates

The new CLI overlay is included only by the exact official v0.17.1 producer.
It uses the existing `--mcrf-script-config <absolute private request JSON>`
dispatch. Requests are bounded, reject unknown fields and cannot supply a URL,
author key, source commit, store directory or native executable. Actions:

```json
{"action":"status"}
{"action":"check","id":"yandex"}
{"action":"apply","id":"yandex","expectedSha256":"<SHA returned by check>"}
{"action":"rollback","id":"yandex","expectedSha256":"<verified previous SHA>"}
```

Keep the request in an owned `cache/mcrf-carrier-<UUID>/` directory and keep
stdin open while the request runs; EOF or the finite timeout cancels fetching.
The Android store is derived from that app root as
`no_backup/openflux-script-packages`, shared across disposable connection
directories. It contains no profile secrets. Windows fixture callers use their
isolated temporary directory; host mutation through the public updater is refused.
The ordinary engine update UI now invokes this production boundary through
EngineUpdates. EnginePackages verifies the installed dedicated APK's signer,
exact core source, interface, script API/signature declarations and native ABI,
then compares the extracted executable SHA with its entry in that signed APK.
The host identity is rechecked before and after each child call. Manifest
capability `org.mcrf.engines.openflux.scriptUpdater=2` and CLI
`official-pinned-v1` are required; historical candidate capability1 is separate
and never satisfies this caller. `MCRF_SCRIPT` is parsed privately and bounded.
Old modules show unavailable operations rather than being treated as upgraded.

UI lifecycle: inspect local state → check the selected official package → apply
the exact checked SHA while disconnected → reconnect with the ordinary connect
button. The next native session loads the selected, reverified package. The UI
shows the previous verified version and offers explicit SHA-bound rollback,
followed by the same reconnect step. It never stops/restarts a user's VPN
automatically, enrolls an author or substitutes an upstream APK/executable.
Cancel affects only this view's operation. Leaving/rotating the view cancels its
own work; cancellation after commit does not implicitly roll back a package.

The caller obtains the existing global EngineUpdateGate mutation lease before
apply/rollback, writes a durable exact-token intent (ID, before SHA, target SHA),
and keeps the barrier through child retirement and an independent local native
`status` inventory. That inventory acquires the native store lease and verifies
all ten current and retained previous packages, including signature/wire/API/VM.
Only an exact before/target receipt resolves an interrupted commit; EOF, timeout
or lost stdout alone never clears a barrier. A marker before the durable intent
was written is resolved only by a complete verified offline inventory. A marker
with a mismatched/corrupt intent stays blocked. Candidates are invalidated after
commit/recovery or host change; corrupted cached choices never grant trust.

EngineStartupUpdates and EngineUpdates.recover schedule this same local recovery
after app process death. Startup never fetches, applies or reconnects. A child
still holding the store lease, an invalid package or an unsupported replacement
module keeps the barrier closed. A lost response with a freshly verified exact
target digest is reported as committed; provider runtime acceptance remains false.

`check` verifies the embedded package's SHA and Ed25519 signature, reads update
sources from its signed manifest, fetches the stable index only over official
HTTPS repository paths, then downloads and verifies the named package. The
unsigned index grants no trust. Redirects allow only HTTPS official source or
GitHub asset hosts; responses are finite and credentials/TLS bypasses are absent.

Candidate admission requires the pinned official key; exact package ID/version,
SHA, baseline wire and API; a numeric stable version at least as new as the
current selection; and successful bounded upstream `Inspect` in the embedded
goja runtime after signature verification, including the callable open/write
contract without invoking those functions. Retags, downgrades, new author keys,
new API/wire and unpublished IDs are rejected. `apply` requires the exact
verified candidate SHA, rechecks the index and package, and refuses drift.
Mutation is available only on the producer's Android arm64/arm/x86_64 runtime.
This ABI gate concerns the running native host; `.flux` itself contains JS.

Both a JS session and an updater hold the same nonblocking kernel file lease.
No updater replaces a package while a JS session uses the store. Bytes are
stored under their digest, fsynced and selected by an atomically renamed and
directory-fsynced checkpoint; a previous checkpoint permits explicit rollback
with the same signature/wire/API/preflight checks. A corrupt current checkpoint
fails closed instead of silently reverting. Orphaned partial writes are not
selected. The store refuses growth beyond 64MiB rather than deleting unknown
files. A committed update is reverified and copied into the next session's
private staging directory. It never replaces the native core with upstream's
binary/APK or changes the pinned core source to `latest`.

`runtimePreflight=true` means metadata evaluation in the current VM succeeded.
Every report retains `runtimeAccepted=false`: no provider open/send, CAPTCHA,
TUN/DNS forwarding, account or device acceptance follows from inspection.
Rollback still requires a valid current checkpoint; repair of a corrupted store
is a separate explicit recovery operation, never implicit package adoption.

## Deferred acceptance

The prior source integration blockers are closed in source: packaging's exact
allowlist now contains all fifteen producer inputs, and it requires the
capability2/CLI receipt plus the additional signature/selection/inventory fixture
cases before packaging. The production manifest advertises this capability only
with the new pinned stage. Existing receipts/native files are not relabelled.
No such stage or signed APK was produced by this work.

The source fixtures were authored, not executed: `script_update_test.go` is in
the producer's future bridge suite; `../tests/OpenFluxProfilePackageCTest.kt`
must be included in the next permitted common Kotlin verification closure.
`EngineUpdateOpenFluxScriptPolicyTest.kt` covers receipt protocol/ABI/API/wire,
unsigned/unpublished IDs, exact target/before hashes and lost-response decisions;
these model fixtures also remain unexecuted.
The existing `OpenFluxProfileTest` now uses a real packed CUPS shape instead of
a generic website URL. Older tests outside this write-set may still use
invalid provider placeholders and must be reviewed before that common batch.

Required later: compile/source-bound host suite; three-ABI module packaging,
APK signature and 16KiB checks; actual Android CLI check/apply/restart/rollback,
EOF/cancellation/process-death/fsync/cache-eviction and concurrent lease behavior;
native layout on narrow screens/font scaling/IME with add/remove/edit flows;
Telemost/CUPS/Avito and each selected JS package on real peers, including DNS,
IPv4/IPv6, `https://yandex.ru` through the intended tunnel, rejection/fallback
behavior and setup/CAPTCHA. A fixture is never runtime traffic proof.

## Official sources consulted

- [Pinned official registry](https://github.com/p1neappleXpress/OpenFlux/blob/f492a1a33b7581ad8f88118f97e0b866adda3cdc/transport/registry/registry.go)
- [Packed room wire formats](https://github.com/p1neappleXpress/OpenFlux/blob/f492a1a33b7581ad8f88118f97e0b866adda3cdc/transport/rooms.go)
- [Telemost provider](https://github.com/p1neappleXpress/OpenFlux/blob/f492a1a33b7581ad8f88118f97e0b866adda3cdc/transport/yandex/telemost.go)
- [CUPS provider](https://github.com/p1neappleXpress/OpenFlux/blob/f492a1a33b7581ad8f88118f97e0b866adda3cdc/transport/cupsonline/cupsonline.go)
- [Avito provider](https://github.com/p1neappleXpress/OpenFlux/blob/f492a1a33b7581ad8f88118f97e0b866adda3cdc/transport/avito/avito.go)
- [Package signature/API contract](https://github.com/p1neappleXpress/OpenFlux/blob/f492a1a33b7581ad8f88118f97e0b866adda3cdc/transport/script/flux.go)
- [Restricted runtime inspection](https://github.com/p1neappleXpress/OpenFlux/blob/f492a1a33b7581ad8f88118f97e0b866adda3cdc/transport/script/inspect.go)
- [Official published package inventory](https://github.com/p1neappleXpress/OpenFluxTransports/tree/9349c01a4f2dd707f33f5a316beb9b56b77e56bd)

Local retained package metadata was inspected as ZIP text only, with no JS
execution. Existing `package-verification-receipt.json` is historical byte/
signature evidence; it was not rerun or relabelled as acceptance of these edits.
