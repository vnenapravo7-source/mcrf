// SPDX-License-Identifier: GPL-3.0-or-later
package bridge

import (
	"errors"
	"net"
	"syscall"
)

var errPhysicalNetwork = errors.New("optional core physical network binding failed")

type PhysicalNetwork uint64

const PhysicalNetworkCapability = "android-child-physical-network-v1"

func (n PhysicalNetwork) BindProcess() error {
	if n == 0 {
		return nil
	}
	return bindProcessNetwork(n)
}

func (n PhysicalNetwork) BindSocket(fd int) error {
	if n == 0 {
		return nil
	}
	return bindSocketNetwork(n, fd)
}

func (n PhysicalNetwork) Control(_, _ string, raw syscall.RawConn) error {
	var bindErr error
	if err := raw.Control(func(fd uintptr) { bindErr = n.BindSocket(int(fd)) }); err != nil {
		return err
	}
	return bindErr
}

func (n PhysicalNetwork) Dialer() *net.Dialer {
	if n == 0 {
		return &net.Dialer{}
	}
	return &net.Dialer{Control: n.Control}
}
