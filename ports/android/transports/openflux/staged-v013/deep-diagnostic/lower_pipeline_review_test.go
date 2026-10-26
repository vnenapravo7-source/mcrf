// SPDX-License-Identifier: GPL-3.0-or-later
// UNWIRED/NOTRUN future transport-package tests; requires exact hook recipe.
// Memory-only, no providers, listeners, real process lifecycle or platform IO.
package transport

import (
    "encoding/json"
    "strings"
    "testing"
    "github.com/p1neappleXpress/OpenFlux/mcrfdiag"
    "github.com/p1neappleXpress/OpenFlux/transport/control"
)

func mcrfDeepFlags(t *testing.T, owner *mcrfdiag.Run) map[string]bool {
    t.Helper();line:=owner.Finish();var flags map[string]bool
    if !strings.HasPrefix(line,mcrfdiag.PrefixV3)||len(line)+1>1024||
        json.Unmarshal([]byte(strings.TrimPrefix(line,mcrfdiag.PrefixV3)),&flags)!=nil||len(flags)!=37 {t.Fatal("deep record")}
    return flags
}

func TestMcrfFreshDeepRawOwnerBindsOnceAndRejectsReuse(t *testing.T) {
    first,second:=mcrfdiag.NewV3(),mcrfdiag.NewV3();base:=NewBaseTransport(DefaultConfig())
    if base.McrfBindDiagnostic(nil)||!base.McrfBindDiagnostic(first)||!base.McrfBindDiagnostic(first)||base.McrfBindDiagnostic(second) {t.Fatal("owner binding")}
    base.CallReceive([]byte{1})
    if err:=base.Start();err!=nil {t.Fatal(err)}
    if base.McrfBindDiagnostic(first) {t.Fatal("late owner binding")};base.Stop()
    if !mcrfDeepFlags(t,first)["raw_callback_missing"] {t.Fatal("raw callback event missing")}
    base.CallReceive([]byte{2})
    if base.McrfBindDiagnostic(second)||first.Finish()!="" {t.Fatal("retired transport rebound")}
    for _,value:=range mcrfDeepFlags(t,second) {if value {t.Fatal("retired raw callback contaminated next owner")}}
    // Nil-safe link hooks must not panic in unobserved upstream synthetic paths.
    mcrfSessionDiagnostic(nil).Mark(mcrfdiag.SessDecryptOK)
    mcrfClassicDiagnostic(&transportLink{}).Mark(mcrfdiag.ClassicAccepted)
}

func TestMcrfFreshDeepDemuxMissingCallbacksAndEmptyAreSeparate(t *testing.T) {
    owner:=mcrfdiag.NewV3();demux:=newFrameDemux(&mcrfFreshMemoryTransport{},"synthetic")
    demux.mcrfDiagnostic=owner
    demux.dispatch(nil);demux.dispatch([]byte{'O'});demux.dispatch([]byte{0})
    flags:=mcrfDeepFlags(t,owner)
    for _,key:=range []string{"demux_empty","demux_session","demux_classic","layer_callback_missing"} {
        if !flags[key] {t.Fatal("missing demux event",key)}
    }
    if flags["sess_dec_ok"]||flags["classic_decrypt_ok"] {t.Fatal("missing callbacks invented decryption")}
}

func TestMcrfFreshDeepCodecEmptyUnknownMalformedAndMissingCallback(t *testing.T) {
    owner:=mcrfdiag.NewV3();codec:=NewCodecTransport(&mcrfFreshMemoryTransport{},CodecBatched,false)
    codec.mcrfDiagnostic.Store(owner)
    codec.receive(nil);codec.receive([]byte{0xff});codec.receive([]byte{batchFormatVersion})
    codec.receive(encodeBatch([][]byte{{1}})) // Valid framing, but no user callback.
    flags:=mcrfDeepFlags(t,owner)
    for _,key:=range []string{"codec_no_payload","codec_unknown","codec_decode_failed","layer_callback_missing"} {
        if !flags[key] {t.Fatal("missing codec event",key)}
    }
    if flags["sess_dec_ok"]||flags["classic_frame_accepted"] {t.Fatal("codec invented higher-layer delivery")}
}

func TestMcrfFreshDeepClassicActualPipelineDoesNotMarkSessionCrypto(t *testing.T) {
    wanted:=map[string]string{"valid_ipv4":"classic_frame_accepted","not_ipv4":"classic_frame_rejected",
        "short":"classic_header_invalid","same_role":"classic_direction_invalid","wrong_key":"classic_decrypt_failed","replay":"classic_replay_rejected"}
    for _,kind:=range []string{"valid_ipv4","not_ipv4","short","same_role","wrong_key","replay"} {t.Run(kind,func(t *testing.T){
        owner:=mcrfdiag.NewV3();parameters:=PeerParameters{Capabilities:control.CapabilityIPv4|control.CapabilityTCP,MaxPacketSize:1500}
        session,err:=McrfNewDiagnosticSession(parameters,false,owner);if err!=nil {t.Fatal(err)}
        session.SetClassic(CodecBatched);defer session.Stop()
        secret,context:="synthetic sufficiently long shared secret","synthetic-context"
        raw:=&mcrfFreshMemoryTransport{}
        if err=session.AddTransport("synthetic",raw,secret,context,0);err!=nil {t.Fatal(err)}
        link:=session.links["synthetic"]
        if link.classicEnc.mcrfClassicDiagnostic.Load()!=owner||link.classicEnc.mcrfSessionDiagnostic.Load()!=nil||link.classicCodec.mcrfDiagnostic.Load()!=owner {t.Fatal("classic owner")}
        if err=session.startLink(link);err!=nil {t.Fatal(err)}
        body:=make([]byte,20);body[0]=0x45;if kind=="not_ipv4" {body=[]byte{1}}
        key:=secret;if kind=="wrong_key" {key="other sufficiently long shared secret"}
        wire:=&mcrfFreshMemoryTransport{};sender,err:=NewEncryptedTransport(wire,key,context,kind!="same_role");if err!=nil {t.Fatal(err)}
        if err=sender.Send(body);err!=nil {t.Fatal(err)}
        encrypted:=wire.snapshot();if kind=="short" {encrypted=[]byte{'O'}}
        packet:=encodeBatch([][]byte{encrypted});raw.deliver(packet);if kind=="replay" {raw.deliver(packet)}
        flags:=mcrfDeepFlags(t,owner)
        if !flags["demux_classic"]||flags["demux_session"]||!flags[wanted[kind]] {t.Fatal("classic event missing",kind)}
        for _,key:=range []string{"sess_hdr_bad","sess_dir_bad","sess_dec_fail","sess_dec_ok","sess_batch_bad","sess_env_bad","sess_role_bad","sess_hello_ok","sess_hello_bad","sess_cb_missing","sess_replay"} {
            if flags[key] {t.Fatal("classic contaminated Session",key)}
        }
    })}
}
