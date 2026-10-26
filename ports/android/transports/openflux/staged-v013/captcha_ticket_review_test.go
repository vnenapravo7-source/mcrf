// SPDX-License-Identifier: GPL-3.0-or-later
package mobile

import (
    "errors"
    "crypto/sha256"
    "encoding/hex"
    "encoding/json"
    "strings"
    "reflect"
    "testing"
    "time"

    "github.com/p1neappleXpress/OpenFlux/transport/script"
)

func TestMcrfFreshTicketOwnerRetirementRejectsReplyBeforeCallback(t *testing.T) {
    calls:=0;live:=true;mcrfTestPending(t,func(map[string]string)error{calls++;return nil})
    captcha.mu.Lock();mcrfCaptchaOwnerCurrent=func()bool{return live};captcha.mu.Unlock()
    page:=McrfPendingVerification();live=false
    if McrfReplyVerification(page.ID,"a=synthetic")!="stale_or_unsupported" || calls!=0 {t.Fatal("retired owner received reply")}
    if McrfPendingVerification()!=nil || PendingCaptchaURL()!="" {t.Fatal("retired pending page remained")}
}
func TestMcrfFreshTicketOwnerRetiredDuringApplyCannotPoisonReplacement(t *testing.T) {
    live:=true;mcrfTestPending(t,func(map[string]string)error{live=false;return errors.New("synthetic")})
    captcha.mu.Lock();mcrfCaptchaOwnerCurrent=func()bool{return live};captcha.mu.Unlock()
    page:=McrfPendingVerification();status,failure:=McrfReplyVerificationWithFailure(page.ID,"a=synthetic")
    if status!="apply_failed" || failure==nil || failure.Current() {t.Fatal("old admitted callback failure poisoned replacement owner")}
}
func TestMcrfFreshTicketOwnerRetirementCannotStampNewFailure(t *testing.T) {
    live:=true;mcrfTestPending(t,func(map[string]string)error{return nil})
    captcha.mu.Lock();mcrfCaptchaOwnerCurrent=func()bool{return live};captcha.mu.Unlock()
    old:=McrfPendingVerification();live=false
    status,failure:=McrfReplyVerificationWithFailure(old.ID,"")
    if status!="stale_or_unsupported" || failure!=nil {t.Fatal("retired owner poisoned failure")}
    McrfPendingVerification()
    captcha.mu.Lock();captcha.url="https://captcha.invalid/new";captcha.apply=func(map[string]string)error{return nil};captcha.mu.Unlock()
    current:=McrfPendingVerification();if current==nil || current.ID==old.ID {t.Fatal("replacement ticket lost")}
}
func TestMcrfFreshOwnHTTPAddressNeedsCapturedScriptServerProof(t *testing.T) {
    mcrfTestPending(t,func(map[string]string)error{return nil});captures:=0
    captcha.mu.Lock();captcha.url="http://127.0.0.1:32123/setup";captcha.own=true
    mcrfCaptchaOwnHTTP=func(string)*script.McrfOwnHTTPLease{captures++;return nil};captcha.mu.Unlock()
    page:=McrfPendingVerification()
    if page==nil || captures!=1 || McrfOwnHTTPVerificationAddress(page.ID)!="" || page.URL!="" {t.Fatal("unproven loopback page exposed")}
    McrfPendingVerification();if captures!=1 {t.Fatal("duplicate pending recaptured authority")}
}
func TestMcrfFreshOwnHTTPAddressNeverCapturesExternalInlineOrProxy(t *testing.T) {
    for _,kind:=range []string{"external","inline","proxy"} {
        mcrfTestPending(t,func(map[string]string)error{return nil});captures:=0
        captcha.mu.Lock()
        if kind=="inline" {captcha.own=true;captcha.html="<p>synthetic</p>";captcha.url=""}
        if kind=="proxy" {captcha.own=true;captcha.url="http://127.0.0.1:32123/";captcha.proxy="127.0.0.1:32124"}
        mcrfCaptchaOwnHTTP=func(string)*script.McrfOwnHTTPLease{captures++;return nil};captcha.mu.Unlock()
        page:=McrfPendingVerification()
        if page==nil || captures!=0 || McrfOwnHTTPVerificationAddress(page.ID)!="" {t.Fatal("wrong kind captured HTTP authority")}
        captcha.mu.Lock();mcrfClearVerification();captcha.mu.Unlock()
    }
}

