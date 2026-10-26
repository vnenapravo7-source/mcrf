// SPDX-License-Identifier: GPL-3.0-or-later
// UNWIRED fresh mailru-package tests. No WebSocket/network/provider/account.
package mailru

import (
    "encoding/json"
    "errors"
    "strings"
    "testing"
    "github.com/p1neappleXpress/OpenFlux/mcrfdiag"
)

func freshMailruFlags(t *testing.T, owner *mcrfdiag.Run) map[string]bool {
    t.Helper();var result map[string]bool;line:=owner.Finish()
    if !strings.HasPrefix(line,mcrfdiag.PrefixV3)||json.Unmarshal([]byte(strings.TrimPrefix(line,mcrfdiag.PrefixV3)),&result)!=nil||len(result)!=37 {t.Fatal("fixed record")}
    return result
}
func freshMailruSession() *DocSession { return &DocSession{ready:make(chan struct{}),mcrfDiagnostic:mcrfdiag.NewV3()} }
const freshAuthChanges=`42["message",{"type":"authChanges"}]`

func TestMcrfFreshDeepMailruTypedAuthChangesNotSubstring(t *testing.T) {
    for _,frame:=range []string{`42["message",{"type":"message","message":"authChanges"}]`,
        `42["other",{"type":"authChanges"}]`,`42["message",{"type":1}]`,`42["message",null]`,
        `42["message",{"type":"authChanges"},true]`,`42["message",{"type":"authChanges"}]x`,
        `authChanges`,strings.Repeat("x",(1<<20)+1)} {
        session:=freshMailruSession();writes:=0
        if mcrfFreshMailruAuthChangesWith(session,[]byte(frame),func([]byte)error{writes++;return nil})||writes!=0||mcrfFreshMailruWriteReady(session) {t.Fatal("non-auth frame acknowledged")}
        for _,value:=range freshMailruFlags(t,session.mcrfDiagnostic) {if value {t.Fatal("non-auth frame diagnostic")}}
    }
}
func TestMcrfFreshDeepMailruAckSuccessSignalsAfterFixedWrite(t *testing.T) {
    session:=freshMailruSession();writes:=0
    if !mcrfFreshMailruAuthChangesWith(session,[]byte(freshAuthChanges),func(data []byte)error {
        if mcrfFreshMailruWriteReady(session)||string(data)!=authChangesAckMsg {t.Fatal("ready before ACK or wrong ACK")};writes++;return nil
    })||writes!=1||!mcrfFreshMailruWriteReady(session) {t.Fatal("ACK not ready")}
    flags:=freshMailruFlags(t,session.mcrfDiagnostic)
    if !flags["mailru_auth_changes_seen"]||!flags["mailru_ack_write_ok"]||flags["mailru_ack_write_failed"]||flags["mailru_fallback_ready"] {t.Fatal("ACK event attribution")}
}
func TestMcrfFreshDeepMailruAckFailureDoesNotSignalReady(t *testing.T) {
    session:=freshMailruSession()
    if !mcrfFreshMailruAuthChangesWith(session,[]byte(freshAuthChanges),func([]byte)error{return errors.New("synthetic refusal")})||mcrfFreshMailruWriteReady(session) {t.Fatal("failed ACK became ready")}
    flags:=freshMailruFlags(t,session.mcrfDiagnostic)
    if !flags["mailru_auth_changes_seen"]||!flags["mailru_ack_write_failed"]||flags["mailru_ack_write_ok"] {t.Fatal("failed ACK event")}
}
func TestMcrfFreshDeepMailruFallbackIsIdempotentNotAnAck(t *testing.T) {
    session:=freshMailruSession();mcrfFreshMailruFallback(session);mcrfFreshMailruFallback(session)
    if !mcrfFreshMailruWriteReady(session) {t.Fatal("fallback did not release ready")}
    flags:=freshMailruFlags(t,session.mcrfDiagnostic)
    if !flags["mailru_fallback_ready"]||flags["mailru_ack_write_ok"]||flags["mailru_auth_changes_seen"] {t.Fatal("fallback invented ACK")}
}
func TestMcrfFreshDeepMailruMissingWriterOrSessionCannotInventReady(t *testing.T) {
    if !mcrfFreshMailruAuthChanges(nil,[]byte(freshAuthChanges)) {t.Fatal("typed message unrecognized")}
    session:=freshMailruSession()
    if !mcrfFreshMailruAuthChanges(session,[]byte(freshAuthChanges))||mcrfFreshMailruWriteReady(session) {t.Fatal("missing socket became ready")}
    if !freshMailruFlags(t,session.mcrfDiagnostic)["mailru_ack_write_failed"] {t.Fatal("missing writer failure not observed")}
}
func TestMcrfFreshDeepMailruLateOldSessionDoesNotReportIntoNewOwner(t *testing.T) {
    old,next:=freshMailruSession(),freshMailruSession();old.mcrfDiagnostic.Finish()
    mcrfFreshMailruAuthChangesWith(old,[]byte(freshAuthChanges),func([]byte)error{return nil});mcrfFreshMailruFallback(old)
    if old.mcrfDiagnostic.Finish()!="" {t.Fatal("late owner reopened record")}
    for _,value:=range freshMailruFlags(t,next.mcrfDiagnostic) {if value {t.Fatal("late old owner changed new record")}}
}
func TestMcrfFreshDeepMailruWriteReadyRequiresExactChannel(t *testing.T) {
    if mcrfFreshMailruWriteReady(nil)||mcrfFreshMailruWriteReady(&DocSession{}) {t.Fatal("missing ready channel")}
    session:=freshMailruSession();if mcrfFreshMailruWriteReady(session) {t.Fatal("initially ready")}
    session.markReady();if !mcrfFreshMailruWriteReady(session) {t.Fatal("ready channel ignored")}
    freshMailruFlags(t,session.mcrfDiagnostic)
}
