package main

import (
	"errors"
	"fmt"
	"net"
	"os"
)

// The original listener is created before Noise, but does not Accept until
// Noise has authenticated the remote peer. Keep that ordering. A dead smux
// session must also retire its listener, instead of accepting broken streams
// forever while the app still sees a live child process.
func mobileWatchSession(listener net.Listener, closed <-chan struct{}) func() {
	stopped := make(chan struct{})
	go func() {
		select {
		case <-closed:
			mobileReport("DNSTT_SESSION_CLOSED")
			listener.Close()
		case <-stopped:
		}
	}()
	return func() { close(stopped) }
}

func mobileHandshakeFailure(err error) {
	var network net.Error
	if errors.As(err, &network) && network.Timeout() {
		mobileReport("DNSTT_HANDSHAKE_TIMEOUT")
	} else {
		mobileReport("DNSTT_NOISE_HANDSHAKE_FAILED")
	}
}

func mobileReport(code string) {
	// Never forward raw upstream errors, addresses, keys or authentication data.
	switch code {
	case "DNSTT_SESSION_CLOSED", "DNSTT_HANDSHAKE_TIMEOUT", "DNSTT_NOISE_HANDSHAKE_FAILED", "DNSTT_CONFIG_READ", "DNSTT_CONFIG_REJECTED":
		fmt.Fprintln(os.Stderr, "MCRF_ERROR "+code)
	}
}
