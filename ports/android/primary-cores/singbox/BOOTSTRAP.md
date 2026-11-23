# Separate sing-box primary core bootstrap

Current adapter uses canonical SPI `Platform` via `Engine.Host.platform()`.
Interim ClientPrimaryCorePlatform is deleted; generic per-kind metadata is agreed:
choiceid singbox/kind sing-box/coreAPI1 and Steer steer/steer/coreAPI2. Proxy Request
uses validated fingerprint/mode with fd=-1; deferred native-options VPN ordering
still needs coordinator resolution. See PLATFORM-CONTRACT.md for current hooks.

Human change 18 supersedes bundled/default libbox. This is a source contract,
not an installed or accepted module. Both cores require an explicit selection.

The existing host-owned `org.mcrf.engine.spi.v1.Engine` remains the single SPI.
Do not fork it, pass libbox types through it, or assume Steer's core API 2 and
entry name are sing-box metadata. Engine owner must agree sing-box identity
`org.mcrf.engines.singbox`, entry `org.mcrf.engines.singbox.SingBoxEngine`, SPI 1.
Generic host identity validation must use per-kind expected entry/core API.

Package the full gomobile AAR (go.Seq/go.Universe, io.nekohasekai.libbox wrappers
and matching libbox.so) AND sing-box lifecycle/platform adapter in the module.
Base must contain neither native box nor these runtime wrappers. Existing
MainActivity/service/profile code references libbox directly: removing only
the native dependency or changing it to compileOnly is insufficient. Main must
move those execution paths behind SPI/config JSON and host platform DTOs.

Create a public PathClassLoader from the installed, same-current-signer APK
sourceDir plus verified splitSourceDirs, with librarySearchPath equal to the
installed extracted nativeLibraryDir, parent the host SPI loader. No hidden
APIs, DexPathList edits, createPackageContext IGNORE_SECURITY, absolute native
preload, shared UID or arbitrary downloaded DEX. Module APK must extract its
native libraries; missing process-compatible extracted libbox.so fails before
initialization. The engine owner packages/installs through the existing signed
package gate; install/update must be excluded while a worker lease exists.

Actual AAR bytecode audited locally: go.Seq static initializer calls
System.loadLibrary("box"), then init and go.Universe.touch. All go.* and libbox
wrappers must therefore be DEFINED by the same module classloader whose native
search path contains libbox.so. Loading libbox.so absolutely from the host does
not establish this association. Reject parent classpath contamination rather
than child-first shadowing: host SPI is parent-owned, runtime classes child-owned.
One fresh host-owned core worker process per run; no native unload or core switch
in a live worker. Preserve the UI/main process and encrypted user data.

Before loading code: verify package enabled/current signer, kind/SPI/core API,
expected entry, minSdk/ABI, immutable source/split/native paths and pinned artifact
identity. Recheck installation after class resolution before construction.
Host parent must supply the existing SPI once; entry must implement that exact
Class object. The loader helper resolves without initializing; constructor and
capabilities validation/run occur only after these gates in the isolated worker.
An update/removal during load fails; OS lease must cover inspection through exit.

Use the existing SPI Request settingsJson for an immutable complete config and
generation, worker-local borrowed TUN fd, and validated state directory. Numeric
fds never cross processes: main VpnService transfers PFD through Binder, worker
duplicates with explicit ownership, native uses its own duplicate. Socket callback
must synchronously protect AND bind before connect; false/timeout/stale generation
or dead host closes the original and fails. Libbox platform callbacks must be
adapted inside the module; callback DTOs are pure platform data, never gomobile
objects. SPI 1 currently lacks some libbox platform callbacks/admin commands;
coordinate additive shared SPI changes with Steer/main before implementing them.

Missing selection -> chooser; selected missing package -> explicit install action;
invalid/incompatible selection -> structured failure, never fallback. Changing
core requires confirmation, full saved profile/settings capability preflight and
completed stop/worker cleanup. Unsupported Steer settings must be rejected, not
silently normalized. Keep old settings/profiles/opaque backup sections intact.

Remaining gates: generic SPI agreement, module implementation/package, base runtime
removal, installed loader/JNI identity, callbacks/PFD ownership, supported profile
preflight, 16KiB/ABI/R8/device/real traffic. No bootstrap success claimed from this
document or JVM policy tests.

Public APIs used: [PathClassLoader](https://developer.android.com/reference/dalvik/system/PathClassLoader)
constructor accepts dex/native search paths and parent. [AOSP classloader source](https://android.googlesource.com/platform/libcore/+/refs/heads/master/dalvik/src/main/java/dalvik/system/DexPathList.java)
explains native lookup; no access to its private implementation is needed.
