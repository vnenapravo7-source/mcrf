// SPDX-License-Identifier: GPL-3.0-or-later
package transport
import "time"
// Android's 60s process deadline remains authoritative. FluxFast's unchanged
// 25s quiet-takeover plus path validation cannot fit the default 20s session.
// Apply before Start, only on clients explicitly using FluxFast. No wire,
// authentication, replay window, exit timeout or peer ownership changes.
func(s *Session) McrfAndroidFluxFastHandshake()bool{
 if s==nil{return false};s.mu.Lock();defer s.mu.Unlock()
 if s.started||s.stopped||s.exit{return false}
 s.handshakeTimeout=55*time.Second;return true
}
