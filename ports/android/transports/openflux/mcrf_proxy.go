// SPDX-License-Identifier: GPL-3.0-or-later
// Compiled inside the checksum-pinned OpenFlux mobile package.
package mobile

import (
    "encoding/json"
    "github.com/p1neappleXpress/OpenFlux/share"
    "github.com/p1neappleXpress/OpenFlux/transport"
)

// Keep the link's context, names, codec and strict negotiation. The core owns
// crypto and provider behavior; the outer Android core owns DNS/routes/TUN.
func McrfStartProxy(configJSON, listen, username, password string) string {
    var c share.Config
    if json.Unmarshal([]byte(configJSON), &c) != nil || c.Validate() != nil {
        return "invalid OpenFlux profile"
    }
    if c.Mode == share.ModeStream {
        t := c.Transports[0]
        return StartStreamProxy(t.Type, t.URL, listen, username, password, "")
    }
    if c.Secret == "" {
        t := c.Transports[0]
        return StartProxy(t.Type, t.URL, "", c.Codec, "", "", listen, username, password, "")
    }
    return startProxyWith(func() (transport.Transport, error) {
        value, _, err := buildSessionWith(sessionSpecsOf(c), c.Secret, false,
            sessionOptions{classic: !c.Negotiate, codec: c.Codec, strict: c.Negotiate})
        return value, err
    }, listen, username, password, "")
}
