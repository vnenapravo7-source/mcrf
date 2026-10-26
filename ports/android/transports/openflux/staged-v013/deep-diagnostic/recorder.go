// SPDX-License-Identifier: GPL-3.0-or-later
// Source-prepared fresh-core V3 foundation; NOT wired into the producer/hooks.
// No dependency on historical v0.7.3 patches, process-global Current pointer,
// provider payloads, identifiers, URLs, cookies, native errors or log strings.
package mcrfdiag

import (
    "encoding/json"
    "sync/atomic"
)

type Event uint
const (
    SocketReady Event = iota
    AuthWriteOK
    AuthWriteFailed
    AuthChanges
    AckOK
    AckFailed
    Fallback
    ChatWriteOK
    ChatWriteFailed
    PeerChat
    ReadClosed
    ClassicDecryptOK
    ClassicDecryptFailed
    ClassicAccepted
    ClassicRejected
    RawCallbackMissing
    DemuxEmpty
    DemuxSession
    DemuxClassic
    LayerCallbackMissing
    CodecUnknown
    CodecDecodeFailed
    CodecNoPayload
    ClassicHeaderInvalid
    ClassicDirectionInvalid
    ClassicReplayRejected
    SessHeaderInvalid
    SessDirectionInvalid
    SessDecryptFailed
    SessDecryptOK
    SessBatchInvalid
    SessEnvelopeInvalid
    SessRoleInvalid
    SessHelloAccepted
    SessHelloRejected
    SessCallbackMissing
    SessReplayRejected
    eventCount
)
const PrefixV3 = "MCRF_PROVIDER_STAGE_V3 "
const frozen uint64 = 1 << 63
var names = [...]string{
    "mailru_socketio_ready", "mailru_auth_write_ok", "mailru_auth_write_failed", "mailru_auth_changes_seen",
    "mailru_ack_write_ok", "mailru_ack_write_failed", "mailru_fallback_ready", "mailru_chat_write_ok",
    "mailru_chat_write_failed", "mailru_peer_chat_decoded", "mailru_read_closed", "classic_decrypt_ok",
    "classic_decrypt_failed", "classic_frame_accepted", "classic_frame_rejected", "raw_callback_missing",
    "demux_empty", "demux_session", "demux_classic", "layer_callback_missing", "codec_unknown",
    "codec_decode_failed", "codec_no_payload", "classic_header_invalid", "classic_direction_invalid",
    "classic_replay_rejected", "sess_hdr_bad", "sess_dir_bad", "sess_dec_fail", "sess_dec_ok",
    "sess_batch_bad", "sess_env_bad", "sess_role_bad", "sess_hello_ok", "sess_hello_bad", "sess_cb_missing", "sess_replay",
}

type Run struct { bits atomic.Uint64 }
func NewV3() *Run { return new(Run) }
func (*Run) String() string { return "OpenFluxDiagnosticRun(redacted)" }

// Future hooks must retain the exact run pointer captured with their owner.
// There is intentionally no global lookup for a late callback to contaminate.
func (run *Run) Mark(event Event) {
    if run == nil || event >= eventCount { return }
    for {
        before := run.bits.Load()
        if before & frozen != 0 { return }
        if run.bits.CompareAndSwap(before, before | (uint64(1) << event)) { return }
    }
}

// Exactly one caller obtains a fixed Boolean-only record. False means not
// observed before freeze, not healthy/never happened/worker or handler joined.
// This does NOT emit anything or prove StopProxy/child death/reader EOF ordering.
func (run *Run) Finish() string {
    if run == nil { return "" }
    var snapshot uint64
    for {
        snapshot = run.bits.Load()
        if snapshot & frozen != 0 { return "" }
        if run.bits.CompareAndSwap(snapshot, snapshot | frozen) { break }
    }
    flags := make(map[string]bool, len(names))
    for index, name := range names { flags[name] = snapshot & (uint64(1) << uint(index)) != 0 }
    data, _ := json.Marshal(flags) // Closed fixed-key Boolean schema cannot fail.
    return PrefixV3 + string(data)
}
