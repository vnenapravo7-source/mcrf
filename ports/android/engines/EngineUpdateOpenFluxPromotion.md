# Reviewed fresh OpenFlux module for the intermediate APK

Coordinator explicitly authorized scoped stage promotion to the existing dedicated package, not a production source/binary overwrite and not script-update activation.

Use both:

- `-PopenfluxPromotionStage=ABS_VERIFIED_NATIVE_DIRECTORY`
- `-PopenfluxPromotionGate=true`

The reviewed directory is `C:/Users/dieonfire/Documents/ChatGPT/MCRF/work/ports-test-2026-10-14/openflux-build-v0.7.3-0db6c96b5d5543c897828b8f80195722/verified-native`.

Exact source: upstream `p1neappleXpress/OpenFlux`, v0.7.3, commit `f12e981629a18c8c01007d59ac002254c02972bb`. Public release/immutable tag commit rechecked on 2026-10-14. Upstream release has desktop executables, not an MCRF Android module.

The gate validates the local capability marker identity and these hard-pinned binary hashes:

| ABI | SHA-256 |
|---|---|
| arm64-v8a | 3bc4d237b183b44bc50906416720fae3f46173c6394d213a029961fff790686d |
| armeabi-v7a | 7ffd9ee6fd913491ca3a47961672058890c0b11f93cf4cee8fe210a448800957 |
| x86_64 | 82e853e6b6020723d5fbd152a6700da9303a95ca3cf48921616f9631cc11f495 |

Output remains `org.mcrf.engines.openflux`, versionCode 6/versionName 0.7.3-mcrf.6, with source version/commit metadata. Interface/openflux API remains 1/2; private `--mcrf-config` CLI unchanged. Native byte copies are confined to generated module build output. Production JNI/transport-lock are unchanged. Debug exit fixtures are NOT included by this promotion path. Candidate and promotion properties cannot be combined.

The binary includes the previously staged script CLI, but the promoted package advertises script-updater=0. No production `.flux` enrollment/job/runtime/UI activation is performed or claimed. Existing signed script tests and candidate helpers retain their separate scope.

Capture into the coordinator immutable build:

- `engines/build.gradle.kts`, `engines/src/main/AndroidManifest.xml`, `engines/src/openflux/AndroidManifest.xml`.
- Exact stage three `ABI/libopenflux.so` files and `script-updater-capability.json`; preserve the directory structure and pass the FROZEN absolute stage path.
- Existing module license assets, especially `OpenFlux-LICENSE`; this document and handoff/source hashes for review.
- Do not run the non-StageOnly transport builder to accomplish this delivery.

Required build/package gate: configure enabled promotion branch; build separate signed OpenFlux APKs for three ABIs/universal; verify same actual host signer, exact payload hashes/allowlist, source metadata/versionCode, no fixtures or base embedding. Record resulting APK bytes/hashes separately (not fabricated from native sizes).

Required coordinator phone gate: safe stopped-worker update of the dedicated module, exact installed APK receipt, then exactly approved unchanged stored classic Mail.ru profile outbound HTTPS with existing deadlines, cleanup and protected-user-data guard. No claim of success follows from host tests or document-ready alone. New Mail.ru authChangesAck/write readiness and OFX1 chat-channel source differ from v0.4.2; this supports testing the fresh mobile core but does not reveal the approved exit's version or certify older cursor-only peer compatibility.