func mcrfTestPending(t *testing.T, apply func(map[string]string) error) {
    t.Helper()
    captcha.mu.Lock()
    mcrfCaptchaGeneration++
    captcha.url, captcha.html, captcha.proxy = "https://captcha.example/check?token=synthetic", "", ""
    captcha.own = false
    captcha.apply = apply
    captcha.mu.Unlock()
    t.Cleanup(func() { captcha.mu.Lock(); mcrfClearVerification(); captcha.apply = nil; mcrfCaptchaTicket = nil; captcha.mu.Unlock() })
}

func TestMcrfFreshTicketRejectsSamePageReplacement(t *testing.T) {
    first, second := 0, 0
    mcrfTestPending(t, func(map[string]string) error { first++; return nil })
    old := McrfPendingVerification(); if old == nil { t.Fatal("no ticket") }
    captcha.mu.Lock(); mcrfCaptchaGeneration++; captcha.apply = func(map[string]string) error { second++; return nil }; captcha.mu.Unlock()
    current := McrfPendingVerification(); if current == nil || current.ID == old.ID { t.Fatal("replacement reused ticket") }
    if McrfReplyVerification(old.ID, "cookie=synthetic") != "stale_or_unsupported" { t.Fatal("old ticket admitted") }
    if first != 0 || second != 0 { t.Fatal("stale reply invoked a callback") }
    if McrfReplyVerification(current.ID, "cookie=synthetic") != "accepted" || second != 1 { t.Fatal("current callback not used") }
}

func TestMcrfFreshTicketIsSingleUseAndCancelledTicketStaysRetired(t *testing.T) {
    calls := 0
    mcrfTestPending(t, func(map[string]string) error { calls++; return nil })
    page := McrfPendingVerification(); if page == nil { t.Fatal("no ticket") }
    if !McrfCancelVerification(page.ID) || McrfCancelVerification(page.ID) { t.Fatal("cancel not scoped/one-shot") }
    if McrfReplyVerification(page.ID, "cookie=synthetic") != "stale_or_unsupported" || calls != 0 { t.Fatal("cancelled reply applied") }
}

func TestMcrfFreshTicketDoesNotExportOwnHtmlOrAcceptProxyReply(t *testing.T) {
    mcrfTestPending(t, func(map[string]string) error { t.Fatal("unsupported callback invoked"); return nil })
    captcha.mu.Lock(); mcrfCaptchaGeneration++; captcha.html = "<html>synthetic-private</html>"; captcha.own = true; captcha.mu.Unlock()
    own := McrfPendingVerification(); if own == nil || !own.Own || own.URL != "" { t.Fatal("own page exposed") }
    encoded, _ := json.Marshal(own)
    if strings.Contains(string(encoded), "synthetic") || strings.Contains(own.String(), own.ID) { t.Fatal("private page escaped") }
    if McrfReplyVerification(own.ID, "cookie=synthetic") != "stale_or_unsupported" { t.Fatal("own page accepted as cookies") }
    captcha.mu.Lock(); mcrfCaptchaGeneration++; captcha.html = ""; captcha.own = false; captcha.proxy = "127.0.0.1:12345"; captcha.mu.Unlock()
    proxied := McrfPendingVerification(); if proxied == nil { t.Fatal("no exit-side ticket") }
    if McrfReplyVerification(proxied.ID, "cookie=synthetic") != "stale_or_unsupported" { t.Fatal("exit check bypassed its proxy") }
}

