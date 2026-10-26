// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "encoding/base64"
    "encoding/hex"
    "encoding/json"
    "fmt"
    "net/url"
    "strings"
    "sync"
    "sync/atomic"
    "unicode/utf8"

    mobile "openflux-mobile"
)

func init() {
    state := &mcrfVerificationRun{documents:&mcrfDocumentRun{},http:&mcrfHTTPGateway{}}
    stagedProviderStart = func(enabled bool) { state.enabled = enabled }
    stagedOwnInlineStart = func(enabled bool) { state.ownEnabled = enabled }
    stagedOwnHTTPStart = func(enabled bool) { state.http.enabled = enabled }
    stagedProviderStop = state.stop
    stagedProviderWaiting = state.waiting.Load
    stagedProviderInput = state.input
    stagedProviderCheck = func() string {
        if state.http!=nil{
            if failure:=state.http.failed.Load();failure!=nil{
                if failure.Current(){return "verification_reply_failed"}
                state.http.failed.CompareAndSwap(failure,nil)
            }
        }
        if failure := state.failed.Load(); failure != nil {
            if failure.Current() { return "verification_reply_failed" }
            state.failed.CompareAndSwap(failure,nil)
        }
        page := mobile.McrfPendingVerification()
        if page == nil {
            state.waiting.Store(false)
            return mcrfFreshProviderFailure(mobile.PendingCaptchaURL(), mobile.PendingCaptchaHTML(), mobile.PendingCaptchaOwn())
        }
        if page.Proxy != "" { return "exit_verification_proxy_unsupported" }
        if page.Own {
            if state.enabled&&state.http!=nil&&state.http.enabled&&state.documents.metadata(page.ID)==nil&&state.http.page(page.ID)!=""{
                if state.announced!=page.ID{
                    data,_:=json.Marshal(map[string]string{"id":page.ID,"kind":"own_http"})
                    fmt.Println("MCRF_OPENFLUX_HTTP_PENDING "+string(data));state.announced=page.ID
                }
                state.waiting.Store(true);return ""
            }
            if !state.enabled || !state.ownEnabled || state.documents.metadata(page.ID)==nil { return "setup_page_unsupported" }
            if state.documents.failureCurrent() { return "verification_document_failed" }
            if state.announced != page.ID {
                data,_:=json.Marshal(map[string]string{"id":page.ID,"kind":"inline"})
                fmt.Println("MCRF_OPENFLUX_DOCUMENT_PENDING "+string(data))
                state.announced=page.ID
            }
            state.waiting.Store(true);return ""
        }
        if !state.enabled { return "provider_verification_required" }
        parsed, err := url.Parse(page.URL)
        if err != nil || len(page.URL)>8192 || parsed.Scheme!="https" || parsed.Hostname()=="" || parsed.User!=nil || parsed.Fragment!="" || (parsed.Port()!="" && parsed.Port()!="443") { return "verification_page_rejected" }
        if state.announced != page.ID {
            data, err := json.Marshal(page)
            if err != nil || len(data)>16384 { return "verification_page_rejected" }
            // Private child pipe only. Android must consume this before generic
            // logging and never persist the URL or forward it to a log viewer.
            fmt.Println("MCRF_OPENFLUX_VERIFY " + string(data))
            state.announced = page.ID
        }
        state.waiting.Store(true)
        return ""
    }
}

type mcrfVerificationRun struct {
    enabled bool // Set once before the stdin worker starts.
    ownEnabled bool // Separate opt-in; the current Android host always sets false.
    documents *mcrfDocumentRun
    http *mcrfHTTPGateway
    announced string // Accessed only by the main polling goroutine.
    waiting atomic.Bool
    busy atomic.Bool
    failed atomic.Pointer[mobile.McrfVerificationFailure]
    mu sync.Mutex
    closed bool // Guarded by mu. Closing drops pending replies, not an already admitted callback.
    queued *mcrfVerificationCommand // One bounded next reply, not one worker per line.
    apply func(string,string)(string,*mobile.McrfVerificationFailure) // Synthetic host-test injection only.
    applyOwn func(string,string)(string,*mobile.McrfVerificationFailure)
    emit func(string)
}

type mcrfVerificationCommand struct { id, cookies string; cancel, own bool }
func (mcrfVerificationCommand) String() string { return "OpenFluxVerificationCommand(redacted)" }

