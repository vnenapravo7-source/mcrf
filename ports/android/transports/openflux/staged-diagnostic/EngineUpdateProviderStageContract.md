# Source-only diagnostic overlay contract

Review target: upstream v0.7.3 f12e981629a18c8c01007d59ac002254c02972bb
plus the previously reviewed ACK overlay. `stage.ps1` creates a unique D: source
copy, applies the exact pinned ACK files, installs diagnostic hooks, and records
the complete source patch. It performs no native build or provider request.

Private staged-only CLI: `--mcrf-diagnostic-config-v1 PRIVATE_CONFIG_PATH`.
The original `--mcrf-config` path does not enable recording or emit a diagnostic
snapshot. Debug level stays zero. The private config retains its original profile
fields; no secret/context/codec/channel/timeout changes.

Recorder export: package `mcrfdiag` Begin/Current/Run.Mark/Run.Finish. Only a fixed
Event enum can be recorded. Each Mailru DocSession and each classicEnc captures
its run pointer at construction. Other encryption instances have a nil recorder.
Finish freezes the atomic bitset and detaches this exact run; repeated Finish
returns empty. Callbacks holding that run pointer cannot alter its frozen flags.
Isolation relies on the current one-diagnostic-run-per-process CLI: a new CLI
invocation has a separate address space. This export is not safe isolation for
reused/overlapping operations in one process: an old in-flight connection could
construct a DocSession later and capture Current(new). StopProxy does not join
that construction. The diagnostic CLI must retain its one-run process lifetime.

Stdout has exactly one additional final line, after `run` returns and its deferred
StopProxy has returned:

`MCRF_PROVIDER_STAGE_V1 {"classic_decrypt_failed":false,...}`

The JSON is one object with exactly 15 Boolean fields, matching `names` in
diagnostic.go. Every unseen flag is false. Version is the exact prefix. A crash,
killed process, missing line, empty line, invalid record or record before cleanup
must be `not_reported`, never synthesized all-false. StopProxy closes resources
but does not prove every asynchronous callback has joined: false means not
observed before freeze. Flags aggregate reconnects, not one connection.
`mailru_fallback_ready` means the existing timer callback fired; it can fire
after a successful ACK and does not prove fallback caused readiness.

Proposed coordinator NativeCarrier gate for a future reviewed diagnostic module:
CURRENT signer + exact APK/native/source/overlay receipt + metadata
`org.mcrf.engines.provider-diagnostic=1` and
`org.mcrf.engines.provider-diagnostic-cli=diagnostic-config-v1`.
These metadata names are a proposed separate capability; current production/v7
does not advertise them. Gate the private flag on main's DEBUG exact-original
approved-profile path only. Missing metadata keeps ordinary CLI behavior.

Reader: accept only the exact prefix; maximum 1024 UTF-8 bytes, exactly the 15
known keys, native Boolean values, no extra keys, duplicate keys or duplicate
records. Accept the final record only with matching process lifetime, normal
completion/cleanup evidence, and the existing STOP ACK. Do not forward other
native/provider text or replace the STOP/lease/data guards. Main owns this reader
and DEBUG propagation; no MainActivity/NativeCarrier/Probe files were edited here.

Synthetic host checks cover atomic concurrency, all-false keys, disabled recorder,
per-run reset/stale callbacks, once-only freeze, real localhost ACK success and
closed-socket failure, the exact fallback hook, own chat echo versus decoded peer
chat, real correct/wrong-key classic encryption, nonclassic isolation and classic
frame accept/reject hooks. Wrapper checks model STOP/EOF/error cleanup ordering
and a panic with no report. They do not claim a real provider or Android STOP gate.

No native/ABI hashes or APK version are assigned to this source-only overlay.
Coordinator must review the complete patch and test receipts before authorizing
any native compile or packaging. HTTPS remains failed, not accepted.
