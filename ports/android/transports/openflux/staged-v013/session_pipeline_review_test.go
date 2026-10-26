// SPDX-License-Identifier: GPL-3.0-or-later
package transport

import (
    "sync"
    "testing"
    "github.com/p1neappleXpress/OpenFlux/transport/control"
)

type mcrfFreshMemoryTransport struct {
    mu sync.Mutex
    receiver func([]byte)
    sent []byte
}
func (*mcrfFreshMemoryTransport) Start()error{return nil}
func (*mcrfFreshMemoryTransport) Stop()error{return nil}
func (*mcrfFreshMemoryTransport) IsConnected()bool{return true}
func (*mcrfFreshMemoryTransport) Stats()TransportStats{return TransportStats{}}
func (raw *mcrfFreshMemoryTransport) Receive(callback func([]byte)){raw.mu.Lock();raw.receiver=callback;raw.mu.Unlock()}
func (raw *mcrfFreshMemoryTransport) Send(data []byte)error{raw.mu.Lock();raw.sent=append([]byte(nil),data...);raw.mu.Unlock();return nil}
func (raw *mcrfFreshMemoryTransport) snapshot()[]byte{raw.mu.Lock();defer raw.mu.Unlock();return append([]byte(nil),raw.sent...)}
func (raw *mcrfFreshMemoryTransport) deliver(data []byte){
    raw.mu.Lock();callback:=raw.receiver;raw.mu.Unlock()
    if callback!=nil {callback(append([]byte(nil),data...))}
}

// New exact-v0.13.0 pipeline test with a local synchronized memory-only Transport,
// not an old diagnostic binary or its recorder. No provider or network is started.
// Missing deeper event counters remain missing; false is NOT a health result.
func TestMcrfFreshActualSessionPipeline(t *testing.T) {
    cases:=[]string{"short_O","same_crypto_role","wrong_crypto_key","plaintext_not_batch","invalid_envelope",
        "same_envelope_role","valid_hello","invalid_hello","callback_missing","replay"}
    for _,kind:=range cases {t.Run(kind,func(t *testing.T){
        secret,context:="synthetic sufficiently long shared secret","synthetic-context"
        params:=PeerParameters{Capabilities:control.CapabilityIPv4|control.CapabilityTCP,MaxPacketSize:1500}
        session,err:=NewSession(params,false)
        if err!=nil {t.Fatal(err)}
        session.SetClassic(CodecBatched)
        raw:=&mcrfFreshMemoryTransport{}
        if err=session.AddTransport("synthetic",raw,secret,context,0);err!=nil {t.Fatal(err)}
        defer session.Stop()
        link:=session.links["synthetic"]
        if err=session.startLink(link);err!=nil {t.Fatal(err)}
        var peer [32]byte;peer[0]=1
        envelope:=&control.Envelope{Kind:control.KindHello,Role:control.RoleExit,Local:peer,
            Hello:&control.HelloTail{Capabilities:control.CapabilityIPv4|control.CapabilityTCP,MaxPacketSize:1500}}
        if kind=="same_envelope_role" {envelope.Role=control.RoleClient}
        if kind=="invalid_hello" {envelope.Local=[32]byte{}}
        encoded,err:=envelope.Encode()
        if err!=nil {t.Fatal(err)}
        plaintext:=encodeBatch([][]byte{encoded})
        if kind=="plaintext_not_batch" {plaintext=make([]byte,20);plaintext[0]=0x45}
        if kind=="invalid_envelope" {plaintext=encodeBatch([][]byte{{1}})}
        wire:=&mcrfFreshMemoryTransport{}
        key:=secret
        if kind=="wrong_crypto_key" {key="other sufficiently long shared secret"}
        sender,err:=NewEncryptedTransport(wire,key,context,kind!="same_crypto_role")
        if err!=nil {t.Fatal(err)}
        if err=sender.Send(plaintext);err!=nil {t.Fatal(err)}
        packet:=wire.snapshot()
        if kind=="short_O" {packet=[]byte{'O'}}
        if kind=="callback_missing" {link.batched.Receive(nil)}
        raw.deliver(packet)
        if kind=="replay" {raw.deliver(packet)}
        flags:=McrfObservation(session)
        want:=""
        switch kind {
        case "short_O","same_crypto_role":
            want="session_crypto_bad_header_or_length"
            if link.encrypted.recvOK.Load()!=0 {t.Fatal("invalid crypto header delivered")}
        case "wrong_crypto_key":
            want="session_crypto_failed"
            if link.encrypted.recvOK.Load()!=0 {t.Fatal("wrong key delivered")}
        case "plaintext_not_batch":
            want="session_crypto_received"
            if session.cntDecodeErr.Load()!=0 || session.cntHelloRecv.Load()!=0 {t.Fatal("bad batch reached envelope")}
        case "invalid_envelope":
            want="envelope_decode_failed"
            if session.cntDecodeErr.Load()!=1 {t.Fatal("invalid envelope not rejected")}
        case "same_envelope_role":
            want="session_crypto_received"
            if session.cntHelloRecv.Load()!=0 {t.Fatal("same role accepted")}
        case "valid_hello":
            want="hello_received"
            if session.cntHelloRecv.Load()!=1 {t.Fatal("valid hello not decoded")}
        case "invalid_hello":
            want="hello_rejected"
            if session.cntHelloReject.Load()!=1 {t.Fatal("invalid hello not rejected")}
        case "callback_missing":
            want="session_crypto_received"
            if session.cntHelloRecv.Load()!=0 {t.Fatal("missing callback delivered hello")}
        case "replay":
            want="session_crypto_replay"
            if link.encrypted.recvReplay.Load()!=1 || session.cntHelloRecv.Load()!=1 {t.Fatal("replay not rejected exactly once")}
        }
        if !flags["session_observed"] || !flags["carrier_started"] || !flags[want] {t.Fatal("actual pipeline observation missing",kind)}
        if flags["classic_received"] || flags["classic_crypto_received"] || flags["classic_crypto_failed"] {t.Fatal("session frame observed as classic")}
        if kind=="plaintext_not_batch" || kind=="same_envelope_role" || kind=="callback_missing" {
            // These three currently share coarse counters. Do not invent V3
            // bad-batch/role/callback flags or label the pipeline healthy.
            if flags["hello_received"] || flags["hello_accepted"] {t.Fatal("coarse observation invented handshake success")}
        }
    })}
}
