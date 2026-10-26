// SPDX-License-Identifier: GPL-3.0-or-later
// Fresh0.13 source overlay, UNWIRED/NOTRUN. Requires exact Mailru hook recipe.
// No provider endpoints, account creation, retries or anti-abuse changes.
package mailru

import (
    "bytes"
    "encoding/json"
    "github.com/p1neappleXpress/OpenFlux/mcrfdiag"
)

// Only an actual Socket.IO message event with typed authChanges receives the
// fixed ACK. A substring in chat/document data must not signal authentication.
func mcrfFreshMailruAuthChanges(session *DocSession, frame []byte) bool {
    var write func([]byte) error
    if session != nil && session.Conn != nil { write = func(data []byte) error { return session.safeWrite(1, data) } }
    return mcrfFreshMailruAuthChangesWith(session, frame, write)
}

// Injection is for isolated host tests only. Production supplies safeWrite.
// Parser is typed/bounded, not advertised as duplicate-key strict JSON grammar.
func mcrfFreshMailruAuthChangesWith(session *DocSession, frame []byte, write func([]byte) error) bool {
    if len(frame)>1<<20 || !bytes.HasPrefix(frame, []byte("42[")) { return false }
    var envelope []json.RawMessage
    if json.Unmarshal(frame[2:], &envelope)!=nil || len(envelope)!=2 { return false }
    var event string; var message struct { Type string `json:"type"` }
    if json.Unmarshal(envelope[0], &event)!=nil || event!="message" ||
        json.Unmarshal(envelope[1], &message)!=nil || message.Type!="authChanges" { return false }
    if session==nil { return true }
    session.mcrfDiagnostic.Mark(mcrfdiag.AuthChanges)
    if write==nil || session.ready==nil { session.mcrfDiagnostic.Mark(mcrfdiag.AckFailed); return true }
    if write([]byte(authChangesAckMsg))!=nil { session.mcrfDiagnostic.Mark(mcrfdiag.AckFailed); return true }
    session.mcrfDiagnostic.Mark(mcrfdiag.AckOK); session.markReady()
    return true
}

func mcrfFreshMailruFallback(session *DocSession) {
    if session==nil || session.ready==nil { return }
    session.mcrfDiagnostic.Mark(mcrfdiag.Fallback); session.markReady()
}
func mcrfFreshMailruWriteReady(session *DocSession) bool {
    if session==nil || session.ready==nil { return false }
    select { case <-session.ready: return true; default: return false }
}