func TestMcrfFreshTicketRejectsAfterDetachAndInvalidCookieHeader(t *testing.T) {
    calls := 0
    mcrfTestPending(t, func(map[string]string) error { calls++; return nil })
    page := McrfPendingVerification(); if page == nil { t.Fatal("no ticket") }
    if McrfReplyVerification(page.ID, "cookie=synthetic\r\nInjected: true") != "invalid_reply" { t.Fatal("control accepted") }
    captcha.mu.Lock(); mcrfCaptchaGeneration++; captcha.apply = nil; captcha.mu.Unlock()
    if McrfReplyVerification(page.ID, "cookie=synthetic") != "stale_or_unsupported" || calls != 0 { t.Fatal("detached callback invoked") }
}

func TestMcrfFreshTicketUpstreamCancelAndDetachInvalidate(t *testing.T) {
    calls := 0
    mcrfTestPending(t, func(map[string]string) error { calls++; return nil })
    first := McrfPendingVerification(); if first == nil { t.Fatal("no ticket") }
    CancelCaptcha() // Exercises the actual source hook, not a simulated epoch bump.
    if McrfReplyVerification(first.ID, "cookie=synthetic") != "stale_or_unsupported" { t.Fatal("upstream cancel did not invalidate ticket") }
    mcrfTestPending(t, func(map[string]string) error { calls++; return nil })
    second := McrfPendingVerification(); if second == nil { t.Fatal("no replacement ticket") }
    detachCaptcha()
    if McrfReplyVerification(second.ID, "cookie=synthetic") != "stale_or_unsupported" || calls != 0 { t.Fatal("upstream detach did not invalidate ticket") }
}

func TestMcrfFreshExpiredTicketDoesNotRenewOrDeliver(t *testing.T) {
    calls := 0
    mcrfTestPending(t, func(map[string]string) error { calls++; return nil })
    page := McrfPendingVerification(); if page == nil { t.Fatal("no ticket") }
    captcha.mu.Lock(); mcrfCaptchaTicket.expires = time.Now().Add(-time.Second); captcha.mu.Unlock()
    if McrfPendingVerification() != nil { t.Fatal("expired pending was silently renewed") }
    if McrfReplyVerification(page.ID, "cookie=synthetic") != "stale_or_unsupported" || calls != 0 { t.Fatal("expired reply applied") }
}

func TestMcrfFreshReplyFailureIsBoundToAdmittedEpoch(t *testing.T) {
    mcrfTestPending(t,func(map[string]string)error{return errors.New("synthetic-private-failure")})
    page:=McrfPendingVerification()
    status,failure:=McrfReplyVerificationWithFailure(page.ID,"cookie=synthetic")
    if status!="apply_failed" || failure==nil || !failure.Current() {t.Fatal("current failure lost")}
    mcrfTestPending(t,func(map[string]string)error{return nil})
    if failure.Current() {t.Fatal("old failure poisoned replacement")}
    current:=McrfPendingVerification()
    if current==nil || current.ID==page.ID || McrfReplyVerification(current.ID,"cookie=new")!="accepted" {t.Fatal("replacement unavailable")}
}

func TestMcrfFreshReplacementDuringOldApplyDoesNotInheritFailure(t *testing.T) {
    entered:=make(chan struct{});release:=make(chan struct{})
    released:=false
    defer func(){if !released {close(release)}}()
    mcrfTestPending(t,func(map[string]string)error{close(entered);<-release;return errors.New("synthetic-old-failure")})
    old:=McrfPendingVerification()
    result:=make(chan *McrfVerificationFailure,1)
    go func(){_,failure:=McrfReplyVerificationWithFailure(old.ID,"cookie=synthetic");result<-failure}()
    select {case <-entered:case <-time.After(time.Second):t.Fatal("old apply not entered")}
    calls:=0
    mcrfTestPending(t,func(map[string]string)error{calls++;return nil})
    current:=McrfPendingVerification()
    if current==nil || current.ID==old.ID {t.Fatal("replacement missing")}
    // Admit the current callback while the old synthetic callback still waits.
    if McrfReplyVerification(current.ID,"cookie=new")!="accepted" || calls!=1 {t.Fatal("wrong callback")}
    close(release);released=true
    select {case failure:=<-result:if failure==nil || failure.Current() {t.Fatal("late failure still current")};case <-time.After(time.Second):t.Fatal("old callback did not exit")}
}

