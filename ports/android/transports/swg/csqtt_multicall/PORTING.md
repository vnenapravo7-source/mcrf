# BS layout — 2026-11-01

Implemented candidate layout: `rust-multicall-v1`, source-bound gate schema 3,
APK catalog schema 4. QWDTT and the CSQTT Go host share `libbs.so`; five Rust
generations share the single executable `libcsqtt_transport.so`. These are the
only two native files per ABI. WDTT belongs only to `org.mcrf.engines.wdtt` and
is never a BS migration deletion candidate. Installed schema 1/2/3 BS packages
remain readable for CSQTT/QWDTT; their WDTT payload cannot satisfy WDTT requirements.
Profiles, generation IDs and auto-detection order are unchanged. No profile store
is rewritten. A missing standalone WDTT must go through the normal module install.

## Real multi-call native proof (2026-11-01)

`lib.rs` defines the library/host boundary and validates mandatory per-process
`MCRF_BS_RUST_MODE` with optional agreeing `--mcrf-generation=...`. It never reads
JSON to select a generation. `csqtt_multicall_build.py` now produces five real
library adapters and one executable in a fresh external stage. Each adapter
keeps `main.rs` as its library crate root and appends `pub fn mcrf_run() { main(); }`.
This preserves inner attributes, module-relative paths, crate-local macros and
the original main's runtime, signals, IPC and shutdown. The host validates the
selector before dispatch; the adapter filters only the already-validated control
argument from its original CLI iterator. Without the generated `ported` feature,
the standalone boundary still rejects all valid launches with exit 78.

Receipt/revision checked inputs (frozen trees are never edited):

| Adapter | Pinned implementation | Source binding |
|---|---|---|
| 2.0 | `2ff8a1a4fbe031fb76b799e172dce25cfe1ca942` | `bs-retained-source-recovery-20261101-03h/native-stage-csqtt20/source`; full manifest and receipt hashes. Existing historical ingress/CAPTCHA/streams overlays retained. |
| 2.1.5 | `de7afc23cec7481f98845bf24fc8943af19b4cf8` | Same retained family, `native-stage-csqtt215/source`. The suggested `csqtt-historical-57bf…` is also 2.1.5, **not 2.1.9**. |
| 2.1.9 | `71712b07dbabe16fa43f5a248dc69e1ab9f3a270` | Clean `work/android-upstream/csqtt-legacy71712b`; required Git blob identities checked. Existing SHA-pinned private ingress, CAPTCHA waiters and stream-owner overlays reproduced in the new stage only. |
| 2.5.1 | `d02da9bd9d8926969b766e197c434d830a83dbd8` | `csqtt25-negotiated-478136905c174d7ebf61856bcd8ecd9b/source`; negotiated-worker source manifest/receipt, original embedded domains. |
| 2.5.2/2.5.3 | `1f51f962…` / `0188606a243c92a54440be09dc59a93848c463aa` | Versioned receipts prove equal client/shared Git trees and native hashes. Canonical `domains.txt` recovered by `csqtt252-aligned-armv7-20261101-02` is checked against its Git blob and SHA-256. |

Mechanical compilation blockers and resolutions:

- Cargo automatically included nested vendor crates and their benchmark deps:
  explicit workspace exclusions keep them dependency-only.
- Exact AWS-LC 1.17.3 and 1.18.0 could not resolve together: the old, checksum-
  verified registry archive is copied and its package name alone is namespaced.
  Its native symbol prefix and source implementation remain unchanged.
- Vendor primp differs only by line endings: normalized equality is checked
  before sharing one path. The two netstack revisions have real code differences
  and retain separate crates with unique package names.
- The versioned source archive omitted `domains.txt`: use the existing canonical
  source recovery, never an invented list.
- Host C compilation required LLVM-MinGW and NASM. Rust keeps its bundled GNU
  linker; putting clang's GCC alias first broke the libgcc link. No Rust protocol
  source or frozen input was changed to solve a toolchain failure.

The new workspace lock is seeded from all five original locks; every resolved
registry version/checksum must belong to that union. The resolved lock and all
toolchain/generated/native hashes are retained in the build receipt. Dependency
feature unification and the new lock still require wire/performance regression.
No old executables are embedded or spawned by the multi-call host.

