// SPDX-License-Identifier: GPL-3.0-or-later
package mobile

import "github.com/p1neappleXpress/OpenFlux/transport"

func McrfBeforeStopObservation() map[string]bool {
    clientSession.mu.Lock(); manager := clientSession.m; clientSession.mu.Unlock()
    var session *transport.Session
    if manager != nil { session = manager.Session() }
    out := transport.McrfObservation(session)
    proxy.mu.Lock(); out["proxy_running_before_stop"] = proxy.running; proxy.mu.Unlock()
    return out
}
