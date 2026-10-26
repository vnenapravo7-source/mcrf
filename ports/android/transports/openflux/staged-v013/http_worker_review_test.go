// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "context"
    "encoding/base64"
    "encoding/json"
    "errors"
    "net/http"
    "strings"
    "testing"
    "time"
    "github.com/p1neappleXpress/OpenFlux/transport/script"
)
func mcrfHTTPTestLine(payload string)string{return "OPENFLUX_HTTP_REQUEST|"+strings.Repeat("a",32)+"|"+strings.Repeat("b",32)+"|"+strings.Repeat("c",32)+"|"+base64.StdEncoding.EncodeToString([]byte(payload))}
func mcrfHTTPTestRequest()*mcrfHTTPRequest{return mcrfReadHTTPRequest(mcrfHTTPTestLine(`["GET","http://127.0.0.1:4321/path",[],""]`))}
func mcrfHTTPTestResponse(body []byte)*script.McrfOwnHTTPResponse{return &script.McrfOwnHTTPResponse{Status:200,Headers:make(http.Header),Body:body}}
func mcrfHTTPTestIdle(t *testing.T,worker *mcrfHTTPChildWorker){t.Helper();deadline:=time.Now().Add(time.Second);for{worker.mu.Lock();busy:=worker.busy;worker.mu.Unlock();if !busy{return};if time.Now().After(deadline){t.Fatal("worker did not retire")};time.Sleep(time.Millisecond)}}
func mcrfHTTPTestSignal(t *testing.T,ch <-chan struct{}){t.Helper();select{case <-ch:case <-time.After(time.Second):t.Fatal("missing bounded signal")}}

