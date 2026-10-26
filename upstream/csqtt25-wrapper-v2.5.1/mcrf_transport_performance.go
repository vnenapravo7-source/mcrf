// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"fmt"
	"net"
)

// These are the ONE local IP relay socket's requested kernel buffers, not
// per-TURN-worker allocations. Android may clamp them to its kernel limits;
// never request privileged SO_*BUFFORCE or alter system-wide socket settings.
const mcrfIPRelayBufferBytes = 4 << 20
const mcrfSOCKSMaxClients = 512

func mcrfRawTransportMTU(requested int) int {
	if requested > 1280 {
		return 1280
	}
	return requested
}

func mcrfConfigureIPRelaySocket(socket *net.UDPConn) error {
	var failures []error
	if err := socket.SetReadBuffer(mcrfIPRelayBufferBytes); err != nil {
		failures = append(failures, fmt.Errorf("local IP receive buffer: %w", err))
	}
	// A receive-buffer restriction must not prevent tuning the send buffer:
	// the upload path can still benefit independently on Android kernels.
	if err := socket.SetWriteBuffer(mcrfIPRelayBufferBytes); err != nil {
		failures = append(failures, fmt.Errorf("local IP send buffer: %w", err))
	}
	return errors.Join(failures...)
}

// Acquire BEFORE Accept. Excess clients wait in the listener backlog instead
// of being accepted and reset. Cancellation must also release a full queue.
func mcrfAcquireSOCKSSlot(ctx context.Context, slots chan struct{}) bool {
	if ctx.Err() != nil {
		return false
	}
	select {
	case slots <- struct{}{}:
		return true
	case <-ctx.Done():
		return false
	}
}
