# Primary core stage contract (change 18)

The primary flavors `singbox` and `steer` are disabled unless the coordinator passes `-PprimaryCoreStage=ABS_STAGE -PprimaryCoreNativeGate=true`. A stage can contain either or both modules; missing modules stay disabled. No runtime is copied into the base APK by this project. Main must remove the bundled runtime/wrappers and migrate its typed libbox calls through the host SPI.

`receipt.json` is a local coordinator-gated receipt, not a public release manifest. Schema:

- `version`: 1; `modules`: object keyed by `sing-box` and/or `steer`.
- Each module: `spi`: 1, `api`: 1 for sing-box (adapter API proposal) / 2 for Steer, `entry`: `org.mcrf.engines.singbox.SingBoxEngine` / `org.mcrf.engines.steer.SteerEngine`.
- `versionCode`: positive monotonically increasing Android module version; `moduleVersion`: actual module version, never inferred from a remote tag.
- `sourceRepository`, `sourceVersion`, `sourceSha256`: immutable matching source/patch receipt. The coordinator must independently verify provenance and runtime/adapter compatibility.
- `abis`: exact ABI names. sing-box permits arm64-v8a/armeabi-v7a/x86_64; Steer permits arm64-v8a/x86_64 only. Per-ABI outputs not present in the receipt are disabled; a universal APK contains only declared ABIs. This is a delivery bound, not evidence of all ABI runtime acceptance.
- `artifacts`: two entries `{file,bytes,sha256}` for `runtime.aar` and `adapter.aar`, beneath `STAGE/CORE/`. No adapter artifact exists merely because its receipt is described here.
- `licenses`: nonempty array `{file,bytes,sha256}` beneath `STAGE/CORE/licenses/`; included as module assets. Include actual license/source/provenance notices.

Runtime AAR must contain exactly `jni/ABI/libbox.so` for sing-box or `jni/ABI/libsteer_vpn.so` for Steer across declared ABIs. Adapter AAR must contain no native libraries, must include its entry class in classes.jar, and must not define the parent-owned `org.mcrf.engine.spi.v1` classes. Neither an AAR nor a prototype APK is a signed deliverable module.

Metadata preserves both the existing Steer SPI keys `org.mcrf.engine.spi.version`, `org.mcrf.engine.core.api`, `org.mcrf.engine.entry` and TUN host loader aliases `org.mcrf.engines.core-api`, `org.mcrf.engines.entry`; alias values must agree. `org.mcrf.engines.primary-core=1`, `kind`, source and ABI metadata identify this as a primary core, never a SOCKS transport worker.

Module package names: `org.mcrf.engines.singbox`, `org.mcrf.engines.steer`. Public APK asset names use `mcrf-engine-sing-box-ABI-release.apk` / `mcrf-engine-steer-ABI-release.apk`. These are naming contracts; discovery fails honestly if no real uploaded stable asset exists. A coordinator output alias is needed for the singbox Gradle flavor.

Current APK signer sets must exactly equal the actual host signer set. Installed APK bytes must match the trusted download receipt before acceptance. Install/remove/update use the durable EngineUpdateGate; loader must hold a shared usage lease from package inspection through worker cleanup. Primary identity validation never loads classes/native code. Main/TUN must pin exact installed APK bytes and verify SPI Class identity, entry, native search path, minSdk and full profile/settings capabilities before construction.

Main hooks:

- First launch: `EngineUpdatePrimaryUi.view(activity, host)`; do not assign a default when `host.selected()` is null.
- Engines: `ProtocolsSheet.show(activity, true, host)`; confirmed selection calls full snapshot validation then `selectValidated`. Host callbacks use choice IDs `singbox` / `steer`; package kinds are `sing-box` / `steer`, mapped explicitly by `EngineUpdatePrimary.choiceId` and `kindForChoice`. Main owns the actual store/loader/runtime. Missing host bridge disables switching; it never pretends success.
- Updates: `EngineUpdateUi.view(activity)`; one OpenFlux action follows current phase. Application update controls remain main-owned. Auto preference and source/pin/stage policy unchanged.
- Declare package visibility for both primary packages in the main manifest. Retain existing installer callbacks and provider. Startup/onResume recovery must remain.

Pending: actual lifecycle adapters, finalized sing-box API/Steer additive SPI compatibility, stage receipts, shared Gradle metadata/signature/ABI/R8 builds, installed loader/JNI identity, first-launch/switch/profile failure and actual traffic tests. No complete architecture or device acceptance is claimed by this stage contract.
Enabled primary flavors use flavor-specific `compileOnly(project(":primary-spi"))` for the canonical host SPI. It resolves D8 contract references and must not contribute SPI classes to the module APK; disabled carrier-only builds do not depend on it. The coordinator must capture the exact primary-spi contract-lock and canonical Java sources. Actual APK DEX inspection remains a packaging gate.