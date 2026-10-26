// SPDX-License-Identifier: GPL-3.0-or-later
// Future transport-package overlay; UNWIRED/NOTRUN. Requires exact hook recipe,
// recorder package and existing fresh memory-only transport fixture. Not provider IO.
package transport

import (
    "encoding/json"
    "strings"
    "testing"
    "github.com/p1neappleXpress/OpenFlux/mcrfdiag"
    "github.com/p1neappleXpress/OpenFlux/transport/control"
)

func TestMcrfFreshDeepSessionActualPipeline(t *testing.T) {
    expected := map[string]string{
        "short_O":"sess_hdr_bad", "same_crypto_role":"sess_dir_bad", "wrong_crypto_key":"sess_dec_fail",
        "plaintext_not_batch":"sess_batch_bad", "invalid_envelope":"sess_env_bad", "same_envelope_role":"sess_role_bad",
        "valid_hello":"sess_hello_ok", "invalid_hello":"sess_hello_bad", "callback_missing":"sess_cb_missing", "replay":"sess_replay",
    }
    for _, kind := range []string{"short_O","same_crypto_role","wrong_crypto_key","plaintext_not_batch","invalid_envelope",
        "same_envelope_role","valid_hello","invalid_hello","callback_missing","replay"} { t.Run(kind,func(t *testing.T) {
        run := mcrfdiag.NewV3()
        parameters := PeerParameters{Capabilities:control.CapabilityIPv4|control.CapabilityTCP, MaxPacketSize:1500}
        session,err := McrfNewDiagnosticSession(parameters,false,run); if err != nil { t.Fatal(err) }
        session.SetClassic(CodecBatched); defer session.Stop()
        secret,context := "synthetic sufficiently long shared secret","synthetic-context"
        raw := &mcrfFreshMemoryTransport{}
        if err=session.AddTransport("synthetic",raw,secret,context,0);err!=nil {t.Fatal(err)}
        link:=session.links["synthetic"]
        if link.encrypted.mcrfSessionDiagnostic.Load()!=run || link.batched.mcrfSessionDiagnostic.Load()!=run {t.Fatal("owner propagation")}
        if err=session.startLink(link);err!=nil {t.Fatal(err)}
        var peer [32]byte;peer[0]=1
        envelope:=&control.Envelope{Kind:control.KindHello,Role:control.RoleExit,Local:peer,
            Hello:&control.HelloTail{Capabilities:control.CapabilityIPv4|control.CapabilityTCP,MaxPacketSize:1500}}
        if kind=="same_envelope_role" {envelope.Role=control.RoleClient}
        if kind=="invalid_hello" {envelope.Local=[32]byte{}}
        encoded,err:=envelope.Encode();if err!=nil {t.Fatal(err)}
        plaintext:=encodeBatch([][]byte{encoded})
        if kind=="plaintext_not_batch" {plaintext=make([]byte,20);plaintext[0]=0x45}
        if kind=="invalid_envelope" {plaintext=encodeBatch([][]byte{{1}})}
        wire:=&mcrfFreshMemoryTransport{};key:=secret
        if kind=="wrong_crypto_key" {key="other sufficiently long shared secret"}
        sender,err:=NewEncryptedTransport(wire,key,context,kind!="same_crypto_role");if err!=nil {t.Fatal(err)}
        if err=sender.Send(plaintext);err!=nil {t.Fatal(err)}
        packet:=wire.snapshot();if kind=="short_O" {packet=[]byte{'O'}}
        if kind=="callback_missing" {link.batched.Receive(nil)}
        raw.deliver(packet);if kind=="replay" {raw.deliver(packet)}
        // Event snapshot only; this does NOT assert child exit/StopProxy/reader drain.
        line:=run.Finish();var flags map[string]bool
        if !strings.HasPrefix(line,mcrfdiag.PrefixV3)||len(line)+1>1024||
            json.Unmarshal([]byte(strings.TrimPrefix(line,mcrfdiag.PrefixV3)),&flags)!=nil||len(flags)!=37||!flags[expected[kind]] {
            t.Fatal("actual deep pipeline event missing",kind)
        }
        if flags["classic_decrypt_ok"]||flags["classic_decrypt_failed"]||flags["classic_frame_accepted"] {t.Fatal("session/classic contamination")}
        if kind=="same_crypto_role"&&flags["sess_hdr_bad"] {t.Fatal("direction merged into generic header")}
        if kind=="short_O"&&flags["sess_dir_bad"] {t.Fatal("length merged into direction")}
        // Frozen old callback cannot report into a new owner or reopen its record.
        next:=mcrfdiag.NewV3();raw.deliver(packet)
        if run.Finish()!="" {t.Fatal("late old callback revived record")}
        var fresh map[string]bool
        if json.Unmarshal([]byte(strings.TrimPrefix(next.Finish(),mcrfdiag.PrefixV3)),&fresh)!=nil {t.Fatal("new record")}
        for _, value:=range fresh {if value {t.Fatal("late old callback contaminated next run")}}
    }) }
}

func TestMcrfFreshDeepSessionOwnerRequiredAndNewLinksCaptureTheirSession(t *testing.T) {
    parameters:=PeerParameters{Capabilities:control.CapabilityIPv4|control.CapabilityTCP,MaxPacketSize:1500}
    if session,err:=McrfNewDiagnosticSession(parameters,false,nil);err==nil||session!=nil {t.Fatal("ownerless diagnostic session")}
    first,second:=mcrfdiag.NewV3(),mcrfdiag.NewV3()
    for _, owner:=range []*mcrfdiag.Run{first,second} {
        session,err:=McrfNewDiagnosticSession(parameters,false,owner);if err!=nil {t.Fatal(err)}
        raw:=&mcrfFreshMemoryTransport{}
        if err=session.AddTransport("synthetic",raw,"synthetic sufficiently long shared secret","synthetic-context",0);err!=nil {t.Fatal(err)}
        link:=session.links["synthetic"]
        if session.mcrfDiagnostic!=owner||link.encrypted.mcrfSessionDiagnostic.Load()!=owner||link.batched.mcrfSessionDiagnostic.Load()!=owner {t.Fatal("wrong owner")}
        session.Stop()
    }
}
