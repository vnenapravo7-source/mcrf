// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"errors"
	"fmt"
	"net"
	"strconv"
	"syscall"
)

const sstpUnderlayEnv = "MCRF_SSTP_UNDERLAY"

func newSSTPUnderlay(value string, bindProcess func(uint64) error, bindSocket func(uint64, uintptr) error) (*net.Dialer, error) {
	if value == "" {
		return nil, nil
	}
	handle, err := strconv.ParseUint(value, 10, 64)
	if err != nil || handle == 0 {
		return nil, errors.New("invalid Android physical network handle")
	}
	if err := bindProcess(handle); err != nil {
		return nil, fmt.Errorf("bind SSTP resolver process: %w", err)
	}
	return &net.Dialer{Control: func(_, _ string, conn syscall.RawConn) error {
		var bindErr error
		if err := conn.Control(func(fd uintptr) { bindErr = bindSocket(handle, fd) }); err != nil {
			return err
		}
		return bindErr
	}}, nil
}
