//go:build !android || !cgo

// SPDX-License-Identifier: GPL-3.0-or-later
package client

import "errors"

func bindMasterDNSProcess(uint64) error {
	return errors.New("Android process network binding unavailable")
}

func bindMasterDNSSocket(uint64, uintptr) error {
	return errors.New("Android socket network binding unavailable")
}
