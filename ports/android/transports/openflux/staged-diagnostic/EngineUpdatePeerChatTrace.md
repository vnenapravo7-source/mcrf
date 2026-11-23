# 4fb1 provider-stage source trace

Actual coordinator receipt: `work/ports-test-2026-10-14/integration-4fb1-device/openflux-provider-stage.log`.
One approved stored profile, HTTPS false after 15s; final Boolean snapshot reported
after normal child exit/EOF, cleanup ACK true, user data unchanged. Ordinary v7
remains unchanged. No new native build, provider retry or configuration edit.

Observed: Socket.IO and local auth write succeeded, fallback timer fired, local
chat write succeeded, at least one peer-tagged chat payload decoded. No
authChanges/ACK or classic decrypt/frame event was observed before freeze.
False flags are not proof those operations never happened. These aggregate
events may come from different reconnects and different received packets.

Exact receive path in pristine v0.7.3 (staged diagnostic hooks preserve it):

1. `transport/mailru/mailru.go:661` decodeChatData accepts message type `message`,
   excludes only matching own `useridoriginal`, requires OFX1 and valid Base64.
   It does not authenticate the exit participant, require a nonempty decoded
   packet, or validate tunnel framing. In staged mailru.go:651 the peer flag is
   marked before RecordReceive and CallReceive. Thus it proves decoding of
   tagged chat, not a valid exit response. Empty Base64, old chat history or a
   different participant can satisfy this check; none is established here.
2. `transport/transport.go:135` CallReceive invokes the registered callback only
   if nonnil. Manager receives from Session (`transport/manager/manager.go:271`);
   no manager raw Receive overwrite was found. Callback absence is an unmeasured
   possible gate, not a demonstrated registration fault.
3. `transport/framedemux.go:37-52` dispatch drops zero-length packets. A packet
   starting with encryptedMagic[0] (`O`) goes to Session, not classic. Every
   other nonempty packet goes to classicCb if registered. This classifier checks
   one byte, not the full OFX header. A legitimate Session handshake or malformed
   O-prefixed packet therefore leaves classic flags false.
4. Classic callback enters `transport/codec.go:167-229`. Batch marker 0x02 must
   pass decodeBatch (`framing.go:99`); legacy 0x00/0x1F must pass decompress
   (`compressor.go:64`). Lone legacy keepalive is discarded. Unknown marker or
   decoder error returns before crypto. A valid empty batch/probe or zero-length
   decoded records can produce no crypto callback. Non-nil user callback and a
   nonempty decoded record are required. Codec accepts both legacy and batched
   receive framing already; changing the profile codec is not justified.
5. `transport/encrypted.go:482-539` classic Enc.Receive checks minimum length,
   magic, version and expected receive direction before r.open. Rejection at
   any of these checks sets neither existing diagnostic decrypt flag. A same-role
   or echoed encrypted record can fail direction; this is not a wrong-key finding.
   Failure of r.open marks classic_decrypt_failed. Success must also pass replay
   detection before classic_decrypt_ok is marked. Replay rejection is unmeasured.
6. Only then does callback reach `transport/session.go:1205` receiveClassic;
   non-IPv4/short frames and an exit busy with Session are rejected, otherwise
   accepted. Both frame flags being unobserved adds no localization until earlier
   gates are measured. `mobile/session.go:119-123` explicitly sets Classic for
   this profile path; staged session.go:208 records only its classicEnc instance.

The strongest supported narrowing is the unmeasured path between decoded chat
and successful classic crypto acceptance. Session-side data remains another path
with no classic telemetry. Key mismatch would ordinarily be recorded only after
reaching r.open; the current result does not establish that prerequisite.
No provider/server/key/channel defect is proved, and ACK correction alone has
not produced HTTPS success.

Bounded next proposal, requiring main review before implementation: extend
diagnostic telemetry only, using a new exact schema/prefix version (do not silently
expand the accepted v1 15-key schema). Fixed Boolean events for callback missing,
empty demux packet, Session dispatch, classic dispatch, unknown codec marker,
codec decode failure, codec with no nonempty output, and classic header rejection
(length/magic/version/direction one aggregate), plus replay rejection. Keep payload,
header bytes, lengths, IDs and errors out of the recorder. Existing crypto flags
already cover r.open. No runtime/protocol fix or profile change is indicated yet.

Proposed offline tests: feed the real receive chain with empty OFX1 Base64,
same-sender echo/different-participant tagged chat, O-prefixed Session record,
unknown codec marker, malformed batch/legacy, valid empty batch, valid encoded
classic record, short/wrong-header/wrong-direction record, valid correct/wrong-key
record and replay. Assert each early return is distinguishable while callback
behavior and ciphertext stay unchanged. Retain privacy/reset/freeze/STOP tests;
test any new strict parser schema separately in main's owned reader. Do not build
or retry a provider until main reviews the source changes and offline evidence.
