// SPDX-License-Identifier: GPL-3.0-or-later
package manager

import (
    "testing"
    "time"
    "github.com/p1neappleXpress/OpenFlux/transport"
    "github.com/p1neappleXpress/OpenFlux/transport/control"
)

// Reuse pinned upstream's in-memory fakeTransport; no listener/provider/Session Start.
type mcrfOwnerTransport struct {fakeTransport;notify func(error,string,string,string,string)}
func (raw *mcrfOwnerTransport) SetErrorNotifier(n func(error,string,string,string,string)){raw.notify=n}
func mcrfOwnerManager(t *testing.T) (*Manager,*mcrfOwnerTransport) {
    t.Helper()
    session,err:=transport.NewSession(transport.PeerParameters{Capabilities:control.CapabilityIPv4|control.CapabilityTCP,MaxPacketSize:1500},false)
    if err!=nil {t.Fatal(err)}
    m:=New(session,nil,"synthetic-secret-long-enough","synthetic-owner")
    raw:=&mcrfOwnerTransport{};if err:=m.Add("same","synthetic",raw,1,nil);err!=nil {t.Fatal(err)}
    return m,raw
}
func TestMcrfFreshCaptchaOwnerChecksExactRawIdentity(t *testing.T) {
    m,raw:=mcrfOwnerManager(t)
    if !m.McrfRawCurrent("same",raw) || m.McrfRawCurrent("other",raw) || m.McrfRawCurrent("same",&mcrfOwnerTransport{}) {t.Fatal("identity weakened to name/type")}
}
func TestMcrfFreshCaptchaOwnerNotifierCarriesOriginalInstance(t *testing.T) {
    m,raw:=mcrfOwnerManager(t);calls,legacy:=0,0
    m.SetCaptchaNotifier(func(string,string,string,string){legacy++})
    m.McrfSetCaptchaNotifier(func(name string,source transport.Transport,url,html,reason string){if name!="same" || source!=raw {t.Fatal("origin changed")};calls++})
    raw.notify(nil,"ignored-provider-name","https://captcha.invalid/","","synthetic")
    if calls!=1 || legacy!=1 {t.Fatal("notifier flow lost")}
}
func TestMcrfFreshCaptchaOwnerRetiredNotifierCannotAnnounceReplacement(t *testing.T) {
    m,old:=mcrfOwnerManager(t);calls,legacy:=0,0
    m.SetCaptchaNotifier(func(string,string,string,string){legacy++})
    m.McrfSetCaptchaNotifier(func(string,transport.Transport,string,string,string){calls++})
    replacement:=&mcrfOwnerTransport{}
    m.mu.Lock();m.entries["same"].Raw=replacement;m.mu.Unlock() // Synthetic exact reload commit, not actual Session reload.
    old.notify(nil,"same","https://captcha.invalid/","","synthetic")
    if calls!=0 || legacy!=0 || m.McrfRawCurrent("same",old) || !m.McrfRawCurrent("same",replacement) {t.Fatal("stale instance announced")}
    m.subscribeErrors("same",replacement);replacement.notify(nil,"same","https://captcha.invalid/","","synthetic")
    if calls!=1 || legacy!=1 {t.Fatal("replacement cannot announce independently")}
}
func TestMcrfFreshCaptchaOwnerRemovedEntryCannotAnnounce(t *testing.T) {
    m,raw:=mcrfOwnerManager(t);calls:=0
    m.McrfSetCaptchaNotifier(func(string,transport.Transport,string,string,string){calls++})
    m.mu.Lock();delete(m.entries,"same");m.mu.Unlock()
    raw.notify(nil,"same","https://captcha.invalid/","","synthetic")
    if calls!=0 || m.McrfRawCurrent("same",raw) {t.Fatal("removed entry current")}
}
type mcrfOwnerValue struct {*mcrfOwnerTransport;nonComparable []byte}
func TestMcrfFreshCaptchaOwnerRejectsNilAndNonPointerIdentity(t *testing.T) {
    var missing *mcrfOwnerTransport
    if mcrfSameRaw(missing,missing) || mcrfSameRaw(nil,nil) {t.Fatal("nil current")}
    value:=mcrfOwnerValue{mcrfOwnerTransport:&mcrfOwnerTransport{},nonComparable:[]byte{1}}
    if mcrfSameRaw(value,value) {t.Fatal("value identity admitted")}
}
func TestMcrfFreshCaptchaOwnerNotifierDoesNotHoldManagerLock(t *testing.T) {
    m,raw:=mcrfOwnerManager(t);done:=make(chan struct{})
    m.McrfSetCaptchaNotifier(func(name string,source transport.Transport,_,_,_ string){
        if !m.McrfRawCurrent(name,source) {t.Error("source lost")};m.McrfSetCaptchaNotifier(nil)
    })
    go func(){defer close(done);raw.notify(nil,"same","https://captcha.invalid/","","synthetic")}()
    select {case <-done:case <-time.After(2*time.Second):t.Fatal("notifier held manager lock")}
}

type mcrfOwnerCookieRaw struct {mcrfOwnerTransport;calls int;value string}
func (raw *mcrfOwnerCookieRaw) FetchCookies()(map[string]string,error){return map[string]string{"synthetic":raw.value},nil}
func (raw *mcrfOwnerCookieRaw) ApplyCookies(jar map[string]string)error{raw.calls++;raw.value=jar["synthetic"];return nil}
func TestMcrfFreshCaptchaOwnerReloadPublishesMatchingProvider(t *testing.T) {
    m,_:=mcrfOwnerManager(t);old:=&mcrfOwnerCookieRaw{};next:=&mcrfOwnerCookieRaw{}
    m.mu.Lock();mcrfReplaceRawLocked(m.entries["same"],old);mcrfReplaceRawLocked(m.entries["same"],next);m.mu.Unlock()
    if err:=m.ApplyCookiesFor("same",map[string]string{"synthetic":"new"});err!=nil {t.Fatal(err)}
    jar,err:=m.FetchCookiesFor("same")
    if err!=nil || jar["synthetic"]!="new" || next.calls!=1 || old.calls!=0 {t.Fatal("reload retained old provider")}
}
func TestMcrfFreshCaptchaOwnerReloadDropsUnsupportedProvider(t *testing.T) {
    m,next:=mcrfOwnerManager(t);old:=&mcrfOwnerCookieRaw{}
    m.mu.Lock();mcrfReplaceRawLocked(m.entries["same"],old);mcrfReplaceRawLocked(m.entries["same"],next);m.mu.Unlock()
    if m.ApplyCookiesFor("same",map[string]string{"synthetic":"new"})==nil || old.calls!=0 {t.Fatal("unsupported replacement used old provider")}
    if _,err:=m.FetchCookiesFor("same");err==nil {t.Fatal("unsupported replacement fetched old cookies")}
}
