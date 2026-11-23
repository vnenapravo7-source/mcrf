//go:build !android || !cgo

// SPDX-License-Identifier: GPL-3.0-or-later
package main

import "errors"

func bindSSTPProcess(uint64) error {
	return errors.New("Android process network binding unavailable")
}

func bindSSTPSocket(uint64, uintptr) error {
	return errors.New("Android socket network binding unavailable")
}
