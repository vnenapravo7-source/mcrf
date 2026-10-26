// SPDX-License-Identifier: GPL-3.0-or-later
package script

import (
    "context"
    "net/http"
    "net/url"
    "strconv"
    "strings"
    "unicode/utf8"
)

// The SDK's own HTTP handler must stop waiting when its request is cancelled.
// This does not interrupt a JavaScript promise already running on its VM loop.
func mcrfAwaitHTTPResult[T any](ctx context.Context,done <-chan T)(T,bool) {
    var zero T
    if ctx.Err()!=nil {return zero,false}
    select {
    case value,ok:=<-done:if !ok || ctx.Err()!=nil {return zero,false};return value,true
    case <-ctx.Done():return zero,false
    }
}

// McrfOwnHTTPLease captures a concrete script-owned server handler, NOT a port
// to dial. Reusing a retired port can never redirect this lease to a new server.
// No Android/CLI entry enables it yet. The future caller still needs explicit
// user-open, exact captcha generation, private request transport and CSP/origin
// controls. Already-admitted handler calls cannot be revoked by retirement.
type McrfOwnHTTPLease struct {
    owner *ScriptTransport
    server *http.Server
    handler http.Handler
    port int
    address string
    host string
}

func (*McrfOwnHTTPLease) String() string { return "OpenFluxOwnHTTPLease(redacted)" }
// Address is private challenge material, never a log/notification/Intent extra.
func (lease *McrfOwnHTTPLease) Address() string { if lease==nil{return ""};return lease.address }

func (t *ScriptTransport) McrfCaptureOwnHTTP(raw string) *McrfOwnHTTPLease {
    if t==nil || len(raw)==0 || len(raw)>8192 || !utf8.ValidString(raw) {return nil}
    for _,ch:=range raw {if ch<0x20 || ch==0x7f || ch=='\\' {return nil}}
    u,err:=url.Parse(raw)
    if err!=nil || !strings.EqualFold(u.Scheme,"http") || u.Opaque!="" || u.User!=nil || u.Fragment!="" ||
        u.Hostname()!="127.0.0.1" {return nil}
    // Conservative first contract: actual script listener binds literal IPv4.
    // Other loopback spellings do not create DNS/IPv6/alias fallback authority.
    port,err:=strconv.Atoi(u.Port())
    if err!=nil || port<1 || port>65535 || u.Host!="127.0.0.1:"+strconv.Itoa(port) {return nil}
    u.Scheme="http"
    t.httpServersMu.Lock();defer t.httpServersMu.Unlock()
    for _,owned:=range t.httpServers {
        if owned.port==port && owned.srv!=nil && owned.srv.Handler!=nil {
            return &McrfOwnHTTPLease{owner:t,server:owned.srv,handler:owned.srv.Handler,port:port,address:u.String(),host:u.Host}
        }
    }
    return nil
}

func (lease *McrfOwnHTTPLease) Current() bool {
    if lease==nil || lease.owner==nil {return false}
    lease.owner.httpServersMu.Lock();defer lease.owner.httpServersMu.Unlock()
    for _,owned:=range lease.owner.httpServers {if owned.srv==lease.server && owned.port==lease.port {return true}}
    return false
}

func (lease *McrfOwnHTTPLease) ServeHTTP(w http.ResponseWriter,r *http.Request) {
    if lease==nil || !lease.Current() {http.Error(w,"verification retired",http.StatusGone);return}
    if r==nil || r.URL==nil || r.Host!=lease.host || r.URL.User!=nil || r.URL.Fragment!="" || r.URL.Opaque!="" ||
        (r.URL.Scheme!="" && r.URL.Scheme!="http") || (r.URL.Host!="" && r.URL.Host!=lease.host) {
        http.Error(w,"verification origin rejected",http.StatusForbidden);return
    }
    // No http.Client, proxy, DNS lookup or TCP dial, even if a handler returns
    // Location. Its exact response is handed back; no redirects are followed.
    // Do not hold ownership mutex while script/handler code is running.
    lease.handler.ServeHTTP(w,r)
}
