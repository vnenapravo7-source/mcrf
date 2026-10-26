// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "context"
    "encoding/base64"
    "fmt"
    "strings"
    "sync"
    "sync/atomic"
    "unicode/utf8"
    mobile "openflux-mobile"
    "github.com/p1neappleXpress/OpenFlux/transport/script"
)

// Separate source opt-in. Android does not send it. Private pipe only; no
// listener or address handed to an external browser.
type mcrfHTTPGateway struct {
    mu sync.Mutex
    enabled,closed bool
    id,permit string
    worker mcrfHTTPChildWorker
    emit func(string)error
    open func(string)string
    address func(string)string
    closePermit func(string,string)bool
    reply func(string,string,string)string
    failed atomic.Pointer[mobile.McrfVerificationFailure]
}
func (*mcrfHTTPGateway) String()string{return "OpenFluxHTTPGateway(redacted)"}
func (gate *mcrfHTTPGateway) output(line string)error{if gate.emit!=nil{return gate.emit(line)};_,err:=fmt.Println(line);return err}
func (gate *mcrfHTTPGateway) page(id string)string{if gate.address!=nil{return gate.address(id)};return mobile.McrfOwnHTTPVerificationAddress(id)}
func (gate *mcrfHTTPGateway) closeNative(id,permit string)bool{if gate.closePermit!=nil{return gate.closePermit(id,permit)};return mobile.McrfCloseOwnHTTPVerification(id,permit)}
func (gate *mcrfHTTPGateway) local(id,permit string)bool{
    gate.mu.Lock();defer gate.mu.Unlock();return gate.enabled&&!gate.closed&&gate.id==id&&gate.permit==permit
}
func (gate *mcrfHTTPGateway) current(id,permit string)bool{return gate.local(id,permit)&&gate.page(id)!=""&&gate.local(id,permit)}
// Generic exact-ticket cancellation may arrive before OPENED reveals a permit.
// Caller separately retires the native ticket; never cancel a different queued scope.
func (gate *mcrfHTTPGateway) cancelTicket(id string){
    gate.mu.Lock()
    if gate.id==id{gate.id="";gate.permit=""}
    gate.worker.cancelScope(id);gate.mu.Unlock()
}
func (gate *mcrfHTTPGateway) stop(){
    gate.mu.Lock();gate.closed=true;id,permit:=gate.id,gate.permit;gate.id="";gate.permit=""
    gate.worker.stop();gate.mu.Unlock()
    if permit!=""{gate.closeNative(id,permit)}
}
func (gate *mcrfHTTPGateway) result(id,request,permit,code string,live func()bool){
    // All codes fixed at call sites; no exceptions, page text or native errors.
    mcrfHTTPOutput(func()bool{return live()&&gate.local(id,permit)},gate.output,"MCRF_OPENFLUX_HTTP_RESULT ",
        map[string]string{"id":id,"request":request,"code":code})
}
func (gate *mcrfHTTPGateway) input(line string)bool {
    gate.mu.Lock();enabled:=gate.enabled&&!gate.closed;gate.mu.Unlock()
    if !enabled||len(line)>mcrfHTTPCommandLimit{return false}
    if strings.HasPrefix(line,"OPENFLUX_HTTP_REQUEST|"){
        request:=mcrfReadHTTPRequest(line);if request==nil{return false}
        id,permit,nonce:=request.id,request.permit,request.request
        if !gate.current(id,permit){request.close();return true}
        ok:=gate.worker.startQueued(request,func()bool{return gate.current(id,permit)},
            func(ctx context.Context,r *mcrfHTTPRequest)(response *script.McrfOwnHTTPResponse,reason string){
                return mobile.McrfExchangeOwnHTTPVerification(ctx,r.id,r.permit,r.method,r.target,r.headers,r.body)
            },gate.output,func(code string){gate.result(id,nonce,permit,code,func()bool{return true})})
        if !ok{request.close()};return ok
    }
    parts:=strings.Split(line,"|")
    if len(parts)<3||!mcrfHTTPID(parts[1])||!mcrfHTTPID(parts[2]){return false}
    id:=parts[1]
    if parts[0]=="OPENFLUX_HTTP_CLOSE"&&len(parts)==3{
        permit:=parts[2]
        gate.mu.Lock()
        if gate.closed||gate.id!=id||gate.permit!=permit{gate.mu.Unlock();return true}
        gate.id="";gate.permit="";gate.worker.cancelScope(id);gate.mu.Unlock()
        gate.closeNative(id,permit);return true
    }
    if parts[0]=="OPENFLUX_HTTP_OPEN"&&len(parts)==3{
        nonce:=parts[2]
        return gate.worker.queuedTask(id,func(ctx context.Context,live func()bool){
            if !live(){return}
            open:=gate.open;if open==nil{open=mobile.McrfOpenOwnHTTPVerificationAfterUserAction}
            permit:=open(id)
            if !mcrfHTTPID(permit){
                mcrfHTTPOutput(func()bool{gate.mu.Lock();closed:=gate.closed;gate.mu.Unlock();return live()&&!closed},
                    gate.output,"MCRF_OPENFLUX_HTTP_RESULT ",map[string]string{"id":id,"request":nonce,"code":"open_failed"})
                return
            }
            address:=gate.page(id)
            gate.mu.Lock()
            active:=!gate.closed&&live()&&address!=""
            if active{gate.id=id;gate.permit=permit}
            gate.mu.Unlock()
            if !active{gate.closeNative(id,permit);return}
            if !mcrfHTTPOutput(func()bool{return live()&&gate.current(id,permit)},gate.output,"MCRF_OPENFLUX_HTTP_OPENED ",
                map[string]string{"id":id,"request":nonce,"permit":permit,"address":address}){
                gate.closeNative(id,permit)
                gate.mu.Lock();if gate.id==id&&gate.permit==permit{gate.id="";gate.permit=""};gate.mu.Unlock()
            }
        },nil)
    }
    if parts[0]=="OPENFLUX_HTTP_REPLY"&&len(parts)==5&&mcrfHTTPID(parts[3]){
        permit,nonce:=parts[2],parts[3]
        payload,err:=base64.StdEncoding.Strict().DecodeString(parts[4]);defer clear(payload)
        if err!=nil||len(payload)==0||len(payload)>8192||!utf8.Valid(payload)||base64.StdEncoding.EncodeToString(payload)!=parts[4]||!gate.current(id,permit){return false}
        owned:=string(payload)
        accepted:=gate.worker.queuedTask(id,func(ctx context.Context,live func()bool){
            if !live()||!gate.current(id,permit){return}
            reply:=gate.reply;if reply==nil{reply=func(id,permit,payload string)string{
                reason,failure:=mobile.McrfReplyOwnHTTPVerificationWithFailure(id,permit,payload)
                if failure!=nil{gate.failed.Store(failure)};return reason
            }}
            reason:=reply(id,permit,owned);code:="reply_failed";if reason=="accepted"{code="accepted"}
            gate.result(id,nonce,permit,code,live)
        },func(){owned=""})
        if !accepted{owned=""};return accepted
    }
    return false
}