func mcrfReadVerificationCommand(line string) (*mcrfVerificationCommand, bool) {
    if len(line)>16384 { return nil, false }
    parts := strings.Split(line,"|")
    if len(parts)!=2 && len(parts)!=3 { return nil, false }
    id, err := hex.DecodeString(parts[1])
    if err!=nil || len(id)!=16 || parts[1]!=strings.ToLower(parts[1]) { return nil, false }
    if len(parts)==2 && parts[0]=="OPENFLUX_CAPTCHA_CANCEL" { return &mcrfVerificationCommand{id:parts[1],cancel:true},true }
    if len(parts)!=3 || (parts[0]!="OPENFLUX_CAPTCHA_REPLY" && parts[0]!="OPENFLUX_DOCUMENT_REPLY") { return nil,false }
    own:=parts[0]=="OPENFLUX_DOCUMENT_REPLY"
    raw, err := base64.StdEncoding.Strict().DecodeString(parts[2])
    if err!=nil { return nil,false }
    defer func(){for i:=range raw {raw[i]=0}}()
    if base64.StdEncoding.EncodeToString(raw)!=parts[2] {return nil,false}
    if len(raw)==0 || len(raw)>8192 { return nil,false }
    if own {if !utf8.Valid(raw) {return nil,false}} else {
        for _,ch:=range raw {if ch<0x20 || ch>0x7e {return nil,false}}
    }
    return &mcrfVerificationCommand{id:parts[1],cookies:string(raw),own:own},true
}

func (state *mcrfVerificationRun) input(line string) bool {
    if !state.enabled { return false }
    if strings.HasPrefix(line,"OPENFLUX_HTTP_"){return state.http!=nil&&state.http.input(line)}
    if strings.HasPrefix(line,"OPENFLUX_DOCUMENT_READ") {
        if !state.ownEnabled || state.documents==nil {return false}
        state.mu.Lock()
        if state.closed {state.mu.Unlock();return false}
        accepted:=state.documents.input(line)
        state.mu.Unlock();return accepted
    }
    command, ok := mcrfReadVerificationCommand(line)
    if !ok || (command.own && !state.ownEnabled) { return false }
    state.mu.Lock()
    if state.closed { state.mu.Unlock(); command.cookies=""; return false }
    if command.cancel {
        state.mu.Unlock()
        if state.http!=nil{state.http.cancelTicket(command.id)}
        mobile.McrfCancelVerification(command.id);return true
    }
    if state.busy.Load() {
        if state.queued != nil { state.mu.Unlock(); return false } // Bounded protocol overflow, not an unbounded cookie queue.
        state.queued=command;state.mu.Unlock();return true
    }
    state.busy.Store(true);state.mu.Unlock()
    // Apply may wait through reconnect backoff. A single bounded worker keeps
    // stdin free to receive STOP; never spawn a worker for every line.
    go state.runReplies(command)
    return true
}

func (state *mcrfVerificationRun) stop() {
    state.mu.Lock()
    state.closed=true
    if state.queued!=nil { state.queued.cookies=""; state.queued=nil }
    state.waiting.Store(false)
    state.mu.Unlock()
    if state.documents!=nil {state.documents.stop()}
    if state.http!=nil {state.http.stop()}
}

func (state *mcrfVerificationRun) runReplies(command *mcrfVerificationCommand) {
    for command!=nil {
        apply:=state.apply
        if command.own {apply=state.applyOwn;if apply==nil {apply=mobile.McrfReplyOwnVerificationWithFailure}} else if apply==nil {apply=mobile.McrfReplyVerificationWithFailure}
        state.mu.Lock()
        closed:=state.closed
        if closed {state.busy.Store(false)}
        state.mu.Unlock()
        if closed {command.cookies="";return}
        result,failure:=apply(command.id,command.cookies)
        command.cookies=""
        state.mu.Lock()
        closed=state.closed
        if closed {state.busy.Store(false)}
        state.mu.Unlock()
        if closed {return} // A callback already running at STOP cannot drain the next slot or emit its late result.
        if failure!=nil {state.failed.Store(failure)}
        // An obsolete failure must not stop a replacement, even if its new
        // pending announcement has not reached Android yet. No false success ACK.
        if result=="accepted" || (failure!=nil && failure.Current()) {
            if result!="accepted" {result="reply_failed"}
            data,_:=json.Marshal(map[string]string{"id":command.id,"status":result})
            line:="MCRF_OPENFLUX_REPLY "+string(data)
            if state.emit!=nil {state.emit(line)} else {fmt.Println(line)}
        }
        state.mu.Lock()
        command=state.queued;state.queued=nil
        if command==nil {state.busy.Store(false)}
        state.mu.Unlock()
    }
}

// Until a dedicated, origin-bound OpenFlux UI exists, fail explicitly instead
// of waiting silently or widening the VK browser's allowed origins/JS bridge.
// Only a finite code leaves this process; no pending page contents are retained.
func mcrfFreshProviderFailure(url, html string, own bool) string {
    if url == "" && html == "" { return "" }
    if own { return "setup_page_unsupported" }
    return "provider_verification_required"
}
