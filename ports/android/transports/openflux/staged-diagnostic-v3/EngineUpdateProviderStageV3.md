# Source-only Session pipeline diagnostic V3

Prerequisites: upstream f12e/v0.7.3, exact reviewed V1 patch4764e6ad and V2
delta25ba2ab1. This delta is staged only; V2 native759245/package9, ordinary7,
production protocol/profile/transport lock and source hooks are unchanged.

Private CLI `--mcrf-diagnostic-config-v3 PRIVATE_CONFIG_PATH`.
Exact final prefix `MCRF_PROVIDER_STAGE_V3 `. JSON retains the V2 26 Boolean
fields and adds 11 fixed Boolean fields (37 total). All-false is the longest
encoding: 1016 ASCII bytes including prefix, excluding newline (1017 with LF).
All fields use native Booleans. No values other than fixed enum presence bits
are retained: no counts, IDs, role/header values, packets, errors, hosts or keys.

| Additional key | Actual source hook |
| --- | --- |
| sess_hdr_bad | Session encrypted minimum-length/magic/version rejection |
| sess_dir_bad | Session encrypted receive-direction rejection |
| sess_dec_fail | Session r.open failure |
| sess_dec_ok | Session successful r.open after replay check |
| sess_replay | Session replay rejection after successful r.open |
| sess_batch_bad | Session BatchedTransport receive decoder error |
| sess_cb_missing | Session BatchedTransport output callback absent |
| sess_env_bad | Session control.Decode error |
| sess_role_bad | Decoded envelope has this Session's own role |
| sess_hello_ok | Existing cntHelloAccept branch |
| sess_hello_bad | Existing cntHelloReject branches |

The Session recorder is captured only on newLink's Session encrypted/batched
instances. ClassicEnc remains separate. Hooks observe existing checks without
altering decoding, authentication, callback delivery, timing, role selection or
protocol bytes. V1 private flag still emits exactly15 fields/prefixV1; V2 still
exactly26/prefixV2 and ignores V3 events. Ordinary CLI activates no recorder.

Final snapshot is once-only after deferred StopProxy returns; panic emits none.
One diagnostic operation per process remains mandatory. Captured old pointers
freeze safely, but no reuse/overlap isolation claim is made. False is unobserved
before freeze, not absence. Aggregates span packets/reconnects; flags need not be
causally related. Fallback means timer fired, not cause of readiness.

sess_hello_ok does not mean Session established or HTTPS works: the initial
non-echo hello can be accepted before readiness. sess_hello_bad only covers the
existing normal hello reject counter branches; replacement/challenge handling,
stopped state, unknown envelope kinds and valid IPv4/control acceptance are not
traced. Those limits must not become synthesized failure diagnoses.

Proposed main gate, not assigned to any native/module yet: diagnostic metadata3,
provider-diagnostic-cli `diagnostic-config-v3`, source.overlay
`mcrf-provider-stage.3`, exact CURRENT signer/source/APK/native receipts plus
existing DEBUG approved-original profile guard. Reader requires exact prefix,
37 unique known Boolean keys, max1024 bytes, no duplicates/extras/multiple records,
matching child lifetime/normal exit/STOP cleanup ACK. Older readers reject V3;
missing/crash/invalid remains not_reported. Main owns reader propagation.

Host proof uses actual newLink/startLink/Session AES/BatchedTransport/control
decode/hello receive, not a modeled switch: short header, crypto direction,
synthetic wrong-key AEAD, malformed batch/envelope, same envelope role, valid
hello, rejected hello, absent callback and replay produce distinct flags. It
also reruns V1/V2 real Mailru/component-chain, schema, privacy/reset and synthetic
STOP/error/panic tests. These are source/unit results, not Android/provider gates.

Review complete delta, contracts and receipts before native authorization. No
native build, ABI/APK/Gradle/install/phone/provider request was performed for V3.