func TestMcrfFreshUpstreamCancelAndDetachRetireFailureStamp(t *testing.T) {
    for _,retire:=range []func(){CancelCaptcha,detachCaptcha} {
        mcrfTestPending(t,func(map[string]string)error{return errors.New("synthetic-failure")})
        page:=McrfPendingVerification()
        _,failure:=McrfReplyVerificationWithFailure(page.ID,"cookie=synthetic")
        if failure==nil || !failure.Current() {t.Fatal("missing current failure")}
        retire()
        if failure.Current() {t.Fatal("retired failure remained current")}
    }
}

func mcrfTestOwnPending(t *testing.T,apply func(map[string]string)error) *McrfVerificationPage {
    t.Helper();mcrfTestPending(t,apply)
    captcha.mu.Lock();mcrfCaptchaGeneration++;captcha.own=true;captcha.url="";captcha.html="<html>synthetic own setup</html>";captcha.mu.Unlock()
    page:=McrfPendingVerification();if page==nil || !page.Own {t.Fatal("own ticket missing")};return page
}

func TestMcrfFreshOwnInlineDataUsesCapturedStructuredCallback(t *testing.T) {
    expected:=map[string]string{"name":"значение","count":"7","enabled":"true"}
    for _,payload:=range []string{
        `{"name":"значение","count":7,"enabled":true,"ignored":null,"nested":{},"array":[]}`,
        `{"client":{"name":"значение","count":7,"enabled":true},"node":{"private":"not forwarded"}}`,
    } {
        calls:=0;var received map[string]string
        page:=mcrfTestOwnPending(t,func(jar map[string]string)error{calls++;received=jar;return nil})
        status,failure:=McrfReplyOwnVerificationWithFailure(page.ID,payload)
        if status!="accepted" || failure!=nil || calls!=1 || !reflect.DeepEqual(received,expected) {t.Fatal("own structured callback changed")}
        if again,_:=McrfReplyOwnVerificationWithFailure(page.ID,payload);again!="stale_or_unsupported" || calls!=1 {t.Fatal("own data replayed")}
    }
}

func TestMcrfFreshOwnDataRejectsTrailingInvalidAndOversizedJson(t *testing.T) {
    calls:=0;page:=mcrfTestOwnPending(t,func(map[string]string)error{calls++;return nil})
    for _,payload:=range []string{"",`null`,`[]`,`{}`,`{"x":{}}`,`{"x":"ok"} {"other":"trailing"}`,
        `{"x":"ok"} trailing`,`{"x":"`+string([]byte{0xff})+`"}`,`{"x":"`+strings.Repeat("a",8192)+`"}`} {
        status,failure:=McrfReplyOwnVerificationWithFailure(page.ID,payload)
        if status!="invalid_reply" || failure==nil || !failure.Current() || calls!=0 {t.Fatal("invalid own data admitted")}
        current:=McrfPendingVerification();if current==nil || current.ID!=page.ID {t.Fatal("invalid data renewed/retired ticket")}
    }
}

func TestMcrfFreshOwnDataAndBrowserCookiesAreNotInterchangeable(t *testing.T) {
    calls:=0;own:=mcrfTestOwnPending(t,func(map[string]string)error{calls++;return nil})
    for _,header:=range []string{"cookie=synthetic","cookie=synthetic\r\nInjected: true"} {
        if status,failure:=McrfReplyVerificationWithFailure(own.ID,header);status!="stale_or_unsupported" || failure!=nil || calls!=0 {t.Fatal("wrong cookie channel poisoned own page")}
    }
    if status,_:=McrfReplyOwnVerificationWithFailure(own.ID,`{"setting":"synthetic"}`);status!="accepted" || calls!=1 {t.Fatal("own data not available after wrong channel")}
    mcrfTestPending(t,func(map[string]string)error{calls++;return nil});external:=McrfPendingVerification()
    if status,failure:=McrfReplyOwnVerificationWithFailure(external.ID,`{"setting":"synthetic"}`);status!="stale_or_unsupported" || failure!=nil || calls!=1 {t.Fatal("external page received own data")}
    if McrfReplyVerification(external.ID,"cookie=synthetic")!="accepted" || calls!=2 {t.Fatal("external cookie path changed")}
}

