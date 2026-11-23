package main

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"sync"
)

var rootCache49 struct {
	sync.Mutex
	keys  [][32]byte
	pools map[[32]byte]*x509.CertPool
}

// Native Steer similarly parses its root bundle once. Cache by CONTENT, not
// file path, so a changed trust bundle cannot reuse stale trust. Pools are
// immutable after publication; cap the cache instead of retaining each input.
func roots49(pem []byte) *x509.CertPool {
	key := sha256.Sum256(pem)
	rootCache49.Lock()
	defer rootCache49.Unlock()
	if p := rootCache49.pools[key]; p != nil {
		return p
	}
	p := x509.NewCertPool()
	if !p.AppendCertsFromPEM(pem) {
		return nil
	}
	if rootCache49.pools == nil {
		rootCache49.pools = make(map[[32]byte]*x509.CertPool)
	}
	if len(rootCache49.keys) == 4 {
		delete(rootCache49.pools, rootCache49.keys[0])
		rootCache49.keys = rootCache49.keys[1:]
	}
	rootCache49.keys = append(rootCache49.keys, key)
	rootCache49.pools[key] = p
	return p
}

// Exactly the current native policy: explicit insecure still verifies the TLS
// handshake signature; an exact leaf DER/SPKI pin supplies trust; an intermediate
// DER pin supplies a CA store and still requires chain + requested name.
// uTLS always checks CertificateVerify/Finished after this callback.
func certificatePolicy49(o options49, rootPEM []byte) (func([][]byte, [][]*x509.Certificate) error, error) {
	if len(rootPEM) > 1024*1024 {
		return nil, errLimit
	}
	roots := roots49(rootPEM)
	if !o.Insecure && roots == nil && len(o.CertPins) == 0 && len(o.PublicKeyPins) == 0 {
		return nil, errPolicy
	}
	pinned := func(data []byte, pins []string) bool {
		h := sha256.Sum256(data)
		want := hex.EncodeToString(h[:])
		for _, p := range pins {
			if want == p {
				return true
			}
		}
		return false
	}
	return func(raw [][]byte, _ [][]*x509.Certificate) error {
		if len(raw) == 0 || len(raw) > 16 {
			return errPolicy
		}
		total := 0
		certs := make([]*x509.Certificate, 0, len(raw))
		for _, der := range raw {
			total += len(der)
			if total > 40960 {
				return errLimit
			}
			c, e := x509.ParseCertificate(der)
			if e != nil {
				return errPolicy
			}
			certs = append(certs, c)
		}
		if o.Insecure {
			return nil
		}
		leaf := certs[0]
		if pinned(leaf.Raw, o.CertPins) || pinned(leaf.RawSubjectPublicKeyInfo, o.PublicKeyPins) {
			return nil
		}
		store := roots
		if len(o.CertPins) > 0 || len(o.PublicKeyPins) > 0 {
			store = x509.NewCertPool()
			found := false
			for _, c := range certs[1:] {
				if c.IsCA && pinned(c.Raw, o.CertPins) {
					store.AddCert(c)
					found = true
				}
			}
			if !found {
				return errPolicy
			}
		}
		if store == nil {
			return errPolicy
		} // never fall back to the host's ambient roots
		intermediates := x509.NewCertPool()
		for _, c := range certs[1:] {
			intermediates.AddCert(c)
		}
		names := o.VerifyNames
		if len(names) == 0 {
			name := o.SNI
			if name == "" {
				name = o.Host
			}
			names = []string{name}
		}
		for _, name := range names {
			if _, e := leaf.Verify(x509.VerifyOptions{Roots: store, Intermediates: intermediates, DNSName: name}); e == nil {
				return nil
			}
		}
		return errPolicy
	}, nil
}
