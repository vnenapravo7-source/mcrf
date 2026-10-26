// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"

	cronet "github.com/sagernet/cronet-go"
)

// Closed vocabulary; never relay raw SDK errors, destination/URL/user/password,
// certificate subject or HTTP response body. Unknown means unknown, not auth.
func failureCode(e error) string {
	var native cronet.NetError
	if errors.As(e, &native) && int(native) < 0 && int(native) >= -10000 {
		return fmt.Sprintf("PROTOCOL_CRONET_%d", -int(native))
	}
	var nativeGo *cronet.ErrorGo
	if errors.As(e, &nativeGo) && nativeGo.InternalErrorCode < 0 && nativeGo.InternalErrorCode >= -10000 {
		return fmt.Sprintf("PROTOCOL_CRONET_%d", -nativeGo.InternalErrorCode)
	}
	if e != nil {
		switch e.Error() {
		case "PROTOCOL_PHYSICAL_NETWORK_REQUIRED", "PROTOCOL_PHYSICAL_NETWORK_BIND_FAILED":
			return e.Error()
		}
	}
	switch {
	case errors.Is(e, errTrust):
		return "PROTOCOL_SSH_HOST_KEY_REQUIRED"
	case errors.Is(e, errConfig):
		return "PROTOCOL_CONFIG_REJECTED"
	case errors.Is(e, context.Canceled):
		return "PROTOCOL_CANCELLED"
	case errors.Is(e, context.DeadlineExceeded):
		return "PROTOCOL_TIMEOUT"
	case errors.Is(e, syscall.ECONNREFUSED):
		return "PROTOCOL_CONNECTION_REFUSED"
	case errors.Is(e, syscall.ENETUNREACH), errors.Is(e, syscall.EHOSTUNREACH):
		return "PROTOCOL_NETWORK_UNREACHABLE"
	case errors.Is(e, io.EOF), errors.Is(e, net.ErrClosed):
		return "PROTOCOL_PEER_CLOSED"
	}
	var dns *net.DNSError
	if errors.As(e, &dns) {
		if dns.IsTimeout {
			return "PROTOCOL_DNS_TIMEOUT"
		}
		return "PROTOCOL_DNS_FAILURE"
	}
	var unknown x509.UnknownAuthorityError
	var hostname x509.HostnameError
	var invalid x509.CertificateInvalidError
	if errors.As(e, &unknown) {
		return "PROTOCOL_TLS_UNKNOWN_AUTHORITY"
	}
	if errors.As(e, &hostname) {
		return "PROTOCOL_TLS_NAME_MISMATCH"
	}
	if errors.As(e, &invalid) {
		return "PROTOCOL_TLS_CERTIFICATE_INVALID"
	}
	var timeout net.Error
	if errors.As(e, &timeout) && timeout.Timeout() {
		return "PROTOCOL_TIMEOUT"
	}
	// Exact source-controlled SSH error; not a broad authentication keyword.
	if e != nil && strings.Contains(e.Error(), "host key mismatch") {
		return "PROTOCOL_SSH_HOST_KEY_MISMATCH"
	}
	if e != nil && e.Error() == "PROTOCOL_SERVER_DNS_REQUIRED" {
		return "PROTOCOL_SERVER_DNS_REQUIRED"
	}
	return "PROTOCOL_FAILURE_UNCLASSIFIED"
}

type diagnostics struct {
	mu     sync.Mutex
	last   map[string]time.Time
	output io.Writer
}

func (d *diagnostics) report(e error) {
	if e == nil || errors.Is(e, context.Canceled) || errors.Is(e, net.ErrClosed) {
		return
	}
	code := failureCode(e)
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.last == nil {
		d.last = map[string]time.Time{}
	}
	now := time.Now()
	if now.Sub(d.last[code]) < time.Second {
		return
	}
	d.last[code] = now
	fmt.Fprintln(d.output, "MCRF_ERROR "+code)
}
