# Android 2.0 / 10065 — final release pipeline

Target: ordinary release `android-v2.0`, authority in `coordination/release08.json`.
Internal function/property/stage names `release08` remain compatibility names.
This source migration does not build APK/native, install, publish or alter servers.
Historical candidate documents and receipts retain their original version identities.

## Grouped inputs and package contract

The five affected native families remain qwdtt/wdtt/csqtt/csqtt25/steer.
The exact packaged set is app/qwdtt/wdtt/csqtt20/csqtt215/csqtt/csqtt25/steer.
CSQTT auto mode requires all four packages: csqtt20 (2.0), csqtt215 (2.1.5),
csqtt (2.1.9), csqtt25 (SWG 2.5.1/2.5.2/2.5.3).

Future invocation, only under separate build authority:

```powershell
pwsh -File maintenance/Build-Release08.ps1 -CsqttLegacyStage <current-csqtt-wrapper> -Csqtt25Stage <current-csqtt25> -Csqtt25VersionsStage <verified-versioned-inputs> -Csqtt20Stage <historical-package-20> -Csqtt215Stage <historical-package-215>
```

Historical inputs use existing `Prepare-CsqttHistoricalPackage.ps1` package
stages: HISTORICAL_ENGINE_INPUTS_READY, pinned generations/revisions, six bound
artifacts (two per ABI), wrapper/native receipt hashes, licenses and package code 4.
The producer and source/pipeline gates require exactly `packageVersionCode=4`
and `packageVersionName=2.0-mcrf.performance.4` / `2.1.5-mcrf.performance.4`.
Catalogue APKs already use code 3; new bytes must advance to code 4 so startup
updates cannot select the old catalogue bytes at the same package version.
Create fresh package stages from verified native/wrapper inputs; never edit old
receipts to relabel their outputs. The final APK receipt also requires these versions.
Their Go wrapper receipt must match the current csqtt stage. Repackage with that
wrapper when required; retain unchanged pinned Rust binaries. An adapter/source
gate mismatch requires refreshed provenance, never a blind retained-byte override.
Gradle keeps the existing historical artifact/P3 performance checks and admits
these packages with the new source proof; consumed P2/P3 promotion grants are
neither needed nor reused. Module versions retain their own upstream identity
and monotonic package codes; 2.0/10065 identifies the main app and release.

## Bound receipts and assets

Use a fresh `D:/MCRF/Builds/Android/release08-<32 lowercase hex>` directory.
Each bound file is {path: absolutePathUnderD:/MCRF, sha256: lowercaseSHA256}.
The source manifest is a nonempty unique closure of current source files.
No credentials, service endpoints, user data or signing keys belong in archives.

The app/engine source gates require `-Prelease08Receipt=<source-ready.json>`
and `-Prelease08Sha256=<hash>`. Required proof: exact authority, SOURCE_READY,
versionName 2.0, versionCode 10065, releaseType release, publish false,
groupedAffectedBuild true, sourceManifest/sourceManifestSha256,
nativeReceipt/nativeReceiptSha256. Historical candidate authority cannot substitute.

Native receipt: AFFECTED_NATIVE_REBUILT, matching sourceManifestSha256.
`engines` contains exactly the five affected families, rebuilt true, sourceStable
true, bound sourceManifest and nonempty bound outputs with ABI.
`historicalPackages` contains exactly csqtt20/csqtt215, rebuilt false,
sourceStable true, bound packageReceipt and six bound outputs each.
APK native entries must match the corresponding output hashes.

Final receipt: BUILT_NOT_INSTALLED_OR_PUBLISHED, exact authority/version,
sourceStable/groupedAffectedBuild true, bound sourceManifest/nativeReceipt,
signer, assets, sourceArchive and releaseNotes. Public assets use
`mcrf-android-<ABI>-2.0.apk`, `mcrf-engine-<kind>-<ABI>-2.0.apk`,
`mcrf-android-2.0-source.zip` and `mcrf-android-2.0-engines.json`.
Each asset records kind, package, ABI, actual package versionCode/versionName
and SHA256. Require exactly one package per supported ABI: arm64-v8a,
armeabi-v7a, x86_64; Steer supports only arm64-v8a/x86_64. No 32-bit Steer
package or universal asset is invented by this pipeline.

## Install and acceptance

Future: `pwsh -File maintenance/Install-Release08.ps1 -Stage <stage> -Serial <device> -BaselineReceipt <baseline.json>`.

Baseline binds serial, installed versionCode, firstInstallTime, signer,
guardSha256, connectionCount 14, protectedHashes and connectionEvidence.
The already installed trusted read-only guard must confirm exact settings/data
before and after each update. All package/signature/version pairs are checked
before the first install; only forward updates and `install -r` are allowed.
Identical already installed APKs can be retained. Active VPN/services, downgrade,
changed protected data and repeated attempts stop the workflow. No data reset.

The selected ABI and installed receipt must include exactly all eight kinds.
On unsupported Steer ABIs installation fails closed rather than omitting a kind.
The install receipt is UPDATED_PRESERVED_ACCEPTANCE_PENDING, not UI acceptance.
A separately observed device-acceptance.json must bind build/install receipt
hashes, serial, 14 connections, preserved settings and actual PASS evidence.
Required cases: wdtt-empty-routes, route-default-vpn, turn-live-streams,
notification-server-ip, notification-proxy-hidden, random-foreground-theme,
steer-post-pr; traffic/upload for qwdtt, wdtt, csqtt20, csqtt215, csqtt, csqtt25.
Upload cases require measured mbps >=12. Source/static tests cannot supply PASS.

## Publication and release notes

Future: `pwsh -File maintenance/Publish-Release08.ps1 -Stage <accepted-stage>`.

Rechecks bound sources, installed-byte acceptance and signatures; creates
android-v2.0 as a draft, uploads app/source/checksums, and updates all seven
engine families in the enduring shared catalogue. Metadata identifies 2.0/10065.
Replacements are backed up; unrelated assets and Windows latest are preserved.
Publishes as ordinary release with --prerelease=false and --latest=false.
Partial attempts remain for inspection; no automatic clobber/retry.

User-facing notes cover CSQTT automatic generation selection; one/all engine
downloads; merged VPN/BS server cards; existing 2.0/2.1.5/2.1.9 and SWG
2.5.1/2.5.2/2.5.3; TURN routes, server/IP/stream display and appearance updates.

## Source-only verification

`pwsh -NoProfile -File maintenance/Test-Release08Pipeline.ps1`

Runs parser/static checks and synthetic receipt/ZIP fixtures only. It verifies
public identity, exact package/ABI admission and negative stale/missing/tampered
cases. It never invokes Gradle, native compilers, adb, GitHub or server tools.
Kotlin/UI/runtime verification belongs to the separate application review.