Proof: `D:/MCRF/Builds/Android/bs-rust-multicall-integration-20261101-01/receipt.json`.
State `COMPILED_MULTICALL_PROOF_NOT_PACKAGED`, frozen/generated sources stable.
One arm64 PIE/16KiB ELF: **8,098,568 bytes raw / 3,897,622 bytes zlib-9**.
Windows x86_64: **9,171,456 bytes raw / 4,316,896 bytes zlib-9**.
Android ARMv7: **5,980,612 bytes raw / 3,431,241 bytes zlib-9**.
Android x86_64: **9,662,136 bytes raw / 4,283,224 bytes zlib-9**.
These are transport sizes, not APK or the whole BS engine sizes. The candidate
uses fat LTO, opt-level=z, panic=abort, codegen-units=1; savings combine merging
and optimization and do not establish throughput or panic-behavior acceptance.

27 host process cases passed: missing/unknown/conflicting selector rejection,
six parallel real CLI parsers, each generation's runtime/config error, agreeing
control CLI filtering, and the historical TURN transport CLI distinction. No
provider traffic, Android execution or live parallel route test was performed.

Reproduce (Python 3.11+): `py -3.14 transports/swg/csqtt_multicall_build.py
--output D:/MCRF/Builds/Android/<fresh-stage> --execute --targets host arm64`.
`contract.json` pins the actual compiled receipt. The source-bound candidate
gate permits verified staging. `require_ported()` still rejects release promotion.

Android/Go integration is implemented. Schema 4 makes Android select the explicit
logical generation (and validated modern implementation) per ProcessBuilder.
The Go wrapper checks mode/generation agreement, removes inherited selectors and
sets one trusted `MCRF_BS_RUST_MODE` per Rust child. The modern loader verifies
ABI/size/SHA-256 pins generated from this Rust receipt; standalone SWG release
loaders remain unchanged. No process-global selector is changed. Logical IDs,
autodetection order and independent route-owned processes remain unchanged.
The old split-only 2.5.2/3 presence check is skipped for verified schema 4.

Final common Go source/native: `bs-multicall-go-source-20261101-02` and
`bs-multicall-go-native-20261101-02`; 79 Go tests include seven simultaneously
living generation processes with private configuration/cache/selector ownership.
Changed Android consumers compile standalone; 10 Kotlin tests cover schemas
1–4, trusted env, logical generations, migration and unchanged autodetection.
No Gradle/APK was run. Installed phone packages remain unchanged.

Required acceptance: each generation CLI/config, wire/auth, CAPTCHA, timeout,
EOF/STOP/drain, panic behavior; parallel processes of differing generations;
three Android ABI execution (all three have compiled/16KiB ELF proof); real
traffic with per-route ownership. A host
selector test is not protocol or route acceptance.

## Size experiment

`maintenance/bs_rust_size_experiment.py` verifies an explicit source manifest,
copies it twice to a fresh external stage and uses the same Cargo.lock for both.
It keeps the pinned release baseline (3/fat/1/unwind) and sets only the candidate
profile to fat/z/1/abort. `--execute` uses `cargo ndk ... --frozen`, isolated target
directories and 16KiB alignment. Source/lock, producer/toolchain, log and binary
hashes, raw and compressed native size are recorded; frozen sources are rechecked.
No Gradle/APK is invoked. Without `--execute` the result is preparation only.

`evaluate_bs_rust_experiment.py` requires SHA-pinned compilation and measurement
receipts and rehashes both binaries. Measurements identify ABI, generation,
source manifest and each artifact hash, >=3 throughput samples, >=20 latency
and cold-start samples, zero panics and passing wire/captcha/timeout/shutdown/
parallel-generations cases. Thresholds: compressed payload size <=90%, mean
throughput >=97%, latency p95 <=105%, cold-start p95 <=110% of baseline. Any
protocol/panic failure rejects. Missing measurements never count as accepted.
An accepted single ABI/generation experiment is not full multi-call acceptance.
The earlier 2–6 MB / 5–10 MB savings estimates are unmeasured hypotheses.
