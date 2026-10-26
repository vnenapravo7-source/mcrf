// SPDX-License-Identifier: GPL-3.0-or-later
package mobile

import (
    "crypto/rand"
    "crypto/sha256"
    "encoding/hex"
    "encoding/json"
    "time"
    "unicode/utf8"

    "github.com/p1neappleXpress/OpenFlux/transport/script"
)

// Guarded by captcha.mu, including the twelve pinned upstream mutation hooks.
var mcrfCaptchaGeneration uint64
var mcrfCaptchaTicket *mcrfVerificationOwner
var mcrfCaptchaOwnerCurrent func() bool
var mcrfCaptchaOwnHTTP func(string) *script.McrfOwnHTTPLease

type mcrfVerificationOwner struct {
    id string
    generation uint64
    apply func(map[string]string) error
    expires time.Time
    used bool
    document string // Private immutable snapshot, not part of the pending JSON DTO.
    documentDigest string
    ownerCurrent func() bool
    ownHTTP mcrfHTTPLease
    httpOpened,httpDocumentRead bool
    httpPermit string
    httpRun mcrfHTTPRun
}

// In-memory only. Own HTML is deliberately not returned by this contract.
type McrfVerificationPage struct {
    ID string `json:"id"`
    URL string `json:"url,omitempty"`
    Proxy string `json:"proxy,omitempty"`
    Own bool `json:"own"`
}

func (McrfVerificationPage) String() string { return "OpenFluxVerificationPage(redacted)" }

func McrfPendingVerification() *McrfVerificationPage {
    captcha.mu.Lock()
    defer captcha.mu.Unlock()
    if captcha.apply == nil || (captcha.url == "" && captcha.html == "") { return nil }
    if mcrfCaptchaOwnerCurrent!=nil && !mcrfCaptchaOwnerCurrent() {mcrfClearVerification();return nil}
    if mcrfCaptchaTicket != nil && mcrfCaptchaTicket.generation == mcrfCaptchaGeneration && time.Now().After(mcrfCaptchaTicket.expires) { return nil }
    if mcrfCaptchaTicket == nil || mcrfCaptchaTicket.generation != mcrfCaptchaGeneration || mcrfCaptchaTicket.used {
        raw := make([]byte, 16)
        if _, err := rand.Read(raw); err != nil { return nil }
        mcrfRetireVerificationTicket()
        mcrfCaptchaTicket = &mcrfVerificationOwner{id: hex.EncodeToString(raw), generation: mcrfCaptchaGeneration, apply: captcha.apply, expires: time.Now().Add(150*time.Second)}
        mcrfCaptchaTicket.ownerCurrent=mcrfCaptchaOwnerCurrent
        if captcha.own && captcha.proxy=="" && captcha.html=="" && mcrfCaptchaOwnHTTP!=nil {
            if lease:=mcrfCaptchaOwnHTTP(captcha.url);lease!=nil{mcrfCaptchaTicket.ownHTTP=mcrfHTTPLeaseAdapter{lease}}
        }
        if captcha.own && captcha.proxy=="" && len(captcha.html)>0 && len(captcha.html)<=mcrfOwnDocumentLimit && utf8.ValidString(captcha.html) {
            mcrfCaptchaTicket.document=captcha.html
        }
    }
    page := &McrfVerificationPage{ID: mcrfCaptchaTicket.id, Proxy: captcha.proxy, Own: captcha.own || captcha.html != ""}
    if !page.Own { page.URL = captcha.url }
    return page
}

// All callers already hold captcha.mu. Invalidate before releasing the lock.
func mcrfClearVerification() {
    mcrfCaptchaGeneration++
    mcrfRetireVerificationTicket()
    mcrfCaptchaOwnerCurrent=nil;mcrfCaptchaOwnHTTP=nil
    captcha.url, captcha.html, captcha.name, captcha.reason = "", "", "", ""
    captcha.own = false
    captcha.proxy, captcha.remoteName = "", ""
}

func mcrfCurrentVerification(id string) *mcrfVerificationOwner {
    ticket := mcrfCaptchaTicket
    if ticket == nil || ticket.used || ticket.id != id || ticket.generation != mcrfCaptchaGeneration || captcha.apply == nil || time.Now().After(ticket.expires) { return nil }
    if ticket.ownerCurrent!=nil && !ticket.ownerCurrent() {return nil}
    return ticket
}

// Address disclosure alone is NOT permission to open an HTTP page. Separate
// CLI source opt-in exists, but Android manual admission is not enabled yet.
func McrfOwnHTTPVerificationAddress(id string) string {
    captcha.mu.Lock();defer captcha.mu.Unlock()
    ticket:=mcrfCurrentVerification(id)
    if ticket==nil || !captcha.own || captcha.proxy!="" || captcha.html!="" || ticket.ownHTTP==nil || !ticket.ownHTTP.Current() {return ""}
    return ticket.ownHTTP.Address()
}

// Callback is captured from the exact pending generation, never re-read through
// global SubmitCaptchaCookies after unlocking. No cookie persistence or logging.
func McrfReplyVerification(id, header string) string {
    result,_ := McrfReplyVerificationWithFailure(id,header)
    return result
}

// Failure belongs to one exact epoch, not every later check in this process.
type McrfVerificationFailure struct { generation uint64;ownerCurrent func()bool }
func (failure *McrfVerificationFailure) Current() bool {
    captcha.mu.Lock(); defer captcha.mu.Unlock()
    return failure != nil && failure.generation == mcrfCaptchaGeneration && (failure.ownerCurrent==nil || failure.ownerCurrent())
}
func mcrfInvalidVerification(id string) (string,*McrfVerificationFailure) {
    captcha.mu.Lock(); defer captcha.mu.Unlock()
    ticket:=mcrfCurrentVerification(id)
    if ticket==nil { return "stale_or_unsupported",nil }
    return "invalid_reply",&McrfVerificationFailure{generation:mcrfCaptchaGeneration,ownerCurrent:ticket.ownerCurrent}
}

