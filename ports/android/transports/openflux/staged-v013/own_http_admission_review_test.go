// SPDX-License-Identifier: GPL-3.0-or-later
package script

import (
    "context"
    "net/http"
    "net/http/httptest"
    "strings"
    "testing"
    "time"
)

func TestMcrfFreshOwnHTTPAwaitReturnsOnRequestCancellation(t *testing.T) {
    ctx,cancel:=context.WithCancel(context.Background());done:=make(chan string);finished:=make(chan bool,1)
    go func(){_,ok:=mcrfAwaitHTTPResult(ctx,done);finished<-ok}();cancel()
    select {case ok:=<-finished:if ok {t.Fatal("cancelled result admitted")};case <-time.After(2*time.Second):t.Fatal("cancelled wait did not return")}
}
func TestMcrfFreshOwnHTTPAwaitRejectsCancelledOrClosedResult(t *testing.T) {
    done:=make(chan string,1);done<-"synthetic";ctx,cancel:=context.WithCancel(context.Background());cancel()
    if value,ok:=mcrfAwaitHTTPResult(ctx,done);ok || value!="" {t.Fatal("cancelled result exposed")}
    if value,ok:=mcrfAwaitHTTPResult(context.Background(),done);!ok || value!="synthetic" {t.Fatal("live result lost")}
    close(done);if _,ok:=mcrfAwaitHTTPResult(context.Background(),done);ok {t.Fatal("closed result admitted")}
}

