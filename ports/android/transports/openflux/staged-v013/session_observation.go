// SPDX-License-Identifier: GPL-3.0-or-later
package transport

// Finite counter observations, not raw logs or a claim that false means healthy.
// This uses existing atomics/Session lock only; no provider Stats/network call.
func McrfObservation(s *Session) map[string]bool {
    out := map[string]bool{}
    for _, name := range []string{
        "session_observed","session_started","session_ready","session_stopped_before_cleanup",
        "carrier_present","carrier_started","carrier_dead","hello_sent","hello_received","hello_accepted","hello_rejected",
        "data_sent","data_received","data_dropped","envelope_decode_failed","unknown_kind",
        "classic_sent","classic_received","classic_dropped","session_crypto_received","session_crypto_failed","session_crypto_replay","session_crypto_bad_header_or_length",
        "classic_crypto_received","classic_crypto_failed","classic_crypto_replay","classic_crypto_bad_header_or_length",
        "codec_unknown","codec_send_failed","batch_send_failed","batch_queue_waited","proxy_running_before_stop","proxy_ready_seen","stop_returned",
    } { out[name] = false }
    if s == nil { return out }
    out["session_observed"] = true
    out["hello_sent"], out["hello_received"] = s.cntHelloSent.Load()>0, s.cntHelloRecv.Load()>0
    out["hello_accepted"], out["hello_rejected"] = s.cntHelloAccept.Load()>0, s.cntHelloReject.Load()>0
    out["data_sent"], out["data_received"], out["data_dropped"] = s.cntDataSent.Load()>0, s.cntDataRecv.Load()>0, s.cntDataDrop.Load()>0
    out["envelope_decode_failed"], out["unknown_kind"] = s.cntDecodeErr.Load()>0, s.cntUnknownKind.Load()>0
    out["classic_sent"], out["classic_received"], out["classic_dropped"] = s.cntClassicSent.Load()>0, s.cntClassicRecv.Load()>0, s.cntClassicDrop.Load()>0
    s.mu.Lock()
    defer s.mu.Unlock()
    out["session_started"], out["session_ready"], out["session_stopped_before_cleanup"] = s.started, s.ready, s.stopped
    for _, link := range s.links {
        out["carrier_present"] = true
        out["carrier_started"] = out["carrier_started"] || link.started
        out["carrier_dead"] = out["carrier_dead"] || link.dead
        for prefix, crypto := range map[string]*EncryptedTransport{"session":link.encrypted,"classic":link.classicEnc} {
            if crypto == nil { continue }
            _,_,received,failed,replay,header,length := crypto.CryptoStats()
            out[prefix+"_crypto_received"] = out[prefix+"_crypto_received"] || received>0
            out[prefix+"_crypto_failed"] = out[prefix+"_crypto_failed"] || failed>0
            out[prefix+"_crypto_replay"] = out[prefix+"_crypto_replay"] || replay>0
            out[prefix+"_crypto_bad_header_or_length"] = out[prefix+"_crypto_bad_header_or_length"] || header>0 || length>0
        }
        if link.classicCodec != nil {
            out["codec_unknown"] = out["codec_unknown"] || link.classicCodec.unknown.Load()>0
            out["codec_send_failed"] = out["codec_send_failed"] || link.classicCodec.sendErrors.Load()>0
        }
        if link.batched != nil {
            out["batch_send_failed"] = out["batch_send_failed"] || link.batched.sendErrors.Load()>0
            out["batch_queue_waited"] = out["batch_queue_waited"] || link.batched.queueWaits.Load()>0
        }
    }
    return out
}
