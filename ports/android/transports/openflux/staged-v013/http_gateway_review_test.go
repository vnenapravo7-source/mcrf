// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "encoding/base64"
    "errors"
    "strings"
    "testing"
)
func mcrfHTTPTestGateway()(*mcrfHTTPGateway,*[]string){
    frames:=make([]string,0);gate:=&mcrfHTTPGateway{enabled:true}
    gate.open=func(string)string{return strings.Repeat("b",32)}
    gate.address=func(string)string{return "http://127.0.0.1:4321/path"}
    gate.closePermit=func(string,string)bool{return true}
    gate.emit=func(line string)error{frames=append(frames,line);return nil}
    return gate,&frames
}
func mcrfHTTPTestOpenLine()string{return "OPENFLUX_HTTP_OPEN|"+strings.Repeat("a",32)+"|"+strings.Repeat("c",32)}
func mcrfHTTPTestReplyLine(payload string)string{return "OPENFLUX_HTTP_REPLY|"+strings.Repeat("a",32)+"|"+strings.Repeat("b",32)+"|"+strings.Repeat("d",32)+"|"+base64.StdEncoding.EncodeToString([]byte(payload))}
func TestMcrfFreshHTTPGatewayRequiresSeparateOptInAndStrictControlShape(t *testing.T){
    gate,frames:=mcrfHTTPTestGateway();gate.enabled=false
    if gate.input(mcrfHTTPTestOpenLine()){t.Fatal("disabled gate")}
    gate.enabled=true
    for _,line:=range []string{"OPENFLUX_HTTP_OPEN|bad|bad",mcrfHTTPTestOpenLine()+"|extra","OPENFLUX_HTTP_UNKNOWN|"+strings.Repeat("a",32)+"|"+strings.Repeat("b",32)}{
        if gate.input(line){t.Fatal("invalid control accepted")}
    }
    if len(*frames)!=0{t.Fatal("unexpected output")}
}
func TestMcrfFreshHTTPGatewayOpenReturnsPrivatePermitAndExactRequestIdentity(t *testing.T){
    gate,frames:=mcrfHTTPTestGateway()
    if !gate.input(mcrfHTTPTestOpenLine()){t.Fatal("open admission")};mcrfHTTPTestIdle(t,&gate.worker)
    if len(*frames)!=1||!strings.HasPrefix((*frames)[0],"MCRF_OPENFLUX_HTTP_OPENED ")||!strings.Contains((*frames)[0],strings.Repeat("c",32))||
        !gate.current(strings.Repeat("a",32),strings.Repeat("b",32)){t.Fatal("private open binding")}
    gate.stop()
}
func TestMcrfFreshHTTPGatewayFailedOpenHasOnlyFiniteResult(t *testing.T){
    gate,frames:=mcrfHTTPTestGateway();gate.open=func(string)string{return ""}
    if !gate.input(mcrfHTTPTestOpenLine()){t.Fatal("open slot")};mcrfHTTPTestIdle(t,&gate.worker)
    if len(*frames)!=1||!strings.HasPrefix((*frames)[0],"MCRF_OPENFLUX_HTTP_RESULT ")||!strings.Contains((*frames)[0],"open_failed")||
        strings.Contains((*frames)[0],"127.0.0.1"){t.Fatal("finite open failure")}
}
func TestMcrfFreshHTTPGatewayStopDuringOpenClosesLatePermitWithoutEmission(t *testing.T){
    gate,frames:=mcrfHTTPTestGateway();entered:=make(chan struct{});release:=make(chan struct{});closed:=0
    gate.open=func(string)string{close(entered);<-release;return strings.Repeat("b",32)}
    gate.closePermit=func(string,string)bool{closed++;return true}
    gate.input(mcrfHTTPTestOpenLine());mcrfHTTPTestSignal(t,entered);gate.stop();close(release);mcrfHTTPTestIdle(t,&gate.worker)
    if closed!=1||len(*frames)!=0{t.Fatal("late permit leaked")}
}
func TestMcrfFreshHTTPGatewayStaleCloseCannotRetireReplacement(t *testing.T){
    gate,_:=mcrfHTTPTestGateway();closed:=0
    gate.closePermit=func(id,permit string)bool{closed++;return false}
    gate.id=strings.Repeat("a",32);gate.permit=strings.Repeat("b",32)
    if !gate.input("OPENFLUX_HTTP_CLOSE|"+gate.id+"|"+strings.Repeat("d",32))||closed!=0||gate.permit==""{t.Fatal("stale close mutated current")}
    if !gate.input("OPENFLUX_HTTP_CLOSE|"+gate.id+"|"+gate.permit)||closed!=1||gate.permit!=""{t.Fatal("exact cancellation must consume even if native already retired")}
}
func TestMcrfFreshHTTPGatewayReplyPayloadAndResultRemainScopedAndRedacted(t *testing.T){
    gate,frames:=mcrfHTTPTestGateway();gate.id=strings.Repeat("a",32);gate.permit=strings.Repeat("b",32)
    payload:=`{"client":{"sample":"synthetic-private"}}`;got:=""
    gate.reply=func(id,permit,body string)string{got=body;return "accepted"}
    if !gate.input(mcrfHTTPTestReplyLine(payload)){t.Fatal("reply slot")};mcrfHTTPTestIdle(t,&gate.worker)
    if got!=payload||len(*frames)!=1||!strings.Contains((*frames)[0],"accepted")||strings.Contains((*frames)[0],"synthetic-private"){t.Fatal("reply binding/result")}
    if gate.input(mcrfHTTPTestReplyLine(strings.Repeat("x",8193))){t.Fatal("oversized reply")}
}
func TestMcrfFreshHTTPGatewayStopRetainsBusyReplyAndDiscardsLateResult(t *testing.T){
    gate,frames:=mcrfHTTPTestGateway();gate.id=strings.Repeat("a",32);gate.permit=strings.Repeat("b",32)
    entered:=make(chan struct{});release:=make(chan struct{})
    gate.reply=func(string,string,string)string{close(entered);<-release;return "accepted"}
    gate.input(mcrfHTTPTestReplyLine("{}"));mcrfHTTPTestSignal(t,entered);gate.stop()
    gate.worker.mu.Lock();busy:=gate.worker.busy;gate.worker.mu.Unlock();if !busy{t.Fatal("false callback retirement")}
    close(release);mcrfHTTPTestIdle(t,&gate.worker);if len(*frames)!=0{t.Fatal("late reply result")}
}
func TestMcrfFreshHTTPGatewayFailedOpenedWriteRetiresExactNativePermit(t *testing.T){
    gate,_:=mcrfHTTPTestGateway();closed:=0;gate.closePermit=func(string,string)bool{closed++;return true}
    gate.emit=func(string)error{return errors.New("synthetic")}
    gate.input(mcrfHTTPTestOpenLine());mcrfHTTPTestIdle(t,&gate.worker)
    if closed!=1||gate.permit!=""{t.Fatal("unreceived opened permit retained")}
}
func TestMcrfFreshHTTPGatewayFollowingCommandDuringLastFrameUsesOneQueuedSlot(t *testing.T){
    gate,_:=mcrfHTTPTestGateway();replies:=0;insideOpened:=false;results:=0
    gate.reply=func(string,string,string)string{if insideOpened{t.Error("reply ran before old output returned")};replies++;return "accepted"}
    gate.emit=func(line string)error{
        if strings.HasPrefix(line,"MCRF_OPENFLUX_HTTP_OPENED "){
            insideOpened=true
            if !gate.input(mcrfHTTPTestReplyLine("{}")){t.Error("following command rejected while final frame unwinds")}
            if replies!=0{t.Error("parallel reply worker")};insideOpened=false
        }else if strings.HasPrefix(line,"MCRF_OPENFLUX_HTTP_RESULT "){results++}
        return nil
    }
    if !gate.input(mcrfHTTPTestOpenLine()){t.Fatal("open slot")};mcrfHTTPTestIdle(t,&gate.worker)
    if replies!=1||results!=1{t.Fatal("queued next command not drained exactly once")}
}
func TestMcrfFreshHTTPGatewayQueuedOpenIsDroppedOnStopWithBoundedOverflow(t *testing.T){
    gate,frames:=mcrfHTTPTestGateway();entered:=make(chan struct{});release:=make(chan struct{});opens:=0;closed:=0
    gate.open=func(string)string{opens++;close(entered);<-release;return strings.Repeat("b",32)}
    gate.closePermit=func(string,string)bool{closed++;return true}
    if !gate.input(mcrfHTTPTestOpenLine()){t.Fatal("active open")};mcrfHTTPTestSignal(t,entered)
    queued:="OPENFLUX_HTTP_OPEN|"+strings.Repeat("e",32)+"|"+strings.Repeat("f",32)
    if !gate.input(queued){t.Fatal("one next open slot")};if gate.input(queued){t.Fatal("overflow accepted")}
    gate.stop();close(release);mcrfHTTPTestIdle(t,&gate.worker)
    if opens!=1||closed!=1||len(*frames)!=0{t.Fatal("queued open escaped stop or late permit leaked")}
}
func TestMcrfFreshHTTPGatewayGenericOldTicketCancelPreservesQueuedReplacement(t *testing.T){
    gate,frames:=mcrfHTTPTestGateway();entered:=make(chan struct{});release:=make(chan struct{});closed:=0
    old:=strings.Repeat("a",32);replacement:=strings.Repeat("e",32)
    gate.open=func(id string)string{
        if id==old{close(entered);<-release;return strings.Repeat("b",32)}
        return strings.Repeat("f",32)
    }
    gate.closePermit=func(id,permit string)bool{if id!=old{t.Error("replacement native ticket closed")};closed++;return true}
    gate.input(mcrfHTTPTestOpenLine());mcrfHTTPTestSignal(t,entered)
    if !gate.input("OPENFLUX_HTTP_OPEN|"+replacement+"|"+strings.Repeat("c",32)){t.Fatal("replacement next slot")}
    gate.cancelTicket(old)
    close(release);mcrfHTTPTestIdle(t,&gate.worker)
    if closed!=1||len(*frames)!=1||!strings.Contains((*frames)[0],replacement)||!gate.current(replacement,strings.Repeat("f",32)){
        t.Fatal("old cancellation poisoned replacement or leaked late permit")
    }
}
