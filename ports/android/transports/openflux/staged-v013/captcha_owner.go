// SPDX-License-Identifier: GPL-3.0-or-later
package manager

import (
    "reflect"
    "github.com/p1neappleXpress/OpenFlux/transport"
)

// Fresh mobile bridge needs the notifying instance, not a later name lookup.
type McrfCaptchaNotifier func(name string, raw transport.Transport, url, html, reason string)

// Caller holds manager.mu. Reload must publish transport/provider as one pair;
// keeping the old Provider routes a new instance's cookie operation to the old raw.
func mcrfReplaceRawLocked(entry *Entry,raw transport.Transport) {
    entry.Raw=raw;entry.Provider,_=raw.(CookieProvider)
}

func mcrfSameRaw(a,b transport.Transport) bool {
    if a==nil || b==nil {return false}
    av,bv:=reflect.ValueOf(a),reflect.ValueOf(b)
    return av.Kind()==reflect.Ptr && bv.Kind()==reflect.Ptr && !av.IsNil() && !bv.IsNil() && a==b
}
func (m *Manager) McrfRawCurrent(name string,raw transport.Transport) bool {
    if m==nil {return false}
    m.mu.RLock();defer m.mu.RUnlock()
    entry:=m.entries[name];return entry!=nil && mcrfSameRaw(entry.Raw,raw)
}
func (m *Manager) McrfSetCaptchaNotifier(n McrfCaptchaNotifier) {
    m.mu.Lock();m.mcrfCaptchaNotifier=n;m.mu.Unlock()
}
func (m *Manager) mcrfNotifyCaptcha(name string,raw transport.Transport,url,html,reason string) {
    m.mu.RLock()
    entry:=m.entries[name];current:=entry!=nil && mcrfSameRaw(entry.Raw,raw)
    n:=m.mcrfCaptchaNotifier
    m.mu.RUnlock()
    if !current {return} // Retired instance cannot announce under its replacement's name.
    m.NotifyCaptcha(name,url,html,reason) // Preserve existing session stall/exit reporting.
    if n!=nil && m.McrfRawCurrent(name,raw) {n(name,raw,url,html,reason)}
    // No manager mutex is held during either notifier. The captured callback
    // must still check Current at admission; an already admitted call may race reload.
}
