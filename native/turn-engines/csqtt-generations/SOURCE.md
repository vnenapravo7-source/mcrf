# MCRF Windows CSQTT modules — 2026-10-22

Separate, on-demand modules. No native CSQTT binary is embedded in mcrf.exe.

| Module | Original source revision | Native contract |
| --- | --- | --- |
| CSQTT 2.0 | amurcanov/csqtt 2ff8a1a4fbe031fb76b799e172dce25cfe1ca942 | legacy device-bound, six GETCONF fields |
| CSQTT 2.1.5 | amurcanov/csqtt de7afc23cec7481f98845bf24fc8943af19b4cf8 | legacy device-bound, worker-count field |
| CSQTT 2.1.9 | amurcanov/csqtt 446293aa2e873ac5323ef6fd2316d9b81d966c11, Windows portability luminescq/focsq bfa176f | CSQTT-WIRE-3 |
| CSQTT 2.5 / Rebirth | amurcanov/csqtt d02da9bd9d8926969b766e197c434d830a83dbd8 | no legacy device field, 2.5 protocol |

Rebirth v2.5.0, v2.5.0-legacy and current native source were compared with the
original 2.5 line. They share the compatible native implementation; Android-only
UI/routing changes do not require another Windows engine.

Source directories contain the actual patched Rust inputs and Cargo.lock files.
Legacy protocol/dispatcher/packet implementations are kept from their own tags,
not emulated by changing a version label on the latest client. MCRF changes only
the private stdin host boundary and platform integration. Credentials are not
passed as operating-system process arguments. The local UDP/SOCKS bridge is the
separately distributed csqtt-netstack component; its source/license remains separate.

Maintainer build scripts are included under native/turn-engines. Their default
paths describe the maintainer's Windows toolchain layout and may be adjusted.
Use Rust 1.99.0, x86_64-pc-windows-gnullvm, LLVM-MinGW and locked dependencies.
For legacy snapshots build rust-client with cargo build --locked --release;
the 2.1.9 bridge references the included portable backend. The generated lock
files for old tags are included because those tags did not ship Cargo.lock.

Original licenses and attribution are retained. Do not infer a license change
from Cargo metadata: see root LICENSE.csqtt and NOTICES.txt. No private MCRF
profile, credentials, provider sessions or generated native configuration is
included in this archive.
