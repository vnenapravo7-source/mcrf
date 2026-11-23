// Staged opt-in diagnostics. No payload, error or identifier API exists here.
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
 eventCount
)
const Prefix = "MCRF_PROVIDER_STAGE_V1 "
const frozen uint64 = 1 << 63
var names = [...]string{"mailru_socketio_ready","mailru_auth_write_ok","mailru_auth_write_failed","mailru_auth_changes_seen","mailru_ack_write_ok","mailru_ack_write_failed","mailru_fallback_ready","mailru_chat_write_ok","mailru_chat_write_failed","mailru_peer_chat_decoded","mailru_read_closed","classic_decrypt_ok","classic_decrypt_failed","classic_frame_accepted","classic_frame_rejected"}
type Run struct { bits atomic.Uint64 }
var current atomic.Pointer[Run]
func Begin() *Run { r:=new(Run); current.Store(r); return r }
func Current() *Run { return current.Load() }
func (r *Run) Mark(e Event) {
 if r==nil || e>=eventCount { return }
 for { old:=r.bits.Load(); if old&frozen!=0{return}; if r.bits.CompareAndSwap(old,old|(1<<e)){return} }
}
// Freeze detaches this run. Late callbacks retain its pointer and cannot affect a new run.
func (r *Run) Finish() string {
 if r==nil{return ""}
 var bits uint64
 for { bits=r.bits.Load(); if bits&frozen!=0{return ""}; if r.bits.CompareAndSwap(bits,bits|frozen){break} }
 current.CompareAndSwap(r,nil)
 flags:=make(map[string]bool,len(names));for i,name:=range names{flags[name]=bits&(1<<i)!=0}
 // Version is encoded by the exact prefix; the JSON object contains Boolean fields only.
 data,_:=json.Marshal(flags);return Prefix+string(data)
}
