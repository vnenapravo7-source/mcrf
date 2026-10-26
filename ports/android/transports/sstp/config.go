// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"crypto/x509"
	"encoding/json"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
)

type profile struct {
	Server     string `json:"server"`
	ServerPort int    `json:"server_port"`
	Username   string `json:"username"`
	Password   string `json:"password"`
	SNI        string `json:"sni"`
	CA         string `json:"ca_certificate"`
	SOCKS      string `json:"socks"`
	SOCKSUser  string `json:"socks_user"`
	SOCKSPass  string `json:"socks_pass"`
}

func readConfig(reader io.Reader) (profile, error) {
	var p profile
	data, err := io.ReadAll(io.LimitReader(reader, 65537))
	if err != nil || len(data) > 65536 {
		return p, errors.New("SSTP_CONFIG_SIZE")
	}
	shape := json.NewDecoder(bytes.NewReader(data))
	start, err := shape.Token()
	if err != nil || start != json.Delim('{') {
		return p, errors.New("SSTP_CONFIG_OBJECT")
	}
	seen := map[string]bool{}
	for shape.More() {
		t, e := shape.Token()
		name, ok := t.(string)
		if e != nil || !ok || seen[name] {
			return p, errors.New("SSTP_CONFIG_DUPLICATE")
		}
		seen[name] = true
		var value json.RawMessage
		if shape.Decode(&value) != nil || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return p, errors.New("SSTP_CONFIG_VALUE")
		}
	}
	if _, err = shape.Token(); err != nil {
		return p, errors.New("SSTP_CONFIG_OBJECT")
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if d.Decode(&p) != nil {
		return p, errors.New("SSTP_CONFIG_FORMAT")
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return p, errors.New("SSTP_CONFIG_TRAILING")
	}
	validHost := func(host string) bool {
		if ip := net.ParseIP(host); ip != nil {
			return true
		}
		if len(host) == 0 || len(host) > 253 {
			return false
		}
		for _, part := range strings.Split(host, ".") {
			if len(part) == 0 || len(part) > 63 || part[0] == '-' || part[len(part)-1] == '-' {
				return false
			}
			for _, ch := range part {
				if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-') {
					return false
				}
			}
		}
		return true
	}
	if !validHost(p.Server) || p.SNI != "" && !validHost(p.SNI) {
		return p, errors.New("SSTP_CONFIG_HOST")
	}
	if p.ServerPort == 0 && !seen["server_port"] {
		p.ServerPort = 443
	}
	if p.ServerPort < 1 || p.ServerPort > 65535 {
		return p, errors.New("SSTP_CONFIG_PORT")
	}
	if p.Username == "" || p.Password == "" || len(p.Username) > 256 || len(p.Password) > 4096 || strings.ContainsAny(p.Username, "\r\n\x00") {
		return p, errors.New("SSTP_CONFIG_CREDENTIALS")
	}
	h, port, e := net.SplitHostPort(p.SOCKS)
	n, pe := strconv.Atoi(port)
	if e != nil || pe != nil || h != "127.0.0.1" || n < 1 || n > 65535 {
		return p, errors.New("SSTP_CONFIG_LISTENER")
	}
	if len(p.SOCKSUser) == 0 || len(p.SOCKSPass) == 0 || len(p.SOCKSUser) > 255 || len(p.SOCKSPass) > 255 {
		return p, errors.New("SSTP_CONFIG_LOCAL_AUTH")
	}
	if p.CA != "" && !x509.NewCertPool().AppendCertsFromPEM([]byte(p.CA)) {
		return p, errors.New("SSTP_CONFIG_CA")
	}
	return p, nil
}