func TestMcrfFreshOwnDataRejectsReplacementExpiryAndProxy(t *testing.T) {
    calls:=0
    old:=mcrfTestOwnPending(t,func(map[string]string)error{calls++;return nil})
    current:=mcrfTestOwnPending(t,func(map[string]string)error{calls++;return nil})
    if status,_:=McrfReplyOwnVerificationWithFailure(old.ID,`{"x":"synthetic"}`);status!="stale_or_unsupported" || calls!=0 || current.ID==old.ID {t.Fatal("replacement admitted old own reply")}
    captcha.mu.Lock();mcrfCaptchaTicket.expires=time.Now().Add(-time.Second);captcha.mu.Unlock()
    if status,_:=McrfReplyOwnVerificationWithFailure(current.ID,`{"x":"synthetic"}`);status!="stale_or_unsupported" || calls!=0 {t.Fatal("expired own reply admitted")}
    current=mcrfTestOwnPending(t,func(map[string]string)error{calls++;return nil})
    captcha.mu.Lock();mcrfCaptchaGeneration++;captcha.proxy="127.0.0.1:12345";captcha.mu.Unlock();current=McrfPendingVerification()
    if status,_:=McrfReplyOwnVerificationWithFailure(current.ID,`{"x":"synthetic"}`);status!="stale_or_unsupported" || calls!=0 {t.Fatal("proxy own check silently changed contract")}
    captcha.mu.Lock();mcrfCaptchaGeneration++;captcha.proxy="";captcha.html="";captcha.url="http://127.0.0.1:12345/setup";captcha.mu.Unlock();current=McrfPendingVerification()
    if status,_:=McrfReplyOwnVerificationWithFailure(current.ID,`{"x":"synthetic"}`);status!="stale_or_unsupported" || calls!=0 {t.Fatal("URL-served own page silently changed contract")}
}

func TestMcrfFreshOwnDataFailureRetainsOnlyExactEpoch(t *testing.T) {
    page:=mcrfTestOwnPending(t,func(map[string]string)error{return errors.New("synthetic-private-own-failure")})
    status,failure:=McrfReplyOwnVerificationWithFailure(page.ID,`{"x":"synthetic"}`)
    if status!="apply_failed" || failure==nil || !failure.Current() {t.Fatal("own failure epoch lost")}
    mcrfTestOwnPending(t,func(map[string]string)error{return nil})
    if failure.Current() {t.Fatal("old own failure poisoned replacement")}
}

func TestMcrfFreshOwnDocumentSnapshotChunksAreBoundedAndDefensive(t *testing.T) {
    page:=mcrfTestOwnPending(t,func(map[string]string)error{return nil})
    html:=strings.Repeat("x",8191)+"я<html>synthetic-private</html>"
    captcha.mu.Lock();mcrfCaptchaGeneration++;captcha.html=html;captcha.mu.Unlock();page=McrfPendingVerification()
    meta:=McrfOwnVerificationDocument(page.ID);if meta==nil || meta.ID!=page.ID || meta.Bytes!=len(html) {t.Fatal("missing own metadata")}
    hash:=sha256.Sum256([]byte(html));if meta.SHA256!=hex.EncodeToString(hash[:]) {t.Fatal("document digest changed")}
    var assembled []byte
    for offset:=0;offset<meta.Bytes;offset+=8192 {
        chunk:=McrfReadOwnVerificationDocument(page.ID,offset)
        if len(chunk)==0 || len(chunk)>8192 {t.Fatal("unbounded/missing chunk")}
        assembled=append(assembled,chunk...);for i:=range chunk {chunk[i]=0}
        again:=McrfReadOwnVerificationDocument(page.ID,offset)
        if len(again)==0 || again[0]==0 {t.Fatal("caller modified snapshot")}
    }
    if string(assembled)!=html || strings.Contains(meta.String(),"synthetic") || strings.Contains(meta.String(),page.ID) {t.Fatal("document mixed/leaked")}
    if McrfOwnVerificationDocument(page.ID)==meta {t.Fatal("mutable metadata object shared")}
}

