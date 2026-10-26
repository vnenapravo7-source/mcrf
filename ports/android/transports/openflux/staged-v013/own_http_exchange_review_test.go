// SPDX-License-Identifier: GPL-3.0-or-later
package script

import (
    "context"
    "net/http"
    "strings"
    "testing"
    "time"
)

func mcrfHTTPRunFixture(t *testing.T,handler http.Handler,current func()bool)*McrfOwnHTTPRun {
    t.Helper();owner,_:=mcrfOwnHTTPFixture(handler);lease:=owner.McrfCaptureOwnHTTP("http://127.0.0.1:32123/setup")
    run:=lease.McrfOpenHTTPAfterUserAction(current,time.Now().Add(time.Minute));if run==nil{t.Fatal("run missing")};t.Cleanup(run.Close);return run
}
func TestMcrfFreshOwnHTTPExchangePreservesPrivateBytesAndWipesResponse(t *testing.T){
    run:=mcrfHTTPRunFixture(t,http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){w.Header().Set("Content-Type","application/octet-stream");w.WriteHeader(201);w.Write([]byte{0,0xff,3})}),func()bool{return true})
    response,reason:=run.Exchange(context.Background(),"POST",run.lease.Address(),http.Header{"Content-Type":[]string{"text/plain"}},[]byte("synthetic"))
    if reason!="ok" || response.Status!=201 || len(response.Body)!=3 || response.Body[1]!=0xff || strings.Contains(response.String(),"synthetic"){t.Fatal("response changed/leaked")}
    retained:=response.Body;response.Close();for _,b:=range retained{if b!=0{t.Fatal("response not wiped")}}
}
func TestMcrfFreshOwnHTTPExchangeRejectsRequestBudgetsAndHopHeaders(t *testing.T){
    calls:=0;run:=mcrfHTTPRunFixture(t,http.HandlerFunc(func(http.ResponseWriter,*http.Request){calls++}),func()bool{return true})
    cases:=[]struct{method,target string;headers http.Header;body []byte}{
        {"CONNECT",run.lease.Address(),nil,nil},{"GET",strings.Repeat("x",8193),nil,nil},
        {"POST",run.lease.Address(),nil,make([]byte,mcrfHTTPBodyLimit+1)},
        {"GET",run.lease.Address(),http.Header{"Proxy-Authorization":[]string{"synthetic"}},nil},
        {"GET",run.lease.Address(),http.Header{strings.Repeat("x",8193):nil},nil},
        {"GET",run.lease.Address(),http.Header{"X-Test":[]string{"a\r\nb"}},nil},
    }
    for _,c:=range cases{response,reason:=run.Exchange(context.Background(),c.method,c.target,c.headers,c.body);if response!=nil||reason!="invalid_request"{t.Fatal("invalid request admitted")}}
    if calls!=0{t.Fatal("invalid input reached handler")}
}
func TestMcrfFreshOwnHTTPExchangeRejectsResponseOverflowAndHeaders(t *testing.T){
    for _,badHeader:=range []bool{false,true}{
        run:=mcrfHTTPRunFixture(t,http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
            if badHeader{w.Header().Set("X-Test",strings.Repeat("x",8193));w.WriteHeader(200)}else{w.Write(make([]byte,mcrfHTTPResponseLimit));if _,err:=w.Write([]byte{1});err==nil{t.Error("overflow accepted")}}
        }),func()bool{return true})
        response,reason:=run.Exchange(context.Background(),"GET",run.lease.Address(),nil,nil)
        if response!=nil||reason!="response_rejected"{t.Fatal("oversized response admitted")}
    }
}
func TestMcrfFreshOwnHTTPExchangeBusyDoesNotLaunchAnotherHandler(t *testing.T){
    entered,release,finished:=make(chan struct{}),make(chan struct{}),make(chan struct{})
    run:=mcrfHTTPRunFixture(t,http.HandlerFunc(func(http.ResponseWriter,*http.Request){close(entered);<-release}),func()bool{return true})
    defer func(){close(release);select{case <-finished:case <-time.After(2*time.Second):t.Error("fixture did not finish")}}()
    go func(){defer close(finished);response,_:=run.Exchange(context.Background(),"GET",run.lease.Address(),nil,nil);response.Close()}()
    select{case <-entered:case <-time.After(2*time.Second):t.Fatal("handler not entered")}
    if response,reason:=run.Exchange(context.Background(),"GET",run.lease.Address(),nil,nil);response!=nil||reason!="busy"{t.Fatal("overlapping handler launched")}
}
func TestMcrfFreshOwnHTTPExchangeCloseCancelsWaitAndDiscardsLateBody(t *testing.T){
    entered,finished:=make(chan struct{}),make(chan string,1)
    run:=mcrfHTTPRunFixture(t,http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){close(entered);<-r.Context().Done();w.Write([]byte("late synthetic"))}),func()bool{return true})
    go func(){response,reason:=run.Exchange(context.Background(),"GET",run.lease.Address(),nil,nil);response.Close();finished<-reason}()
    select{case <-entered:case <-time.After(2*time.Second):t.Fatal("handler not entered")};run.Close()
    select{case reason:=<-finished:if reason!="retired"{t.Fatal("late response admitted")};case <-time.After(2*time.Second):t.Fatal("cancel-aware handler did not finish")}
}
func TestMcrfFreshOwnHTTPExchangeExpiredOrRetiredOwnerCannotAdmit(t *testing.T){
    live:=true;calls:=0;run:=mcrfHTTPRunFixture(t,http.HandlerFunc(func(http.ResponseWriter,*http.Request){calls++}),func()bool{return live})
    live=false;if response,reason:=run.Exchange(context.Background(),"GET",run.lease.Address(),nil,nil);response!=nil||reason!="retired"||calls!=0{t.Fatal("retired owner admitted")}
    if run.lease.McrfOpenHTTPAfterUserAction(func()bool{return true},time.Now().Add(-time.Second))!=nil || run.lease.McrfOpenHTTPAfterUserAction(func()bool{return true},time.Now().Add(time.Hour))!=nil{t.Fatal("invalid deadline accepted")}
}
func TestMcrfFreshOwnHTTPExchangeParentCancellationDoesNotCloseAnotherRun(t *testing.T){
    ctx,cancel:=context.WithCancel(context.Background());cancel();calls:=0
    run:=mcrfHTTPRunFixture(t,http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){calls++;w.Write([]byte("synthetic"))}),func()bool{return true})
    if response,reason:=run.Exchange(ctx,"GET",run.lease.Address(),nil,nil);response!=nil||reason!="retired"{t.Fatal("cancelled caller admitted")}
    response,reason:=run.Exchange(context.Background(),"GET",run.lease.Address(),nil,nil);defer response.Close()
    if reason!="ok"||calls!=1{t.Fatal("cancelled caller poisoned next exchange")}
}
func TestMcrfFreshOwnHTTPExchangeHandlerPanicStaysFiniteAndReleasesSlot(t *testing.T){
    panicNow:=true;run:=mcrfHTTPRunFixture(t,http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){if panicNow{panic("private synthetic")};w.Write([]byte("ok"))}),func()bool{return true})
    if response,reason:=run.Exchange(context.Background(),"GET",run.lease.Address(),nil,nil);response!=nil||reason!="handler_failed"{t.Fatal("panic escaped/private reason exposed")}
    panicNow=false;response,reason:=run.Exchange(context.Background(),"GET",run.lease.Address(),nil,nil);defer response.Close();if reason!="ok"{t.Fatal("panic retained busy slot")}
}
func TestMcrfFreshOwnHTTPExchangeHeadAndBodylessStatusDoNotExposeBody(t *testing.T){
    for _,status:=range []int{200,204,304}{
        run:=mcrfHTTPRunFixture(t,http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){w.WriteHeader(status);w.Write([]byte("synthetic"))}),func()bool{return true})
        method:="GET";if status==200{method="HEAD"}
        response,reason:=run.Exchange(context.Background(),method,run.lease.Address(),nil,nil)
        if reason!="ok"||response.Status!=status||len(response.Body)!=0{t.Fatal("bodyless response exposed body")};response.Close()
    }
}