func mcrfOwnHTTPFixture(handler http.Handler) (*ScriptTransport,*http.Server) {
    t:=&ScriptTransport{};srv:=&http.Server{Handler:handler};t.addHTTPServer(srv,32123);return t,srv
}
func TestMcrfFreshOwnHTTPCapturesExactRegisteredHandler(t *testing.T) {
    calls:=0;owner,_:=mcrfOwnHTTPFixture(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){calls++;w.Write([]byte(r.URL.Path))}))
    lease:=owner.McrfCaptureOwnHTTP("http://127.0.0.1:32123/setup?private=synthetic")
    if lease==nil || !lease.Current() || !strings.Contains(lease.Address(),"/setup?private=synthetic") || strings.Contains(lease.String(),"synthetic") {t.Fatal("private lease identity invalid")}
    out:=httptest.NewRecorder();lease.ServeHTTP(out,httptest.NewRequest("GET",lease.Address(),nil))
    if out.Code!=200 || out.Body.String()!="/setup" || calls!=1 {t.Fatal("exact handler not used")}
}
func TestMcrfFreshOwnHTTPRejectsUnsafeOrAmbiguousAddresses(t *testing.T) {
    owner,_:=mcrfOwnHTTPFixture(http.NotFoundHandler())
    for _,raw:=range []string{"","https://127.0.0.1:32123/","http://localhost:32123/","http://[::1]:32123/","http://127.0.0.2:32123/",
        "http://127.0.0.1/","http://127.0.0.1:032123/","http://127.0.0.1:65536/","http://user@127.0.0.1:32123/",
        "http://127.0.0.1:32123/#secret","http://example.com:32123/","http://127.0.0.1:32123/\\x","http://127.0.0.1:32123/\n",
        "http://127.0.0.1:32123/"+string([]byte{0xff}),"http://127.0.0.1:32123/"+strings.Repeat("x",8192)} {
        if owner.McrfCaptureOwnHTTP(raw)!=nil {t.Fatal("unsafe/ambiguous address admitted")}
    }
}
func TestMcrfFreshOwnHTTPRequiresLiveRegisteredServer(t *testing.T) {
    owner,srv:=mcrfOwnHTTPFixture(http.NotFoundHandler())
    if owner.McrfCaptureOwnHTTP("http://127.0.0.1:32124/")!=nil {t.Fatal("foreign port admitted")}
    lease:=owner.McrfCaptureOwnHTTP("http://127.0.0.1:32123/");owner.removeHTTPServer(srv)
    if lease.Current() || owner.McrfCaptureOwnHTTP("http://127.0.0.1:32123/")!=nil {t.Fatal("retired server retained")}
    var missing *McrfOwnHTTPLease
    if missing.Current() || missing.Address()!="" {t.Fatal("nil lease usable")}
}
func TestMcrfFreshOwnHTTPPortReuseDoesNotRedirectOldLease(t *testing.T) {
    oldCalls,newCalls:=0,0
    owner,srv:=mcrfOwnHTTPFixture(http.HandlerFunc(func(http.ResponseWriter,*http.Request){oldCalls++}))
    old:=owner.McrfCaptureOwnHTTP("http://127.0.0.1:32123/");owner.removeHTTPServer(srv)
    owner.addHTTPServer(&http.Server{Handler:http.HandlerFunc(func(http.ResponseWriter,*http.Request){newCalls++})},32123)
    out:=httptest.NewRecorder();old.ServeHTTP(out,httptest.NewRequest("GET",old.Address(),nil))
    if out.Code!=410 || oldCalls!=0 || newCalls!=0 {t.Fatal("retired lease redirected")}
    fresh:=owner.McrfCaptureOwnHTTP(old.Address());fresh.ServeHTTP(httptest.NewRecorder(),httptest.NewRequest("GET",fresh.Address(),nil))
    if newCalls!=1 {t.Fatal("replacement could not get independent lease")}
}
func TestMcrfFreshOwnHTTPRejectsForeignRequestOriginAndDoesNotFollowRedirect(t *testing.T) {
    calls:=0;owner,_:=mcrfOwnHTTPFixture(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){calls++;w.Header().Set("Location","https://outside.invalid/");w.WriteHeader(302)}))
    lease:=owner.McrfCaptureOwnHTTP("http://127.0.0.1:32123/")
    for _,target:=range []string{"http://127.0.0.1:32124/","https://127.0.0.1:32123/","http://example.com:32123/"} {
        out:=httptest.NewRecorder();lease.ServeHTTP(out,httptest.NewRequest("GET",target,nil));if out.Code!=403 {t.Fatal("foreign origin reached handler")}
    }
    request:=httptest.NewRequest("GET",lease.Address(),nil);request.Host="different.invalid"
    out:=httptest.NewRecorder();lease.ServeHTTP(out,request);if out.Code!=403 || calls!=0 {t.Fatal("host override admitted")}
    out=httptest.NewRecorder();lease.ServeHTTP(out,httptest.NewRequest("GET",lease.Address(),nil))
    if out.Code!=302 || calls!=1 {t.Fatal("redirect was not handed back intact")}
}
func TestMcrfFreshOwnHTTPAdmittedHandlerDoesNotHoldOwnershipLock(t *testing.T) {
    entered,release,finished:=make(chan struct{}),make(chan struct{}),make(chan struct{})
    owner,srv:=mcrfOwnHTTPFixture(http.HandlerFunc(func(http.ResponseWriter,*http.Request){close(entered);<-release}))
    lease:=owner.McrfCaptureOwnHTTP("http://127.0.0.1:32123/")
    defer func(){close(release);select {case <-finished:case <-time.After(2*time.Second):t.Error("admitted fixture handler did not finish")}}()
    go func(){defer close(finished);lease.ServeHTTP(httptest.NewRecorder(),httptest.NewRequest("GET",lease.Address(),nil))}()
    select {case <-entered:case <-time.After(2*time.Second):t.Fatal("handler not admitted")}
    retired:=make(chan struct{});go func(){owner.removeHTTPServer(srv);close(retired)}()
    select {case <-retired:case <-time.After(2*time.Second):t.Fatal("handler held ownership lock")}
    if lease.Current() {t.Fatal("retired handler still current")}
    // The already-admitted handler is released on return, not falsely reported cancelled.
}