func TestMcrfFreshOwnDocumentInvalidOffsetsAndWrongTicketReturnNothing(t *testing.T) {
    page:=mcrfTestOwnPending(t,func(map[string]string)error{return nil})
    meta:=McrfOwnVerificationDocument(page.ID);if meta==nil {t.Fatal("metadata missing")}
    for _,offset:=range []int{-1,1,meta.Bytes,8192,1<<30} {
        if McrfReadOwnVerificationDocument(page.ID,offset)!=nil {t.Fatal("bad offset admitted")}
    }
    wrong:=strings.Repeat("f",32)
    if wrong==page.ID {wrong=strings.Repeat("e",32)}
    if McrfOwnVerificationDocument(wrong)!=nil || McrfReadOwnVerificationDocument(wrong,0)!=nil {t.Fatal("wrong ticket received document")}
    if McrfOwnVerificationDocument(page.ID)==nil {t.Fatal("bad reader renewed or retired current ticket")}
}

func TestMcrfFreshOwnDocumentRetiresWithReplacementExpiryAndReply(t *testing.T) {
    old:=mcrfTestOwnPending(t,func(map[string]string)error{return nil})
    current:=mcrfTestOwnPending(t,func(map[string]string)error{return nil})
    if McrfReadOwnVerificationDocument(old.ID,0)!=nil || McrfOwnVerificationDocument(old.ID)!=nil {t.Fatal("replacement served old document")}
    captcha.mu.Lock();mcrfCaptchaTicket.expires=time.Now().Add(-time.Second);captcha.mu.Unlock()
    if McrfOwnVerificationDocument(current.ID)!=nil || McrfReadOwnVerificationDocument(current.ID,0)!=nil {t.Fatal("expired document served")}
    current=mcrfTestOwnPending(t,func(map[string]string)error{return nil})
    if status,_:=McrfReplyOwnVerificationWithFailure(current.ID,`{"x":"synthetic"}`);status!="accepted" {t.Fatal("own reply denied")}
    if McrfOwnVerificationDocument(current.ID)!=nil || McrfReadOwnVerificationDocument(current.ID,0)!=nil {t.Fatal("submitted document still served")}
}

func TestMcrfFreshOwnDocumentMaximumAndInvalidUtf8AreRejectedBeforeExport(t *testing.T) {
    for _,html:=range []string{strings.Repeat("x",512<<10),strings.Repeat("x",(512<<10)+1),string([]byte{0xff})} {
        mcrfTestOwnPending(t,func(map[string]string)error{return nil})
        captcha.mu.Lock();mcrfCaptchaGeneration++;captcha.html=html;captcha.mu.Unlock();page:=McrfPendingVerification()
        meta:=McrfOwnVerificationDocument(page.ID)
        if len(html)==512<<10 {if meta==nil || meta.Bytes!=512<<10 {t.Fatal("exact maximum rejected")}}
        if len(html)!=512<<10 && (meta!=nil || McrfReadOwnVerificationDocument(page.ID,0)!=nil) {t.Fatal("invalid document exported")}
    }
}

func TestMcrfFreshOwnDocumentNeverServesExternalOrProxiedPage(t *testing.T) {
    mcrfTestPending(t,func(map[string]string)error{return nil});external:=McrfPendingVerification()
    if McrfOwnVerificationDocument(external.ID)!=nil {t.Fatal("external page became own document")}
    mcrfTestOwnPending(t,func(map[string]string)error{return nil})
    captcha.mu.Lock();mcrfCaptchaGeneration++;captcha.proxy="127.0.0.1:12345";captcha.mu.Unlock();proxied:=McrfPendingVerification()
    if McrfOwnVerificationDocument(proxied.ID)!=nil || McrfReadOwnVerificationDocument(proxied.ID,0)!=nil {t.Fatal("exit proxy bypassed")}
}
