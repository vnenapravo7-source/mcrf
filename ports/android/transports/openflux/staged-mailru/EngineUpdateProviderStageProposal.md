# Minimal provider-stage diagnostic proposal

Status: proposal for coordinator review only. No source overlay applied, native
build, phone operation or provider request authorized by this document.

The reviewed ACK v7 failed the one approved stored-profile HTTPS check after
27 seconds. Coordinator verified APK/native/source pins, cleanup ACK and unchanged
user data. This does not establish whether Mailru rejected control, whether the
exit peer sent data, or whether encryption/layering rejected received data.

Keep debug level zero. Existing verbose logs include user IDs, document details,
nonces, frame dumps and cryptographic context; do not enable or parse them.

Proposed opt-in diagnostic overlay: a run-scoped atomic bitset, fixed events only,
enabled by a private wrapper diagnostic flag supplied only by the coordinator's
existing exact-approved-profile helper. No URLs, IDs, keys, context, frames,
packet sizes, error strings or exception text enter the event recorder.

| Fixed event | Exact source hook |
| --- | --- |
| mailru_socketio_ready | mailru.go after successful waitMailruSocketIO, before SetConnected(true) |
| mailru_auth_write_ok / mailru_auth_write_failed | Result of the existing auth safeWrite; observe result without changing control flow |
| mailru_auth_changes_seen | ACK overlay helper after exact typed authChanges validation |
| mailru_ack_write_ok / mailru_ack_write_failed | Existing helper safeWrite result, before markReady |
| mailru_fallback_ready | Existing three-second callback, distinct from ACK success |
| mailru_chat_write_ok / mailru_chat_write_failed | writerLoop existing OFX1 data safeWrite result |
| mailru_peer_chat_decoded | handleMessage only after decodeChatData excludes own sender and returns a packet |
| mailru_read_closed | Existing ReadMessage failure path; omit underlying error |
| classic_decrypt_ok / classic_decrypt_failed | encrypted.go Receive r.open result, only for link.classicEnc instance |
| classic_frame_accepted / classic_frame_rejected | session.go receiveClassic after acceptance, or existing rejection branches |

Identify the classic encryption instance at its construction/registration; do not
count Session handshake decryption as classic data. Record only presence, never
payload or header content. Multiple reconnects may set different flags during
one run: the aggregate is not proof that all events occurred on one connection.

Emit at most one final fixed-schema snapshot from the wrapper after StopProxy
has completed, including all flags explicitly false when unseen. Main's native
reader must accept only the exact prefix/schema/version and Boolean allowlist;
reject extra keys/types/oversized records and preserve the existing STOP/cleanup
ACK sequence. Crash/missing snapshot is `not_reported`, never all-false evidence.
No provider debug text may be forwarded. This requires a separately reviewed
wrapper/mobile diagnostic export and main reader hook; current v7 has neither.

Interpretation: Socket.IO ready alone is not editor authorization. Fallback ready
is not control ACK. Successful local WebSocket write is not remote receipt.
Peer chat without classic decrypt success narrows the fault to layering/crypto
but does not prove a wrong key. Classic accepted data still does not prove HTTPS.
No peer chat leaves peer absence, channel/version incompatibility and provider
relay failure unresolved. Keep existing context/key/codec/channel/timeouts.

Before any new native candidate: coordinator reviews exact hooks and export,
then isolated synthetic tests cover ACK success/failure/fallback, own-echo versus
peer chat, correct/wrong-key classic decrypt, fixed-schema privacy, reset and
STOP/EOF snapshot ordering. Only afterward may a new separately pinned candidate
be proposed. No automatic retry of the approved profile follows this proposal.
