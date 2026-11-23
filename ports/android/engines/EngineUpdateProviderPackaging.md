# Private ARM64 provider diagnostic package

Coordinator-authorized source hook:

`-PopenfluxProviderStage=ABS_FROZEN_VERIFIED_NATIVE -PopenfluxProviderGate=true`

The stage root contains `provider-diagnostic-candidate.json`,
`arm64-v8a/libopenflux.so` and `OpenFlux-LICENSE`. Main may copy this frozen stage
into canonical work for immutable capture; the hook requires an absolute path.

Configuration gates the exact upstream v0.7.3/f12e commit, reviewed patch SHA
4764e6ad..., ARM64 native e687063d.../27428656 bytes, license 230184f6.../35823
bytes and private CLI/prefix metadata. All hashes are full literals in Gradle.
Only this one native path may exist in the stage. Other OpenFlux ACK/promotion/
script candidate paths are mutually exclusive. Only the OpenFlux variants remain
enabled, with ARM64 output enabled and v7/x86_64/universal outputs disabled.

Output package `org.mcrf.engines.openflux.diagnostic`, versionCode 8,
versionName `0.7.3-mcrf-diagnostic.8`. Metadata source.version `v0.7.3`, commit
`f12e981629a18c8c01007d59ac002254c02972bb`, source.overlay
`mcrf-provider-stage.1`, provider-diagnostic 1, provider-diagnostic-cli
`diagnostic-config-v1`, script-updater 0. The source receipt retains the underlying
ACK overlay provenance separately. Ordinary module builds advertise diagnostic 0.

Generated assets copy only the reviewed license into `licenses/OpenFlux-LICENSE`;
the receipt, inspection output and source files are not bundled. Fixtures are not
staged. Production v7/default package/native inputs/transport-lock remain intact.

Main runs the shared build and checks that exactly one ARM64 APK exists, with
CURRENT signer, exact native/payload/metadata/license and APK receipt. Main owns
the DEBUG candidate reader, private flag propagation, install and separately
approved profile diagnostic run. Source/native preparation is not HTTPS success.

V2 has a distinct exclusive source hook:

`-PopenfluxProviderV2Stage=ABS_FROZEN_VERIFIED_NATIVE -PopenfluxProviderV2Gate=true`

The root contains `provider-diagnostic-v2-candidate.json`, the single ARM64
libopenflux.so and the same reviewed license. Gate pins the base V1 patch 4764e6ad,
V2 delta 25ba2ab1, new native 75924590/27433616 bytes and existing license. V1/V2
paths cannot be combined. V1 parameters retain their version8/pins/metadata.
V2 outputs the same private diagnostic package with versionCode9,
versionName `0.7.3-mcrf-diagnostic.9`, source.overlay `mcrf-provider-stage.2`,
provider-diagnostic2/provider-diagnostic-cli `diagnostic-config-v2`, script-updater0.
Both paths remain ARM64-only with only generated OpenFlux license and no main
assets. Main must build and verify exact output receipts before installation.

V3 uses the separate exclusive hook:

`-PopenfluxProviderV3Stage=ABS_FROZEN_VERIFIED_NATIVE -PopenfluxProviderV3Gate=true`

Root receipt `provider-diagnostic-v3-candidate.json` pins the V1/V2/V3 patch
chain (4764e6ad/25ba2ab1/eb444448), ARM64 native26af028a/27439984 bytes and
the same license. V1/V2/V3 stages cannot be combined. V3 uses private package
versionCode10/name `0.7.3-mcrf-diagnostic.10`, source.overlay
`mcrf-provider-stage.3`, diagnostic3/CLI `diagnostic-config-v3`, script-updater0.
V1/version8 and V2/version9 retain their exact prior pins and parameters.
The ARM64-only, generated-license-only, main-assets-empty restrictions are shared.
Main owns shared compilation, exact signed APK verification, reader/device gates.
