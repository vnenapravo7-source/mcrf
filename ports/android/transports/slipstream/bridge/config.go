// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
)

type config struct {
	Domain         string  `json:"domain"`
	Resolver       string  `json:"resolver"`
	Certificate    string  `json:"certificate"`
	KeepAliveMS    *uint16 `json:"keep_alive_ms"`
	SOCKS          string  `json:"socks"`
	User           string  `json:"socks_user"`
	Password       string  `json:"socks_pass"`
	RemoteUser     string  `json:"username"`
	RemotePassword string  `json:"password"`
}

func readConfig(r io.Reader) (config, error) {
	var c config
	data, err := io.ReadAll(io.LimitReader(r, 65537))
	if err != nil || len(data) > 65536 {
		return c, errors.New("CONFIG_SIZE")
	}
	shape := json.NewDecoder(bytes.NewReader(data))
	start, err := shape.Token()
	if err != nil || start != json.Delim('{') {
		return c, errors.New("CONFIG_OBJECT")
	}
	seen := map[string]bool{}
	for shape.More() {
		t, e := shape.Token()
		key, ok := t.(string)
		if e != nil || !ok || seen[key] {
			return c, errors.New("CONFIG_DUPLICATE")
		}
		seen[key] = true
		var v json.RawMessage
		if shape.Decode(&v) != nil || bytes.Equal(bytes.TrimSpace(v), []byte("null")) {
			return c, errors.New("CONFIG_VALUE")
		}
	}
	if _, err = shape.Token(); err != nil {
		return c, errors.New("CONFIG_OBJECT")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&c) != nil {
		return config{}, errors.New("CONFIG_FORMAT")
	}
	var trailing any
	if decoder.Decode(&trailing) != io.EOF {
		return config{}, errors.New("CONFIG_TRAILING")
	}
	if len(c.Domain) < 3 || len(c.Domain) > 100 || !strings.Contains(c.Domain, ".") {
		return c, errors.New("CONFIG_DOMAIN")
	}
	for _, label := range strings.Split(c.Domain, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return c, errors.New("CONFIG_DOMAIN")
		}
		for _, ch := range label {
			if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-') {
				return c, errors.New("CONFIG_DOMAIN")
			}
		}
	}
	validEndpoint := func(address string, local bool) bool {
		h, p, e := net.SplitHostPort(address)
		n, pe := strconv.Atoi(p)
		return e == nil && pe == nil && n > 0 && n <= 65535 && net.ParseIP(h) != nil && (!local || h == "127.0.0.1")
	}
	if !validEndpoint(c.Resolver, false) || !validEndpoint(c.SOCKS, true) {
		return c, errors.New("CONFIG_ENDPOINT")
	}
	block, rest := pem.Decode([]byte(c.Certificate))
	if block == nil || block.Type != "CERTIFICATE" || len(block.Headers) != 0 || len(bytes.TrimSpace(rest)) != 0 {
		return c, errors.New("CERTIFICATE_FORMAT")
	}
	if _, err = x509.ParseCertificate(block.Bytes); err != nil {
		return c, errors.New("CERTIFICATE_FORMAT")
	}
	if c.KeepAliveMS == nil {
		n := uint16(1000)
		c.KeepAliveMS = &n
	}
	if *c.KeepAliveMS != 0 && (*c.KeepAliveMS < 400 || *c.KeepAliveMS > 60000) {
		return c, errors.New("CONFIG_KEEPALIVE")
	}
	if _, err = McrfSOCKS(c.User, c.Password, c.RemoteUser, c.RemotePassword); err != nil {
		return c, errors.New("CONFIG_CREDENTIALS")
	}
	return c, nil
}
