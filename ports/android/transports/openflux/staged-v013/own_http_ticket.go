// SPDX-License-Identifier: GPL-3.0-or-later
package mobile

import (
    "context"
    "crypto/rand"
    "encoding/hex"
    "encoding/json"
    "net/http"
    "time"
    "unicode/utf8"
    "github.com/p1neappleXpress/OpenFlux/transport/script"
)

type mcrfHTTPRun interface {
    Exchange(context.Context,string,string,http.Header,[]byte)(*script.McrfOwnHTTPResponse,string)
    Close() // Must not block on handler work or invoke ownership callback.
}
type mcrfHTTPLease interface {
    Address()string
    Current()bool
    open(func()bool,time.Time)mcrfHTTPRun
}
type mcrfHTTPLeaseAdapter struct{lease *script.McrfOwnHTTPLease}
func (adapter mcrfHTTPLeaseAdapter) Address()string{return adapter.lease.Address()}
func (adapter mcrfHTTPLeaseAdapter) Current()bool{return adapter.lease.Current()}
func (adapter mcrfHTTPLeaseAdapter) open(current func()bool,deadline time.Time)mcrfHTTPRun{
    run:=adapter.lease.McrfOpenHTTPAfterUserAction(current,deadline);if run==nil{return nil};return run
}

// Caller holds captcha.mu. This is cancellation signalling, NOT a claim that
// an admitted handler/JS/worker has exited. Retire before discarding its owner.
func mcrfRetireVerificationTicket(){
    if ticket:=mcrfCaptchaTicket;ticket!=nil{
        if ticket.httpRun!=nil{ticket.httpRun.Close()}
        ticket.httpRun=nil;ticket.httpPermit="";ticket.document="";ticket.documentDigest=""
    }
    mcrfCaptchaTicket=nil
}
func mcrfOwnHTTPTicket(id string)*mcrfVerificationOwner{
    ticket:=mcrfCurrentVerification(id)
    if ticket==nil || !captcha.own || captcha.proxy!="" || captcha.html!="" || ticket.ownHTTP==nil || !ticket.ownHTTP.Current(){return nil}
    return ticket
}

// Separate CLI source opt-in invokes this; Android does not enable it yet.
// Native API naming is not gesture evidence: the window must gate explicit Open.
// Once attempted, the same ticket cannot reopen/retry uncertain admission.
func McrfOpenOwnHTTPVerificationAfterUserAction(id string)string{
    captcha.mu.Lock();defer captcha.mu.Unlock()
    ticket:=mcrfOwnHTTPTicket(id);if ticket==nil || ticket.httpOpened{return ""}
    ticket.httpOpened=true
    nonce:=make([]byte,16);if _,err:=rand.Read(nonce);err!=nil{return ""}
    permit:=hex.EncodeToString(nonce);clear(nonce)
    ticket.httpPermit=permit
    run:=ticket.ownHTTP.open(func()bool{
        captcha.mu.Lock();defer captcha.mu.Unlock()
        return mcrfOwnHTTPTicket(id)==ticket && ticket.httpPermit==permit
    },ticket.expires)
    if run==nil{ticket.httpPermit="";return ""}
    ticket.httpRun=run;return permit
}

func McrfExchangeOwnHTTPVerification(parent context.Context,id,permit,method,target string,headers http.Header,body []byte)(*script.McrfOwnHTTPResponse,string){
    captcha.mu.Lock()
    ticket:=mcrfOwnHTTPTicket(id)
    if ticket==nil || permit=="" || ticket.httpPermit!=permit || ticket.httpRun==nil{captcha.mu.Unlock();return nil,"retired"}
    run:=ticket.httpRun;captcha.mu.Unlock()
    response,reason:=run.Exchange(parent,method,target,headers,body)
    if response==nil{return nil,reason}
    captcha.mu.Lock()
    current:=mcrfOwnHTTPTicket(id)==ticket && ticket.httpPermit==permit && ticket.httpRun==run
    if current && reason=="ok" && method=="GET" && target==ticket.ownHTTP.Address() && response.Status>=200 && response.Status<300{
        ticket.httpDocumentRead=true // Native response produced, NOT browser delivery/render receipt.
    }
    captcha.mu.Unlock()
    if !current{response.Close();return nil,"retired"}
    return response,reason
}

func McrfCloseOwnHTTPVerification(id,permit string)bool{
    captcha.mu.Lock();defer captcha.mu.Unlock()
    // Exact old permit may close its retained owner even after server/transport
    // expiry. Liveness is required for use, not for cancellation signalling.
    ticket:=mcrfCaptchaTicket
    if ticket==nil || ticket.id!=id || permit=="" || ticket.httpPermit!=permit{return false}
    mcrfClearVerification();return true
}

// HTTP setup answers use a separate exact permit, never the cookie/inline path.
// Queue/native apply acceptance is not a successful connection receipt.
func McrfReplyOwnHTTPVerificationWithFailure(id,permit,payload string)(string,*McrfVerificationFailure){
    captcha.mu.Lock()
    ticket:=mcrfOwnHTTPTicket(id)
    supported:=ticket!=nil && permit!="" && ticket.httpPermit==permit && ticket.httpRun!=nil && ticket.httpDocumentRead
    captcha.mu.Unlock()
    if !supported{return "stale_or_unsupported",nil}
    if len(payload)==0 || len(payload)>8192 || !utf8.ValidString(payload){return mcrfInvalidVerification(id)}
    var root map[string]json.RawMessage
    if json.Unmarshal([]byte(payload),&root)!=nil || root==nil{return mcrfInvalidVerification(id)}
    jar,err:=script.FlattenSubmission([]byte(payload));if err!=nil{return mcrfInvalidVerification(id)}
    captcha.mu.Lock()
    current:=mcrfOwnHTTPTicket(id)
    if current!=ticket || ticket.httpPermit!=permit || ticket.httpRun==nil || !ticket.httpDocumentRead{captcha.mu.Unlock();return "stale_or_unsupported",nil}
    apply:=ticket.apply;ticket.used=true;mcrfClearVerification()
    failure:=&McrfVerificationFailure{generation:mcrfCaptchaGeneration,ownerCurrent:ticket.ownerCurrent}
    captcha.mu.Unlock()
    if err:=apply(jar);err!=nil{return "apply_failed",failure};return "accepted",nil
}
