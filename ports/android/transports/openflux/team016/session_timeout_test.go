// SPDX-License-Identifier: GPL-3.0-or-later
package transport
import("testing";"time")
func TestMcrf016FluxFastDeadlineDoesNotWeakenPeerTakeover(t *testing.T){
 cfg:=DefaultFluxFastConfig();if cfg.QuietTakeover!=25*time.Second{t.Fatal("peer ownership grace changed")}
 s:=&Session{handshakeTimeout:20*time.Second};if !s.McrfAndroidFluxFastHandshake()||s.handshakeTimeout!=55*time.Second{t.Fatal("client deadline missing")}
 if s.handshakeTimeout<=cfg.QuietTakeover+cfg.KeepAliveInterval{t.Fatal("path validation has no budget")}
 for _,s:=range []*Session{{exit:true,handshakeTimeout:20*time.Second},{started:true,handshakeTimeout:20*time.Second},{stopped:true,handshakeTimeout:20*time.Second}}{if s.McrfAndroidFluxFastHandshake()||s.handshakeTimeout!=20*time.Second{t.Fatal("live/exit session changed")}}
}
