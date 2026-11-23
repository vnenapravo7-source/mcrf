package main

import (
	"context"
	stdtls "crypto/tls"
	"encoding/base64"
	"strings"

	utls "github.com/refraction-networking/utls"
)

func establish49(ctx context.Context, s *session49, o options49, roots []byte) error {
	name := o.SNI
	if o.Security == "tls" && name == "" {
		name = o.Host
	}
	protocols := selectedALPN49(o)
	wire := &boundedWrite49{Conn: s.raw}
	if strings.EqualFold(o.Fingerprint, "unsafe") {
		if o.Security != "tls" {
			return errIdentity
		}
		verify, e := certificatePolicy49(o, roots)
		if e != nil {
			return e
		}
		cfg := &stdtls.Config{ServerName: name, MinVersion: stdtls.VersionTLS12, InsecureSkipVerify: true, SessionTicketsDisabled: true, NextProtos: protocols, VerifyPeerCertificate: verify}
		if o.ECH != "" {
			cfg.EncryptedClientHelloConfigList, _ = base64.StdEncoding.DecodeString(o.ECH)
			cfg.MinVersion = stdtls.VersionTLS13
			cfg.EncryptedClientHelloRejectionVerify = func(stdtls.ConnectionState) error { return errPolicy }
		}
		c := stdtls.Client(&recordBoundary49{Conn: wire}, cfg)
		s.stream = c
		if e := c.HandshakeContext(ctx); e != nil {
			return e
		}
		s.alpn = c.ConnectionState().NegotiatedProtocol
		return nil
	}
	id, e := fingerprint49(o.Fingerprint)
	if e != nil {
		return e
	}
	// No session cache is shared across profiles. PSK-capable browser templates
	// must omit an EMPTY ticket extension on a cold handshake, as uTLS's explicit
	// option requires; do not fabricate a ticket or switch to another identity.
	cfg := &utls.Config{ServerName: name, MinVersion: utls.VersionTLS12, InsecureSkipVerify: true, SessionTicketsDisabled: true, OmitEmptyPsk: true, NextProtos: protocols}
	var reality *reality49
	if o.Security == "reality" {
		if strings.EqualFold(o.Fingerprint, "hellogolang") {
			return errIdentity
		}
		cfg.MinVersion = utls.VersionTLS13
		reality = &reality49{}
		cfg.VerifyPeerCertificate = reality.verify
		defer func() {
			for i := range reality.auth {
				reality.auth[i] = 0
			}
		}()
	} else {
		cfg.VerifyPeerCertificate, e = certificatePolicy49(o, roots)
		if e != nil {
			return e
		}
		if o.ECH != "" {
			cfg.EncryptedClientHelloConfigList, _ = base64.StdEncoding.DecodeString(o.ECH)
			cfg.MinVersion = utls.VersionTLS13
			cfg.EncryptedClientHelloRejectionVerify = func(utls.ConnectionState) error { return errPolicy }
		}
	}
	c := utls.UClient(wire, cfg, id)
	s.tls = c
	s.stream = c
	if name == "" {
		if e = c.RemoveSNIExtension(); e != nil {
			return e
		}
	}
	if e = applyALPN49(c, protocols); e != nil {
		return e
	}
	if reality != nil {
		reality.conn = c
		if e = reality.prepare(o); e != nil {
			return e
		}
	}
	if e = c.HandshakeContext(ctx); e != nil {
		return e
	}
	if reality != nil && !reality.verified {
		return errReality
	}
	s.alpn = c.ConnectionState().NegotiatedProtocol
	return nil
}
