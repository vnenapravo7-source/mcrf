// SPDX-License-Identifier: GPL-3.0-or-later
package bridge

import (
	"errors"
	"net"
	"strings"
)

// A resolved parent DNS tag, not a complete arbitrary native configuration.
// The endpoint is physical, literal and single-hop. Unknown fields are rejected
// recursively by Read's decoder; no website DNS rules cross this boundary.
type ServerDNS struct {
	Endpoint ServerDNSEndpoint `json:"endpoint"`
	Strategy string            `json:"strategy"`
}
type ServerDNSEndpoint struct {
	Type   string        `json:"type"`
	Server string        `json:"server,omitempty"`
	Port   uint16        `json:"server_port,omitempty"`
	Path   string        `json:"path,omitempty"`
	TLS    *ServerDNSTLS `json:"tls,omitempty"`
}
type ServerDNSTLS struct {
	Enabled *bool  `json:"enabled,omitempty"`
	Name    string `json:"server_name,omitempty"`
}

func (d *ServerDNS) Validate() error {
	bad := errors.New("unsupported server DNS context")
	switch d.Strategy {
	case "", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only":
	default:
		return bad
	}
	e := d.Endpoint
	if e.Type == "local" {
		if e.Server != "" || e.Port != 0 || e.Path != "" || e.TLS != nil {
			return bad
		}
		return nil
	}
	switch e.Type {
	case "udp", "tcp", "tls", "https":
	default:
		return bad
	}
	if net.ParseIP(e.Server) == nil {
		return bad
	}
	if e.Type != "tls" && e.Type != "https" {
		if e.TLS != nil || e.Path != "" {
			return bad
		}
	} else {
		if e.TLS != nil && (e.TLS.Enabled != nil && !*e.TLS.Enabled || strings.ContainsAny(e.TLS.Name, "/\\\x00")) {
			return bad
		}
		if e.Type == "tls" && e.Path != "" {
			return bad
		}
		if e.Path != "" && (!strings.HasPrefix(e.Path, "/") || strings.ContainsAny(e.Path, "?#\r\n")) {
			return bad
		}
	}
	return nil
}
func (e ServerDNSEndpoint) ServerPort() uint16 {
	if e.Port != 0 {
		return e.Port
	}
	switch e.Type {
	case "tls":
		return 853
	case "https":
		return 443
	default:
		return 53
	}
}
func (e ServerDNSEndpoint) TLSName() string {
	if e.TLS != nil && e.TLS.Name != "" {
		return e.TLS.Name
	}
	return e.Server
}
