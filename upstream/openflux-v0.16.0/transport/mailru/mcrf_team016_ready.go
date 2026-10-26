// SPDX-License-Identifier: GPL-3.0-or-later
// Typed Android-native readiness port. No provider/URL/account/pacing changes.
package mailru

import (
 "bytes"
 "encoding/json"
 "errors"
)

func mcrf016AuthChanges(session *DocSession, frame []byte) bool {
 return mcrf016AuthChangesWith(session,frame,func(data []byte)error{return session.safeWrite(1,data)})
}
func mcrf016AuthChangesWith(session *DocSession, frame []byte, write func([]byte)error) bool {
 if len(frame)>1<<20 || !bytes.HasPrefix(frame,[]byte("42[")){return false}
 var envelope []json.RawMessage
 if json.Unmarshal(frame[2:],&envelope)!=nil||len(envelope)!=2{return false}
 var event string;var message struct {Type string `json:"type"`}
 if json.Unmarshal(envelope[0],&event)!=nil||event!="message"||json.Unmarshal(envelope[1],&message)!=nil||message.Type!="authChanges"{return false}
 if session==nil{return true}
 mcrf016AckWith(session,write)
 return true
}
func mcrf016AckWith(session *DocSession,write func([]byte)error){
 if session==nil||session.ready==nil{return}
 if write==nil||write([]byte(authChangesAckMsg))!=nil{session.mcrfAckFailed.Store(true);return}
 if !session.mcrfAckFailed.Load(){session.markReady()}
}
func mcrf016Ready(session *DocSession)bool{
 if session==nil||session.ready==nil||session.mcrfAckFailed.Load(){return false}
 select{case <-session.ready:return true;default:return false}
}
func mcrf016Fallback(session *DocSession){if session!=nil&&session.ready!=nil&&!session.mcrfAckFailed.Load(){session.markReady()}}
func mcrf016WriteAuth(session *DocSession,data []byte)error{
 return mcrf016WriteAuthWith(func()error{return session.safeWrite(1,data)},func(){_ = session.Conn.Close()})
}
func mcrf016WriteAuthWith(write func()error,close func())error{
 if write==nil||write()!=nil{if close!=nil{close()};return errors.New("mailru authentication write failed")};return nil
}
