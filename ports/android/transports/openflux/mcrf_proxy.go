// SPDX-License-Identifier: GPL-3.0-or-later
// Compiled inside the checksum-pinned OpenFlux mobile package.
package mobile

import (
	"encoding/json"
	"errors"
	"fmt"
	"github.com/p1neappleXpress/OpenFlux/share"
	"github.com/p1neappleXpress/OpenFlux/transport"
)

// share.Config omits params and rejects script. Preserve the official mobile
// session schema without losing settings on JSON -> proxy -> session conversion.
type McrfTransport struct {
	Type     string                 `json:"type"`
	Name     string                 `json:"name,omitempty"`
	URL      string                 `json:"url,omitempty"`
	Priority int                    `json:"priority,omitempty"`
	Dial     string                 `json:"dial,omitempty"`
	Params   map[string]interface{} `json:"params,omitempty"`
}
type McrfConfig struct {
	Name       string          `json:"name,omitempty"`
	Negotiate  bool            `json:"negotiate,omitempty"`
	Codec      string          `json:"codec,omitempty"`
	Secret     string          `json:"secret,omitempty"`
	Context    string          `json:"context,omitempty"`
	Mode       string          `json:"mode,omitempty"`
	Transports []McrfTransport `json:"transports"`
}

func (c McrfConfig) Validate() error {
	if len(c.Transports) < 1 || len(c.Transports) > 64 {
		return errors.New("invalid transport count")
	}
	raw, _ := json.Marshal(c)
	var compatible share.Config
	if json.Unmarshal(raw, &compatible) != nil {
		return errors.New("invalid profile")
	}
	for i, t := range c.Transports {
		if t.Type == "script" {
			if !c.Negotiate || c.Mode != "" {
				return errors.New("script requires negotiated session")
			}
			name, _ := t.Params["name"].(string)
			if !mcrfKnownScript(name) {
				return errors.New("unknown official script")
			}
			// Reuse upstream's count/secret/codec validation, not its share-only type list.
			compatible.Transports[i].Type = "yandex"
		}
		for _, key := range []string{"path", "pubkey", "exit", "is_exit", "listen"} {
			if _, ok := t.Params[key]; ok {
				return errors.New("host-owned transport parameter")
			}
		}
	}
	return compatible.Validate()
}

func mcrfSessionSpecs(c McrfConfig) (string, error) {
	specs := make([]sessionSpec, 0, len(c.Transports))
	seen := map[string]bool{}
	for _, t := range c.Transports {
		name := t.Name
		if name == "" {
			name = t.Type
			for n := 2; seen[name]; n++ {
				name = fmt.Sprintf("%s-%d", t.Type, n)
			}
		}
		if seen[name] {
			return "", errors.New("duplicate transport name")
		}
		seen[name] = true
		params := map[string]interface{}{}
		for k, v := range t.Params {
			params[k] = v
		}
		url := t.URL
		if t.Type == "direct" {
			params["dial"] = t.Dial
			url = ""
		}
		if t.Type == "script" {
			var err error
			params, err = mcrfScriptParams(params)
			if err != nil {
				return "", err
			}
		}
		specs = append(specs, sessionSpec{Name: name, Type: t.Type, URL: url, Priority: t.Priority, Params: params})
	}
	raw, err := json.Marshal(struct {
		Context    string        `json:"context"`
		Transports []sessionSpec `json:"transports"`
	}{c.Context, specs})
	return string(raw), err
}

// Keep the link's context, names, codec and strict negotiation. The core owns
// crypto and provider behavior; the outer Android core owns DNS/routes/TUN.
func McrfStartProxy(configJSON, listen, username, password string) string {
	var c McrfConfig
	if json.Unmarshal([]byte(configJSON), &c) != nil || c.Validate() != nil {
		return "invalid OpenFlux profile"
	}
	if c.Mode == share.ModeStream {
		t := c.Transports[0]
		return StartStreamProxy(t.Type, t.URL, listen, username, password, "")
	}
	if c.Secret == "" {
		t := c.Transports[0]
		return startProxyWith(func() (transport.Transport, error) {
			inner, err := newRawTransport(t.Type, t.URL, t.Params, transport.DefaultConfig(), false)
			if err != nil {
				return nil, err
			}
			attachPrompt(t.Type, t.URL, inner)
			setClassicRoute(t.Type)
			return transport.NewCodecTransport(inner, c.Codec, true), nil
		}, listen, username, password, "")
	}
	specs, err := mcrfSessionSpecs(c)
	if err != nil {
		return "official script or session configuration rejected"
	}
	return startProxyWith(func() (transport.Transport, error) {
		value, _, err := buildSessionWith(specs, c.Secret, false,
			sessionOptions{classic: !c.Negotiate, codec: c.Codec, strict: c.Negotiate})
		return value, err
	}, listen, username, password, "")
}
