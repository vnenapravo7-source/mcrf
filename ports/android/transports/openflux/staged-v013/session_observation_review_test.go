// SPDX-License-Identifier: GPL-3.0-or-later
package transport

import (
    "encoding/json"
    "strings"
    "testing"
)

func TestMcrfFreshMissingSessionDoesNotInventObservations(t *testing.T) {
    snapshot:=McrfObservation(nil)
    if len(snapshot)!=34 {t.Fatal("schema changed")}
    for _,seen:=range snapshot {if seen {t.Fatal("invented an event")}}
}

func TestMcrfFreshObservationUsesCountersWithoutProviderNamesOrPayload(t *testing.T) {
    crypto:=&EncryptedTransport{};crypto.recvFail.Add(1);crypto.recvReplay.Add(1)
    codec:=&CodecTransport{};codec.unknown.Add(1)
    batch:=&BatchedTransport{};batch.sendErrors.Add(1)
    session:=&Session{started:true,links:map[string]*transportLink{"synthetic-private-provider-name":{started:true,encrypted:crypto,classicCodec:codec,batched:batch}}}
    session.cntHelloReject.Add(1);session.cntDecodeErr.Add(1)
    flags:=McrfObservation(session)
    for _,key:=range []string{"session_observed","session_started","carrier_started","hello_rejected","envelope_decode_failed","session_crypto_failed","session_crypto_replay","codec_unknown","batch_send_failed"} {if !flags[key] {t.Fatal("counter missing",key)}}
    data,_:=json.Marshal(flags)
    if strings.Contains(string(data),"synthetic") || strings.Contains(string(data),"https://") {t.Fatal("private data exposed")}
}

func TestMcrfFreshObservationSnapshotsDoNotShareMutableState(t *testing.T) {
    first:=McrfObservation(nil);first["hello_accepted"]=true
    if McrfObservation(nil)["hello_accepted"] {t.Fatal("snapshot state carried into another run")}
}