func TestMcrfFreshHTTPWireRequestTypedOwnedAndRedacted(t *testing.T){
    request:=mcrfReadHTTPRequest(mcrfHTTPTestLine(`["POST","http://127.0.0.1:4321/path",[["X-Sample","synthetic"]],"Zg=="]`))
    if request==nil||request.method!="POST"||string(request.body)!="f"||request.headers.Get("X-Sample")!="synthetic"{t.Fatal("typed request")}
    if request.String()!="OpenFluxHTTPRequest(redacted)"{t.Fatal("redaction")}
    body:=request.body;request.close();if body[0]!=0||request.target!=""||request.headers!=nil{t.Fatal("private request retained")}
}
func TestMcrfFreshHTTPWireRequestRejectsShapeTypesEncodingAndIdentity(t *testing.T){
    for _,payload:=range []string{`{}`,`["GET","x",null,""]`,`["GET",null,[],""]`,`["GET","x",[],null]`,
        `["GET","x",[],"Zh=="]`,`["GET","x",[],"Zg"]`,`["TRACE","x",[],""]`,`["GET","x",[["x"]],""]`,
        `["GET","x",[],""] trailing`,`["GET","x",[],"",true]`,`["GET","x",[["x",null]],""]`}{
        if mcrfReadHTTPRequest(mcrfHTTPTestLine(payload))!=nil{t.Fatal("invalid envelope accepted")}
    }
    good:=mcrfHTTPTestLine(`["GET","x",[],""]`)
    for _,line:=range []string{strings.Replace(good,strings.Repeat("a",32),"BAD",1),good+"|extra",good+"\n",strings.Repeat("x",mcrfHTTPCommandLimit+1)}{
        if mcrfReadHTTPRequest(line)!=nil{t.Fatal("invalid command accepted")}
    }
}
func TestMcrfFreshHTTPWireRequestHeaderBodyAndDepthLimits(t *testing.T){
    pairs:=make([][]string,0);for i:=0;i<17;i++{pairs=append(pairs,[]string{"x","v"})}
    for _,headers:=range [][][]string{pairs,{{"Host","x"}},{{"x","a\nb"}},{{"x",strings.Repeat("v",8190)}}}{
        raw,_:=json.Marshal([]any{"GET","x",headers,""});if mcrfReadHTTPRequest(mcrfHTTPTestLine(string(raw)))!=nil{t.Fatal("header bound")}
    }
    body:=base64.StdEncoding.EncodeToString(make([]byte,65536))
    raw,_:=json.Marshal([]any{"POST","x",[][]string{},body})
    request:=mcrfReadHTTPRequest(mcrfHTTPTestLine(string(raw)));if request==nil||len(request.body)!=65536{t.Fatal("max body")};request.close()
    raw,_=json.Marshal([]any{"POST","x",[][]string{},base64.StdEncoding.EncodeToString(make([]byte,65537))})
    if mcrfReadHTTPRequest(mcrfHTTPTestLine(string(raw)))!=nil{t.Fatal("oversized body")}
    if mcrfHTTPJSONUnicode([]byte(strings.Repeat("[",17)+"0"+strings.Repeat("]",17))){t.Fatal("depth")}
}
func TestMcrfFreshHTTPWireUnicodeRejectsLoneSurrogatesAndMalformedUTF8(t *testing.T){
    for _,raw:=range [][]byte{[]byte(`["\ud800"]`),[]byte(`["\udc00"]`),[]byte(`["\ud800\u0061"]`),{'"',255,'"'}}{
        if mcrfHTTPJSONUnicode(raw){t.Fatal("invalid Unicode")}
    }
    for _,raw:=range []string{`["\ud83d\ude00"]`,`["\\ud800"]`,`["quote\""]`}{
        if !mcrfHTTPJSONUnicode([]byte(raw)){t.Fatal("valid Unicode/escaping")}
    }
}
func TestMcrfFreshHTTPWireResponseFramesBoundIdentityAndCanonicalBody(t *testing.T){
    request:=mcrfHTTPTestRequest();response:=mcrfHTTPTestResponse(make([]byte,8193));defer response.Close()
    frames:=make([]string,0)
    if !mcrfHTTPResponseFrames(request,response,func()bool{return true},func(line string)error{frames=append(frames,line);return nil})||len(frames)!=4{t.Fatal("bounded frame sequence")}
    for _,line:=range frames{if len(line)>24576||!strings.HasPrefix(line,"MCRF_OPENFLUX_HTTP_")||!strings.Contains(line,request.id)||!strings.Contains(line,request.request){t.Fatal("identity/frame bound")}}
    var chunk struct{Offset int;Data string};if json.Unmarshal([]byte(strings.TrimPrefix(frames[2],"MCRF_OPENFLUX_HTTP_CHUNK ")),&chunk)!=nil||chunk.Offset!=8192||chunk.Data!="AA=="{t.Fatal("canonical last chunk")}
}
func TestMcrfFreshHTTPWireResponseRejectsInvalidHeadersStatusAndBody(t *testing.T){
    request:=mcrfHTTPTestRequest();calls:=0;emit:=func(string)error{calls++;return nil}
    invalid:=[]*script.McrfOwnHTTPResponse{
        {Status:199},{Status:600},{Status:204,Body:[]byte{1}},{Status:304,Body:[]byte{1}},
        {Status:200,Body:make([]byte,524289)},{Status:200,Headers:http.Header{"Content-Length":{"0"}}},
        {Status:200,Headers:http.Header{"x":{string([]byte{255})}}},
    }
    for _,response:=range invalid{if mcrfHTTPResponseFrames(request,response,func()bool{return true},emit){t.Fatal("invalid response")};response.Close()}
    if calls!=0{t.Fatal("invalid response emitted")}
}
func TestMcrfFreshHTTPWireResponseRetirementAndWriteFailureStopFrames(t *testing.T){
    request:=mcrfHTTPTestRequest();response:=mcrfHTTPTestResponse(make([]byte,8193));defer response.Close()
    calls:=0;live:=true
    if mcrfHTTPResponseFrames(request,response,func()bool{return live},func(string)error{calls++;live=false;return nil})||calls!=1{t.Fatal("late frame")}
    calls=0
    if mcrfHTTPResponseFrames(request,response,func()bool{return true},func(string)error{calls++;return errors.New("synthetic")})||calls!=1{t.Fatal("write retry")}
}
func TestMcrfFreshHTTPWorkerSingleSlotAcrossTicketsAndCancelledHandler(t *testing.T){
    worker:=&mcrfHTTPChildWorker{};entered:=make(chan struct{});cancelled:=make(chan struct{});release:=make(chan struct{})
    request:=mcrfHTTPTestRequest();body:=[]byte{1};request.body=body;emits:=0
    exchange:=func(ctx context.Context,_ *mcrfHTTPRequest)(*script.McrfOwnHTTPResponse,string){close(entered);<-ctx.Done();close(cancelled);<-release;return mcrfHTTPTestResponse([]byte{1}),"ok"}
    if !worker.start(request,func()bool{return true},exchange,func(string)error{emits++;return nil}){t.Fatal("admission")};mcrfHTTPTestSignal(t,entered)
    other:=mcrfHTTPTestRequest();other.id=strings.Repeat("d",32)
    if worker.start(other,func()bool{return true},exchange,func(string)error{return nil}){t.Fatal("replacement worker")}
    worker.stop();mcrfHTTPTestSignal(t,cancelled)
    worker.mu.Lock();busy:=worker.busy;worker.mu.Unlock();if !busy{t.Fatal("cancel falsely retired handler")}
    close(release);mcrfHTTPTestIdle(t,worker);other.close()
    if emits!=0||body[0]!=0{t.Fatal("late output or retained request")}
}
func TestMcrfFreshHTTPWorkerStopDoesNotWaitForBlockedOutput(t *testing.T){
    worker:=&mcrfHTTPChildWorker{};entered:=make(chan struct{});release:=make(chan struct{});stopped:=make(chan struct{})
    request:=mcrfHTTPTestRequest();response:=mcrfHTTPTestResponse([]byte{1});body:=response.Body;emits:=0
    worker.start(request,func()bool{return true},func(context.Context,*mcrfHTTPRequest)(*script.McrfOwnHTTPResponse,string){return response,"ok"},func(string)error{emits++;close(entered);<-release;return nil})
    mcrfHTTPTestSignal(t,entered);go func(){worker.stop();close(stopped)}();mcrfHTTPTestSignal(t,stopped)
    worker.mu.Lock();busy:=worker.busy;worker.mu.Unlock();if !busy{t.Fatal("blocked output falsely retired")}
    close(release);mcrfHTTPTestIdle(t,worker);if emits!=1||body[0]!=0{t.Fatal("late output or retained response")}
}
func TestMcrfFreshHTTPWorkerRetiredOwnerNeverInvokesExchange(t *testing.T){
    worker:=&mcrfHTTPChildWorker{};called:=false;request:=mcrfHTTPTestRequest()
    if !worker.start(request,func()bool{return false},func(context.Context,*mcrfHTTPRequest)(*script.McrfOwnHTTPResponse,string){called=true;return nil,"retired"},func(string)error{t.Error("stale emit");return nil}){t.Fatal("slot admission")}
    mcrfHTTPTestIdle(t,worker);if called{t.Fatal("retired exchange")}
    worker.stop();other:=mcrfHTTPTestRequest()
    if worker.start(other,func()bool{return true},func(context.Context,*mcrfHTTPRequest)(*script.McrfOwnHTTPResponse,string){return nil,"retired"},func(string)error{return nil}){t.Fatal("closed admission")};other.close()
}
func TestMcrfFreshHTTPWorkerOneQueuedTaskWaitsForActualActiveReturn(t *testing.T){
    worker:=&mcrfHTTPChildWorker{};entered:=make(chan struct{});release:=make(chan struct{});next:=make(chan struct{})
    worker.queuedTask("old",func(context.Context,func()bool){close(entered);<-release},nil)
    mcrfHTTPTestSignal(t,entered)
    if !worker.queuedTask("next",func(context.Context,func()bool){close(next)},nil){t.Fatal("one next slot")}
    if worker.queuedTask("overflow",func(context.Context,func()bool){t.Error("overflow executed")},nil){t.Fatal("unbounded next slots")}
    select{case <-next:t.Fatal("parallel replacement");default:}
    close(release);mcrfHTTPTestSignal(t,next);mcrfHTTPTestIdle(t,worker)
}
func TestMcrfFreshHTTPWorkerStopWipesQueuedRequestWithoutClaimingActiveExit(t *testing.T){
    worker:=&mcrfHTTPChildWorker{};entered:=make(chan struct{});release:=make(chan struct{})
    worker.queuedTask("old",func(context.Context,func()bool){close(entered);<-release},nil);mcrfHTTPTestSignal(t,entered)
    request:=mcrfHTTPTestRequest();body:=[]byte{7};request.body=body;called:=false
    if !worker.startQueued(request,func()bool{return true},func(context.Context,*mcrfHTTPRequest)(*script.McrfOwnHTTPResponse,string){
        called=true;return nil,"retired"
    },func(string)error{return nil}){t.Fatal("next request slot")}
    worker.stop()
    if body[0]!=0{t.Fatal("queued owned body retained")}
    worker.mu.Lock();busy:=worker.busy;worker.mu.Unlock();if !busy{t.Fatal("active falsely retired")}
    close(release);mcrfHTTPTestIdle(t,worker);if called{t.Fatal("stopped next exchange")}
}
func TestMcrfFreshHTTPWorkerCancelOldQueuedScopeDoesNotCancelNewActiveScope(t *testing.T){
    worker:=&mcrfHTTPChildWorker{};entered:=make(chan struct{});release:=make(chan struct{});discarded:=0
    var active context.Context
    worker.queuedTask("new",func(ctx context.Context,_ func()bool){active=ctx;close(entered);<-release},nil);mcrfHTTPTestSignal(t,entered)
    worker.queuedTask("old",func(context.Context,func()bool){t.Error("old queued scope executed")},func(){discarded++})
    worker.cancelScope("old")
    if active.Err()!=nil||discarded!=1{t.Fatal("wrong scope cancelled")}
    close(release);mcrfHTTPTestIdle(t,worker)
}
func TestMcrfFreshHTTPWorkerCancelledOldActiveRetainsNewQueuedTaskUntilExit(t *testing.T){
    worker:=&mcrfHTTPChildWorker{};entered:=make(chan struct{});release:=make(chan struct{});next:=make(chan struct{})
    var active context.Context
    var oldLive func()bool
    worker.queuedTask("old",func(ctx context.Context,live func()bool){active=ctx;oldLive=live;close(entered);<-release},nil);mcrfHTTPTestSignal(t,entered)
    worker.queuedTask("new",func(ctx context.Context,live func()bool){
        if ctx.Err()!=nil||!live(){t.Error("new task inherited old cancellation")}
        if oldLive(){t.Error("retired old liveness closure revived on next context")};close(next)
    },nil)
    worker.cancelScope("old");if active.Err()==nil{t.Fatal("old active not cancelled")}
    select{case <-next:t.Fatal("new task bypassed actual old exit");default:}
    close(release);mcrfHTTPTestSignal(t,next);mcrfHTTPTestIdle(t,worker)
}
