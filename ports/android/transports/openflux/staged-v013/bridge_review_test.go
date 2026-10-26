// SPDX-License-Identifier: GPL-3.0-or-later
// Pure configuration tests only: no provider, listener, account or tunnel.
package mobile

import (
    "encoding/json"
    "testing"

    "github.com/p1neappleXpress/OpenFlux/share"
)

func TestMcrfFreshShareableCarriers(t *testing.T) {
    for _, kind := range []string{"avito", "telemost"} {
        c := share.Config{Transports: []share.Transport{{Type: kind, URL: "https://docs.example/fixture"}}}
        if err := c.Validate(); err != nil { t.Fatalf("%s rejected: %v", kind, err) }
        link, err := share.MakeLink(c)
        if err != nil { t.Fatal(err) }
        parsed, err := share.Decode(link)
        if err != nil { t.Fatal(err) }
        if parsed.Transports[0].Type != kind { t.Fatal("carrier identity changed") }
    }
}

func TestMcrfFreshSessionKeepsWireIdentity(t *testing.T) {
    c := share.Config{Negotiate: true, Secret: "synthetic-key-123456", Context: "synthetic-context", Transports: []share.Transport{
        {Name: "primary", Type: "telemost", URL: "https://docs.example/one|https://docs.example/two", Priority: 100},
        {Name: "reserve", Type: "direct", Dial: "127.0.0.1:32123", Priority: 50},
    }}
    var wire struct {
        Context string `json:"context"`
        Transports []sessionSpec `json:"transports"`
    }
    if err := json.Unmarshal([]byte(sessionSpecsOf(c)), &wire); err != nil { t.Fatal(err) }
    if wire.Context != c.Context || len(wire.Transports) != 2 { t.Fatal("session identity lost") }
    for i, entry := range wire.Transports {
        expected := c.Transports[i]
        if entry.Name != expected.Name || entry.Type != expected.Type || entry.Priority != expected.Priority { t.Fatal("transport identity lost") }
    }
    if wire.Transports[0].URL != c.Transports[0].URL || wire.Transports[1].Params["dial"] != c.Transports[1].Dial { t.Fatal("endpoint changed") }
}

func TestMcrfFreshStreamAndAccountRestrictions(t *testing.T) {
    for _, kind := range []string{"avito", "telemost", "oneme"} {
        c := share.Config{Mode: share.ModeStream, Transports: []share.Transport{{Type: kind, URL: "https://docs.example/fixture"}}}
        if c.Validate() == nil { t.Fatalf("invalid stream %s admitted", kind) }
    }
}
