// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"regexp"
	"syscall"

	"github.com/openlibrecommunity/olcrtc/internal/handshake"
)

var olcHTTPStatus = regexp.MustCompile(`(?i)\b(?:HTTP(?:/[0-9.]+)?(?:\s+status)?|status(?:\s+code)?)[:= ]+([1-5][0-9]{2})\b`)

// Preserve confirmed typed causes and actual provider HTTP status, not the
// arbitrary error body which can contain room links, tokens and credentials.
func failureCode(err error) string {
	var unknown x509.UnknownAuthorityError
	var name x509.HostnameError
	var invalid x509.CertificateInvalidError
	if errors.As(err, &unknown) {
		return "OLC_TLS_UNTRUSTED"
	}
	if errors.As(err, &name) {
		return "OLC_TLS_HOSTNAME"
	}
	if errors.As(err, &invalid) {
		return "OLC_TLS_CERTIFICATE"
	}
	switch {
	case errors.Is(err, handshake.ErrRejected):
		return "OLC_PEER_REJECTED"
	case errors.Is(err, handshake.ErrProtocolVersion):
		return "OLC_PROTOCOL_VERSION"
	case errors.Is(err, handshake.ErrChallengeMismatch):
		return "OLC_CHALLENGE_MISMATCH"
	case errors.Is(err, handshake.ErrChallengeRequired):
		return "OLC_CHALLENGE_REQUIRED"
	case errors.Is(err, handshake.ErrUnexpectedMessage), errors.Is(err, handshake.ErrFrameTooLarge):
		return "OLC_PEER_PROTOCOL"
	case errors.Is(err, syscall.ECONNREFUSED):
		return "OLC_CONNECTION_REFUSED"
	case errors.Is(err, io.EOF), errors.Is(err, io.ErrUnexpectedEOF):
		return "OLC_PEER_CLOSED"
	case errors.Is(err, context.DeadlineExceeded):
		return "OLC_NETWORK_TIMEOUT"
	}
	var dns *net.DNSError
	if errors.As(err, &dns) {
		return "OLC_DNS_FAILED"
	}
	var network net.Error
	if errors.As(err, &network) && network.Timeout() {
		return "OLC_NETWORK_TIMEOUT"
	}
	if err != nil {
		message := err.Error()
		if len(message) > 4096 {
			message = message[:4096]
		}
		if match := olcHTTPStatus.FindStringSubmatch(message); len(match) == 2 {
			return "OLC_HTTP_" + match[1]
		}
	}
	return "OLC_SESSION_FAILED"
}
