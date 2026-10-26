// SPDX-License-Identifier: GPL-3.0-or-later
package mailru
import("errors";"strings";"testing")
func TestMcrf016TypedValidAuthWritesExactlyOneAck(t *testing.T){s:=&DocSession{ready:make(chan struct{})};count:=0;if !mcrf016AuthChangesWith(s,[]byte(`42["message",{"type":"authChanges"}]`),func(data []byte)error{count++;if string(data)!=authChangesAckMsg||mcrf016Ready(s){t.Fatal("ACK/order")};return nil})||count!=1||!mcrf016Ready(s){t.Fatal("valid typed event lost")}}
func TestMcrf016TypedAuthDoesNotMatchSubstring(t *testing.T){
 for _,frame:=range []string{`42["message",{"type":"message","message":"authChanges"}]`,`42["other",{"type":"authChanges"}]`,`42["message",{"type":1}]`,`42["message",{"type":"authChanges"},0]`,`authChanges`,strings.Repeat("x",(1<<20)+1)}{
  s:=&DocSession{ready:make(chan struct{})};if mcrf016AuthChanges(s,[]byte(frame))||mcrf016Ready(s){t.Fatal("non-auth acknowledged")}
 }
}
func TestMcrf016AckBeforeReady(t *testing.T){s:=&DocSession{ready:make(chan struct{})};called:=false;mcrf016AckWith(s,func(data []byte)error{if mcrf016Ready(s)||string(data)!=authChangesAckMsg{t.Fatal("early ready/wrong ACK")};called=true;return nil});if !called||!mcrf016Ready(s){t.Fatal("missing ACK readiness")}}
func TestMcrf016FailedAckNeverBecomesLegacyReady(t *testing.T){s:=&DocSession{ready:make(chan struct{})};mcrf016AckWith(s,func([]byte)error{return errors.New("fixture")});mcrf016Fallback(s);mcrf016AckWith(s,func([]byte)error{return nil});if mcrf016Ready(s){t.Fatal("failed ACK promoted")}}
func TestMcrf016LegacyFallbackWithoutAuthChanges(t *testing.T){s:=&DocSession{ready:make(chan struct{})};mcrf016Fallback(s);mcrf016Fallback(s);if !mcrf016Ready(s){t.Fatal("legacy fallback lost")}}
func TestMcrf016AuthWriteFailureClosesAndStaysFinite(t *testing.T){closed:=0;err:=mcrf016WriteAuthWith(func()error{return errors.New("secret provider material")},func(){closed++});if err==nil||closed!=1||strings.Contains(err.Error(),"secret"){t.Fatal("failure hidden/leaked")};if mcrf016WriteAuthWith(func()error{return nil},func(){closed++})!=nil||closed!=1{t.Fatal("successful auth closed")}}
