# Source-only provider diagnostic V2

Review input: pristine v0.7.3 f12e981629a18c8c01007d59ac002254c02972bb,
then reviewed V1 patch 4764e6add52a853c0f5f9534eb2461d3bb8070b9a7a227f15848c5c840885fc6,
then this bounded V2 delta. `stage.ps1` produces only a unique source copy and
delta; no native or packaging command. Existing V1/ordinary production files,
transport lock, profile key/context/codec/channel/timing and retry policy stay intact.

Staged private CLI: `--mcrf-diagnostic-config-v2 PRIVATE_CONFIG_PATH`.
Final prefix: `MCRF_PROVIDER_STAGE_V2 `. JSON: exactly the original 15 V1 Boolean
keys plus these 11 fixed Boolean keys (26 total):

| Key | Observation, not a diagnosis |
| --- | --- |
| raw_callback_missing | BaseTransport.CallReceive had no registered callback |
| demux_empty | frameDemux discarded a zero-length decoded packet |
| demux_session | Nonempty packet classified by first byte O into Session |
| demux_classic | Nonempty packet classified into the classic branch |
| layer_callback_missing | Selected demux callback or codec output callback was nil |
| codec_unknown | Classic codec marker matched neither batch nor legacy |
| codec_decode_failed | Existing batch/legacy decoder returned an error |
| codec_no_payload | Empty input, single-byte keepalive or valid decoded frame without nonempty records |
| classic_header_invalid | Classic encrypted record failed minimum-length/magic/version guard |
| classic_direction_invalid | Classic record failed existing receive-direction guard |
| classic_replay_rejected | Existing nonce replay guard rejected a successfully opened record |

Hooks record only fixed enum bits at existing branches. No header, role value,
byte count, error, identifier, host or payload enters the recorder. Callback and
decoder behavior remain unchanged. Recorders are captured per BaseTransport,
demux, codec and classicEnc instance during the one diagnostic CLI operation.

V1 still accepts only its original private flag, emits the original prefix and
exactly 15 keys, and ignores V2 events. Ordinary --mcrf-config enables neither
recorder. Each diagnostic process performs one operation only. This is not an
isolation guarantee for overlapping/reused operations; preserve that invariant.
Finish remains once-only, freezes flags after run's deferred StopProxy returns,
and emits nothing on a panic. False means unobserved before freeze; aggregates
can span reconnects and packets. Fallback timer firing is not causation of ready.

Proposed main reader capability gate, not implemented or assigned to an APK:
`org.mcrf.engines.provider-diagnostic=2`,
`org.mcrf.engines.provider-diagnostic-cli=diagnostic-config-v2`, reviewed source
overlay `mcrf-provider-stage.2`, exact CURRENT signer/APK/native/source receipts
and existing DEBUG exact-approved-original profile gate. V1 reader/capability
continues to reject V2. New reader requires exact V2 prefix/26 keys/native Boolean
values, max1024 UTF-8 bytes, no duplicate/extra fields/records, matching child
lifetime/normal exit/STOP cleanup ACK. Missing/crash/invalid remains not_reported.
No module version, new native SHA or production activation is assigned here.

Host proof uses actual Mailru.handleMessage/decodeChatData/BaseTransport forwarding
into a real Session.Start/newLink/frameDemux/CodecTransport/EncryptedTransport/
receiveClassic chain with synthetic packets and a no-network raw transport.
That Session fixture is exit-role to avoid a provider handshake wait; separate
component-chain cases exercise client-role AES receive guards. Own echo, empty
peer, Session peer and valid classic peer are distinguished. No endpoint is an
actual exit provider and no Android/HTTPS acceptance is claimed.

Additional real-component cases cover callback absence, unknown/malformed legacy
and batch framing, empty batch/keepalive, short/magic/version/direction guards,
correct/wrong-key records and replay. Tests retain V1 privacy/reset/atomic/freeze
and synthetic STOP/EOF/error/panic coverage; V2 checks its exact schema and V1
isolation. Review complete delta and host receipts before any native build.
