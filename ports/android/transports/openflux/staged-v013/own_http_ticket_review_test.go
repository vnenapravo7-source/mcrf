// SPDX-License-Identifier: GPL-3.0-or-later
package mobile

import (
    "context"
    "net/http"
    "testing"
    "time"
    "github.com/p1neappleXpress/OpenFlux/transport/script"
)

// These are native admission models with fake private adapters, NOT actual JS,
// HTTP handler/window/manual gesture/CLI/pipe/worker-death receipts.
type mcrfHTTPFakeRun struct {current func()bool;closes,calls int;hook func();last []byte}
func (run *mcrfHTTPFakeRun) Close(){run.closes++}
func (run *mcrfHTTPFakeRun) Exchange(context.Context,string,string,http.Header,[]byte)(*script.McrfOwnHTTPResponse,string){
    run.calls++;if run.hook!=nil{run.hook()};run.last=[]byte("synthetic")
    return &script.McrfOwnHTTPResponse{Status:200,Body:run.last},"ok"
}
type mcrfHTTPFakeLease struct {live,canOpen bool;opens int;deadline time.Time;run *mcrfHTTPFakeRun}
func (lease *mcrfHTTPFakeLease) Address()string{return "http://127.0.0.1:32123/setup?opaque=synthetic"}
func (lease *mcrfHTTPFakeLease) Current()bool{return lease.live}
func (lease *mcrfHTTPFakeLease) open(current func()bool,deadline time.Time)mcrfHTTPRun{
    lease.opens++;lease.deadline=deadline;if !lease.canOpen{return nil};lease.run.current=current;return lease.run
}
func mcrfHTTPTicketFixture(t *testing.T,apply func(map[string]string)error)(*McrfVerificationPage,*mcrfHTTPFakeLease){
    t.Helper();captcha.mu.Lock();mcrfClearVerification();captcha.mu.Unlock();mcrfTestPending(t,apply)
    captcha.mu.Lock();captcha.own=true;captcha.url="http://127.0.0.1:32123/setup?opaque=synthetic";captcha.mu.Unlock()
    page:=McrfPendingVerification();if page==nil{t.Fatal("pending missing")}
    lease:=&mcrfHTTPFakeLease{live:true,canOpen:true,run:&mcrfHTTPFakeRun{}}
    captcha.mu.Lock();mcrfCaptchaTicket.ownHTTP=lease;captcha.mu.Unlock();return page,lease
}
func mcrfHTTPReadFixture(t *testing.T,page *McrfVerificationPage,permit string,lease *mcrfHTTPFakeLease){
    t.Helper();response,reason:=McrfExchangeOwnHTTPVerification(context.Background(),page.ID,permit,"GET",lease.Address(),nil,nil)
    if reason!="ok"||response==nil{t.Fatal("native response missing")};response.Close()
}
func TestMcrfFreshOwnHTTPManualPermitIsSingleAndKeepsOriginalDeadline(t *testing.T){
    page,lease:=mcrfHTTPTicketFixture(t,func(map[string]string)error{return nil})
    captcha.mu.Lock();original:=mcrfCaptchaTicket.expires;captcha.mu.Unlock()
    permit:=McrfOpenOwnHTTPVerificationAfterUserAction(page.ID)
    if len(permit)!=32||lease.opens!=1||!lease.deadline.Equal(original)||!lease.run.current(){t.Fatal("manual admission identity/deadline invalid")}
    if McrfOpenOwnHTTPVerificationAfterUserAction(page.ID)!=""||lease.opens!=1{t.Fatal("duplicate admission retried")}
}
func TestMcrfFreshOwnHTTPFailedAdmissionCannotRetrySameTicket(t *testing.T){
    page,lease:=mcrfHTTPTicketFixture(t,func(map[string]string)error{return nil});lease.canOpen=false
    if McrfOpenOwnHTTPVerificationAfterUserAction(page.ID)!=""{t.Fatal("failed run admitted")};lease.canOpen=true
    if McrfOpenOwnHTTPVerificationAfterUserAction(page.ID)!=""||lease.opens!=1{t.Fatal("uncertain attempt replayed")}
}
func TestMcrfFreshOwnHTTPWrongPermitCannotReadReplyOrClose(t *testing.T){
    applied:=0;page,lease:=mcrfHTTPTicketFixture(t,func(map[string]string)error{applied++;return nil})
    permit:=McrfOpenOwnHTTPVerificationAfterUserAction(page.ID)
    if response,reason:=McrfExchangeOwnHTTPVerification(context.Background(),page.ID,"wrong","GET",lease.Address(),nil,nil);response!=nil||reason!="retired"{t.Fatal("wrong permit read")}
    if status,failure:=McrfReplyOwnHTTPVerificationWithFailure(page.ID,"wrong","{\"answer\":true}");status!="stale_or_unsupported"||failure!=nil{t.Fatal("wrong permit reply")}
    if McrfCloseOwnHTTPVerification(page.ID,"wrong")||lease.run.calls!=0||lease.run.closes!=0||applied!=0||!lease.run.current(){t.Fatal("wrong permit mutated owner")}
    if !McrfCloseOwnHTTPVerification(page.ID,permit){t.Fatal("exact permit could not close")}
}
func TestMcrfFreshOwnHTTPReplyRequiresInitialResponseAndSeparateContract(t *testing.T){
    applied:=0;page,lease:=mcrfHTTPTicketFixture(t,func(map[string]string)error{applied++;return nil});permit:=McrfOpenOwnHTTPVerificationAfterUserAction(page.ID)
    if status,_:=McrfReplyOwnHTTPVerificationWithFailure(page.ID,permit,"{\"answer\":true}");status!="stale_or_unsupported"{t.Fatal("reply before native document response")}
    if status,_:=McrfReplyOwnVerificationWithFailure(page.ID,"{\"answer\":true}");status!="stale_or_unsupported"{t.Fatal("HTTP routed through inline")}
    if McrfReplyVerification(page.ID,"a=synthetic")!="stale_or_unsupported"{t.Fatal("HTTP routed through cookies")}
    mcrfHTTPReadFixture(t,page,permit,lease)
    if status,failure:=McrfReplyOwnHTTPVerificationWithFailure(page.ID,permit,"{\"answer\":true} trailing");status!="invalid_reply"||failure==nil||applied!=0{t.Fatal("invalid structured reply admitted")}
}
func TestMcrfFreshOwnHTTPReplyUsesCapturedApplyAndClosesExactRun(t *testing.T){
    applied:=0;page,lease:=mcrfHTTPTicketFixture(t,func(jar map[string]string)error{if jar["answer"]!="synthetic"{t.Error("answer changed")};applied++;return nil})
    permit:=McrfOpenOwnHTTPVerificationAfterUserAction(page.ID);mcrfHTTPReadFixture(t,page,permit,lease)
    status,failure:=McrfReplyOwnHTTPVerificationWithFailure(page.ID,permit,"{\"answer\":\"synthetic\"}")
    if status!="accepted"||failure!=nil||applied!=1||lease.run.closes!=1||lease.run.current(){t.Fatal("captured reply/retirement failed")}
    if status,_:=McrfReplyOwnHTTPVerificationWithFailure(page.ID,permit,"{\"answer\":true}");status!="stale_or_unsupported"||applied!=1{t.Fatal("reply replayed")}
}
func TestMcrfFreshOwnHTTPUpstreamCancelAndDetachSignalRunCloseOnce(t *testing.T){
    for _,retire:=range []func(){CancelCaptcha,detachCaptcha}{
        page,lease:=mcrfHTTPTicketFixture(t,func(map[string]string)error{return nil});permit:=McrfOpenOwnHTTPVerificationAfterUserAction(page.ID)
        retire();retire()
        if lease.run.closes!=1||lease.run.current()||McrfCloseOwnHTTPVerification(page.ID,permit){t.Fatal("upstream retirement lost or repeated")}
    }
}
func TestMcrfFreshOwnHTTPLateResponseAfterRetirementIsWiped(t *testing.T){
    page,lease:=mcrfHTTPTicketFixture(t,func(map[string]string)error{return nil});permit:=McrfOpenOwnHTTPVerificationAfterUserAction(page.ID)
    lease.run.hook=func(){McrfCloseOwnHTTPVerification(page.ID,permit)}
    response,reason:=McrfExchangeOwnHTTPVerification(context.Background(),page.ID,permit,"GET",lease.Address(),nil,nil)
    if response!=nil||reason!="retired"||lease.run.closes!=1{t.Fatal("late response exposed")}
    for _,b:=range lease.run.last{if b!=0{t.Fatal("late bytes not wiped")}}
}
func TestMcrfFreshOwnHTTPCloseRetainedOwnerAfterServerLossDoesNotTouchReplacement(t *testing.T){
    page,lease:=mcrfHTTPTicketFixture(t,func(map[string]string)error{return nil});permit:=McrfOpenOwnHTTPVerificationAfterUserAction(page.ID);lease.live=false
    if !McrfCloseOwnHTTPVerification(page.ID,permit)||lease.run.closes!=1{t.Fatal("lost server prevented exact cancellation")}
    next,newLease:=mcrfHTTPTicketFixture(t,func(map[string]string)error{return nil});newPermit:=McrfOpenOwnHTTPVerificationAfterUserAction(next.ID)
    if McrfCloseOwnHTTPVerification(page.ID,permit)||!newLease.run.current()||newLease.run.closes!=0{t.Fatal("old permit retired replacement")}
    McrfCloseOwnHTTPVerification(next.ID,newPermit)
}