func McrfReplyVerificationWithFailure(id, header string) (string,*McrfVerificationFailure) {
    captcha.mu.Lock()
    supported:=mcrfCurrentVerification(id)!=nil && !captcha.own && captcha.html=="" && captcha.proxy==""
    captcha.mu.Unlock()
    if !supported {return "stale_or_unsupported",nil}
    if len(header) == 0 || len(header) > 8192 { return mcrfInvalidVerification(id) }
    for _, ch := range header { if ch < 0x20 || ch > 0x7e { return mcrfInvalidVerification(id) } }
    jar := parseCookieHeader(header)
    if len(jar) == 0 { return mcrfInvalidVerification(id) }
    return mcrfApplyVerificationJar(id,jar,false)
}

// Separate contract for a script's own INLINE setup page. Staged CLI exposes
// this only behind a second opt-in; current Android host always disables it.
// Never routed through the cookie channel or VK's WebView.
// URL-served own pages and exit-proxied checks still need separate admission.
func McrfReplyOwnVerificationWithFailure(id, payload string) (string,*McrfVerificationFailure) {
    captcha.mu.Lock()
    supported:=mcrfOwnDocumentTicket(id)!=nil
    captcha.mu.Unlock()
    if !supported {return "stale_or_unsupported",nil}
    if len(payload)==0 || len(payload)>8192 || !utf8.ValidString(payload) {return mcrfInvalidVerification(id)}
    // FlattenSubmission intentionally reads its first object only. The private
    // boundary requires exactly ONE complete object, no trailing second value.
    var root map[string]json.RawMessage
    if json.Unmarshal([]byte(payload),&root)!=nil || root==nil {return mcrfInvalidVerification(id)}
    jar,err:=script.FlattenSubmission([]byte(payload))
    if err!=nil {return mcrfInvalidVerification(id)}
    return mcrfApplyVerificationJar(id,jar,true)
}

func mcrfApplyVerificationJar(id string,jar map[string]string,ownInline bool) (string,*McrfVerificationFailure) {
    captcha.mu.Lock()
    ticket := mcrfCurrentVerification(id)
    supported:=!captcha.own && captcha.html=="" && captcha.proxy==""
    if ownInline {supported=mcrfOwnDocumentTicket(id)!=nil}
    if ticket == nil || !supported {
        captcha.mu.Unlock(); return "stale_or_unsupported",nil
    }
    ticket.used = true
    apply := ticket.apply
    mcrfClearVerification()
    failure := &McrfVerificationFailure{generation:mcrfCaptchaGeneration,ownerCurrent:ticket.ownerCurrent}
    captcha.mu.Unlock()
    // A stop may race after admission; at worst this calls the old captured
    // transport. It cannot deliver cookies to a replacement pending callback.
    if err := apply(jar); err != nil { return "apply_failed",failure }
    return "accepted",nil
}

const mcrfOwnDocumentLimit=512<<10
const mcrfOwnDocumentChunk=8192

type McrfOwnDocument struct {
    ID string `json:"id"`
    Bytes int `json:"bytes"`
    SHA256 string `json:"sha256"`
}
func (McrfOwnDocument) String() string {return "OpenFluxOwnDocument(redacted)"}

// Caller holds captcha.mu. Never resolve an own HTTP URL or use an exit proxy
// implicitly. A valid bounded inline snapshot must belong to this exact epoch.
func mcrfOwnDocumentTicket(id string) *mcrfVerificationOwner {
    ticket:=mcrfCurrentVerification(id)
    if ticket==nil || !captcha.own || captcha.html=="" || captcha.proxy!="" || ticket.document=="" {return nil}
    return ticket
}

// Separate bounded document API. Staged worker emits only after an explicit
// read behind a second host opt-in; current Android host keeps that disabled.
func McrfOwnVerificationDocument(id string) *McrfOwnDocument {
    captcha.mu.Lock();defer captcha.mu.Unlock()
    ticket:=mcrfOwnDocumentTicket(id);if ticket==nil {return nil}
    if ticket.documentDigest=="" {digest:=sha256.Sum256([]byte(ticket.document));ticket.documentDigest=hex.EncodeToString(digest[:])}
    return &McrfOwnDocument{ID:ticket.id,Bytes:len(ticket.document),SHA256:ticket.documentDigest}
}

// Opaque byte chunks may split UTF8; the future consumer must validate the
// complete digest/UTF8 BEFORE displaying anything. No reader owns ticket memory.
func McrfReadOwnVerificationDocument(id string,offset int) []byte {
    captcha.mu.Lock();defer captcha.mu.Unlock()
    ticket:=mcrfOwnDocumentTicket(id)
    if ticket==nil || offset<0 || offset>=len(ticket.document) || offset%mcrfOwnDocumentChunk!=0 {return nil}
    end:=offset+mcrfOwnDocumentChunk;if end>len(ticket.document) {end=len(ticket.document)}
    chunk:=make([]byte,end-offset);copy(chunk,ticket.document[offset:end]);return chunk
}

func McrfCancelVerification(id string) bool {
    captcha.mu.Lock()
    defer captcha.mu.Unlock()
    ticket := mcrfCurrentVerification(id)
    if ticket == nil { return false }
    ticket.used = true
    if captcha.remoteName != "" {
        if captcha.snooze == nil { captcha.snooze = make(map[string]time.Time) }
        captcha.snooze[captcha.remoteName] = time.Now().Add(remoteSnooze)
    }
    mcrfClearVerification()
    return true
}
