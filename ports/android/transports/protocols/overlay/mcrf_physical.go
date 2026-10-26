// SPDX-License-Identifier: GPL-3.0-or-later
package dialer

import (
	"context"
	"syscall"

	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/service"
)

// Private per-owner context, not an imported profile option or mutable global.
// Go creates sockets through raw syscalls on Android: libc's process-default
// binding alone is insufficient. Every SDK TCP dial and UDP listener must bind
// its actual descriptor before connect/send, including DNS and QUIC sockets.
type McrfPhysicalControl struct{ Bind func(fd int) error }

func mcrfPhysicalControl(ctx context.Context) control.Func {
	owner := service.FromContext[*McrfPhysicalControl](ctx)
	if owner == nil {
		return nil
	}
	return func(network, address string, raw syscall.RawConn) error {
		var bindError error
		if e := raw.Control(func(fd uintptr) { bindError = owner.Bind(int(fd)) }); e != nil {
			return e
		}
		return bindError
	}
}
