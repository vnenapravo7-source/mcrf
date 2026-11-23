// Package main is an Android c-shared helper in the external Steer module only.
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"unicode/utf8"

	"github.com/cloudflare/circl/sign/mldsa/mldsa65"
	utls "github.com/refraction-networking/utls"
)

// Errors crossing JNI/C are numbers, never raw errors containing a profile,
// certificate, endpoint or key. No automatic downgrade or engine substitution.
var (
	errOptions    = errors.New("invalid TLS options")
	errIdentity   = errors.New("unsupported TLS identity")
	errALPN       = errors.New("incompatible ALPN")
	errReality    = errors.New("Reality authentication failed")
	errPolicy     = errors.New("certificate policy failed")
	errStopped    = errors.New("TLS owner stopped")
	errLimit      = errors.New("TLS resource limit")
	errTLSWrite49 = errors.New("TLS write failed")
)

type options49 struct {
	Security      string    `json:"security"`
	Host          string    `json:"host"`
	SNI           string    `json:"sni"` // empty Reality SNI is intentional, not Host fallback
	Fingerprint   string    `json:"fingerprint"`
	ALPN          *[]string `json:"alpn"` // nil: fingerprint/transport default; []: no ALPN
	RequiredALPN  string    `json:"requiredALPN"`
	PublicKey     string    `json:"publicKey"`
	ShortID       string    `json:"shortID"`
	PQVerify      string    `json:"pqVerify"`
	CertPins      []string  `json:"certPins"`      // exact SHA256 DER, lower-case hex
	PublicKeyPins []string  `json:"publicKeyPins"` // SHA256 SPKI
	VerifyNames   []string  `json:"verifyNames"`
	Insecure      bool      `json:"insecure"` // explicit user option only
	ECH           string    `json:"ech"`      // inline ECHConfigList, never a URL or file name
	TimeoutMS     int       `json:"timeoutMS"`
}

func parseOptions49(data []byte) (options49, error) {
	var o options49
	if len(data) == 0 || len(data) > 32768 || !utf8.Valid(data) {
		return o, errOptions
	}
	// encoding/json normally accepts duplicate fields (last wins). Refuse them
	// here so C and Go cannot authenticate different interpretations.
	d := json.NewDecoder(bytes.NewReader(data))
	t, err := d.Token()
	if err != nil || t != json.Delim('{') {
		return o, errOptions
	}
	seen := map[string]bool{}
	allowed := map[string]bool{"security": true, "host": true, "sni": true, "fingerprint": true, "alpn": true,
		"requiredALPN": true, "publicKey": true, "shortID": true, "pqVerify": true, "certPins": true,
		"publicKeyPins": true, "verifyNames": true, "insecure": true, "ech": true, "timeoutMS": true}
	for d.More() {
		k, e := d.Token()
		if e != nil {
			return o, errOptions
		}
		key, ok := k.(string)
		if !ok || !allowed[key] || seen[key] {
			return o, errOptions
		}
		seen[key] = true
		var value json.RawMessage
		if d.Decode(&value) != nil {
			return o, errOptions
		}
	}
	if _, err = d.Token(); err != nil {
		return o, errOptions
	}
	if _, err = d.Token(); err != io.EOF {
		return o, errOptions
	}
	d = json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if d.Decode(&o) != nil {
		return o, errOptions
	}
	if o.Security != "tls" && o.Security != "reality" {
		return o, errOptions
	}
	o.Fingerprint = strings.ToLower(o.Fingerprint)
	if o.Security == "reality" && (o.Fingerprint == "unsafe" || o.Fingerprint == "hellogolang") {
		return o, errIdentity
	}
	for _, s := range []string{o.Host, o.SNI, o.Fingerprint, o.RequiredALPN, o.ECH, o.PublicKey, o.ShortID, o.PQVerify} {
		if strings.ContainsRune(s, 0) {
			return o, errOptions
		}
	}
	if len(o.Host) == 0 || len(o.Host) > 255 || len(o.SNI) > 255 || len(o.Fingerprint) > 63 || o.TimeoutMS < 1 || o.TimeoutMS > 30000 {
		return o, errOptions
	}
	if o.RequiredALPN != "" && o.RequiredALPN != "h2" && o.RequiredALPN != "http/1.1" {
		return o, errALPN
	}
	if o.ALPN != nil {
		total := 0
		required := o.RequiredALPN == ""
		for _, p := range *o.ALPN {
			if len(p) == 0 || len(p) > 255 || strings.ContainsRune(p, 0) {
				return o, errALPN
			}
			total += len(p) + 1
			required = required || p == o.RequiredALPN
		}
		if total > 1024 || !required {
			return o, errALPN
		}
	}
	for _, pins := range [][]string{o.CertPins, o.PublicKeyPins} {
		if len(pins) > 32 {
			return o, errOptions
		}
		for _, s := range pins {
			b, e := hex.DecodeString(s)
			if e != nil || len(b) != 32 || s != strings.ToLower(s) {
				return o, errOptions
			}
		}
	}
	if len(o.VerifyNames) > 32 {
		return o, errOptions
	}
	for _, s := range o.VerifyNames {
		if len(s) == 0 || len(s) > 255 || strings.ContainsRune(s, 0) {
			return o, errOptions
		}
	}
	if o.Security == "reality" {
		pk, e := base64.RawURLEncoding.DecodeString(o.PublicKey)
		if e != nil || len(pk) != 32 {
			return o, errOptions
		}
		sid, e := hex.DecodeString(o.ShortID)
		if e != nil || len(sid) > 8 {
			return o, errOptions
		}
		if o.PQVerify != "" {
			p, e := base64.RawURLEncoding.DecodeString(o.PQVerify)
			if e != nil || len(p) != mldsa65.PublicKeySize {
				return o, errOptions
			}
		}
		if o.Insecure || len(o.CertPins) > 0 || len(o.PublicKeyPins) > 0 || len(o.VerifyNames) > 0 || o.ECH != "" {
			return o, errOptions
		}
	} else if o.PublicKey != "" || o.ShortID != "" || o.PQVerify != "" {
		return o, errOptions
	}
	if len(o.ECH) > 8192 {
		return o, errOptions
	}
	if o.ECH != "" {
		if _, e := base64.StdEncoding.DecodeString(o.ECH); e != nil {
			return o, errOptions
		}
	}
	if o.ALPN != nil && len(*o.ALPN) > 64 {
		return o, errALPN
	}
	if o.Fingerprint == "unsafe" {
		return o, nil
	}
	if _, e := fingerprint49(o.Fingerprint); e != nil {
		return o, e
	}
	return o, nil
}

