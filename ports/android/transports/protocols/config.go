// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"unicode"

	"golang.org/x/crypto/ssh"
	bridge "mcrf.local/optionalbridge"
)

var errConfig = errors.New("PROTOCOL_CONFIG_REJECTED")
var errTrust = errors.New("PROTOCOL_SSH_HOST_KEY_REQUIRED")

type Config struct {
	bridge.Config
	PhysicalNetworkHandle uint64 `json:"physical_network_handle,omitempty"`
}

// Bound and de-duplicate JSON before decoding into a struct. encoding/json's
// last-wins and case-insensitive field matching are not a private API contract.
func jsonTree(d *json.Decoder, depth int, count *int) (any, error) {
	*count++
	if depth > 32 || *count > 4096 {
		return nil, errConfig
	}
	t, e := d.Token()
	if e != nil {
		return nil, errConfig
	}
	switch v := t.(type) {
	case json.Delim:
		switch v {
		case '{':
			m := map[string]any{}
			for d.More() {
				k, e := d.Token()
				key, ok := k.(string)
				if e != nil || !ok {
					return nil, errConfig
				}
				if _, exists := m[key]; exists {
					return nil, errConfig
				}
				value, e := jsonTree(d, depth+1, count)
				if e != nil {
					return nil, e
				}
				m[key] = value
			}
			t, e = d.Token()
			if e != nil || t != json.Delim('}') {
				return nil, errConfig
			}
			return m, nil
		case '[':
			values := []any{}
			for d.More() {
				value, e := jsonTree(d, depth+1, count)
				if e != nil {
					return nil, e
				}
				values = append(values, value)
			}
			t, e = d.Token()
			if e != nil || t != json.Delim(']') {
				return nil, errConfig
			}
			return values, nil
		}
		return nil, errConfig
	default:
		return v, nil
	}
}
func exactKeys(v any, allowed ...string) bool {
	m, ok := v.(map[string]any)
	if !ok {
		return false
	}
	for key := range m {
		found := false
		for _, a := range allowed {
			if key == a {
				found = true
				break
			}
		}
		if !found {
			return false
		}
	}
	return true
}
func safeTree(v any) bool {
	switch x := v.(type) {
	case map[string]any:
		for k, v := range x {
			switch strings.ToLower(k) {
			case "detour", "bind_interface", "inet4_bind_address", "inet6_bind_address", "protect_path", "routing_mark", "netns", "certificate_path", "client_certificate_path", "client_key_path", "private_key_path", "known_hosts_path", "agent_path", "agent_socket", "command", "network_strategy", "domain_resolver":
				return false
			}
			if !safeTree(v) {
				return false
			}
		}
	case []any:
		for _, v := range x {
			if !safeTree(v) {
				return false
			}
		}
	}
	return true
}
func readConfig(r io.Reader) (Config, error) {
	var c Config
	data, e := io.ReadAll(io.LimitReader(r, 65537))
	if e != nil || len(data) > 65536 {
		return c, errConfig
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	count := 0
	tree, e := jsonTree(d, 0, &count)
	if e != nil {
		return c, e
	}
	if _, e = d.Token(); e != io.EOF {
		return c, errConfig
	}
	if !exactKeys(tree, "api", "socks", "socks_user", "socks_pass", "outbound", "server_dns", "certificate_store", "physical_network_handle") {
		return c, errConfig
	}
	m := tree.(map[string]any)
	if !safeTree(m["outbound"]) {
		return c, errConfig
	}
	if dns, exists := m["server_dns"]; exists {
		if !exactKeys(dns, "endpoint", "strategy") {
			return c, errConfig
		}
		endpoint := dns.(map[string]any)["endpoint"]
		if !exactKeys(endpoint, "type", "server", "server_port", "path", "tls") {
			return c, errConfig
		}
		if tls, exists := endpoint.(map[string]any)["tls"]; exists && !exactKeys(tls, "enabled", "server_name") {
			return c, errConfig
		}
	}
	if json.Unmarshal(data, &c) != nil {
		return c, errConfig
	}
	host, port, e := net.SplitHostPort(c.SOCKS)
	p, _ := strconv.Atoi(port)
	credential := func(s string) bool {
		if len(s) < 32 || len(s) > 64 {
			return false
		}
		for _, r := range s {
			if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f' || r == '-') {
				return false
			}
		}
		return true
	}
	if e != nil || c.API != 1 || host != "127.0.0.1" || p < 1024 || p > 65535 || !credential(c.User) || !credential(c.Password) {
		return c, errConfig
	}
	if _, e = bridge.CAStore(c.CertificateStore); e != nil {
		return c, errConfig
	}
	if c.ServerDNS != nil {
		if e = c.ServerDNS.Validate(); e != nil {
			return c, errConfig
		}
	}
	out, ok := m["outbound"].(map[string]any)
	if !ok {
		return c, errConfig
	}
	kind, _ := out["type"].(string)
	switch kind {
	case "tuic", "anytls", "naive", "ssh":
	default:
		return c, errConfig
	}
	server, ok := out["server"].(string)
	if !ok || strings.TrimSpace(server) == "" || strings.IndexFunc(server, unicode.IsControl) >= 0 {
		return c, errConfig
	}
	if net.ParseIP(server) == nil && c.ServerDNS == nil {
		return c, errors.New("PROTOCOL_SERVER_DNS_REQUIRED")
	}
	if kind == "ssh" {
		var keys []string
		switch v := out["host_key"].(type) {
		case string:
			keys = []string{v}
		case []any:
			for _, key := range v {
				s, ok := key.(string)
				if !ok {
					return c, errTrust
				}
				keys = append(keys, s)
			}
		}
		if len(keys) == 0 {
			return c, errTrust
		}
		for _, key := range keys {
			if strings.IndexFunc(key, unicode.IsControl) >= 0 {
				return c, errTrust
			}
			_, _, _, rest, e := ssh.ParseAuthorizedKey([]byte(key))
			if e != nil || len(bytes.TrimSpace(rest)) != 0 {
				return c, errTrust
			}
		}
	}
	return c, nil
}