// Admission probe builds the actual pinned Hello in memory, with no socket,
// so TLS12-only/Reality conflicts are diagnosed before dialing or DNS health.
func validateHello49(o options49) error {
	if strings.EqualFold(o.Fingerprint, "unsafe") {
		if o.Security == "tls" {
			return nil
		}
		return errIdentity
	}
	if o.Security == "reality" && strings.EqualFold(o.Fingerprint, "hellogolang") {
		return errIdentity
	}
	id, e := fingerprint49(o.Fingerprint)
	if e != nil {
		return e
	}
	c := utls.UClient(nil, &utls.Config{InsecureSkipVerify: true, ServerName: o.SNI, MinVersion: utls.VersionTLS12, SessionTicketsDisabled: true, OmitEmptyPsk: true, NextProtos: selectedALPN49(o)}, id)
	if e = applyALPN49(c, selectedALPN49(o)); e != nil {
		return errIdentity
	}
	if o.Security == "reality" {
		h := c.HandshakeState.Hello
		keys := c.HandshakeState.State13.KeyShareKeys
		has13 := false
		for _, v := range h.SupportedVersions {
			has13 = has13 || v == utls.VersionTLS13
		}
		if !has13 || keys == nil || (keys.Ecdhe == nil && keys.MlkemEcdhe == nil) {
			return errReality
		}
	}
	return nil
}

func fingerprint49(name string) (utls.ClientHelloID, error) {
	// Names are backend-specific. Xray's exported pinned registry is the oracle,
	// not an approximation that calls all identities Chrome. "unsafe" is handled
	// by actual standard Go TLS, not a uTLS preset or a certificate bypass.
	p := GetFingerprint(strings.ToLower(name))
	if p == nil {
		return utls.ClientHelloID{}, errIdentity
	}
	return *p, nil
}

func selectedALPN49(o options49) []string {
	if o.ALPN != nil {
		p := make([]string, len(*o.ALPN))
		copy(p, *o.ALPN)
		return p
	}
	if o.RequiredALPN != "" {
		return []string{o.RequiredALPN}
	}
	return nil
}
