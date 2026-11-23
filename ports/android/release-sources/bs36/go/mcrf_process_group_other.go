//go:build !linux && !android

// SPDX-License-Identifier: GPL-3.0-or-later
package main

import "errors"

func mcrfCreateProcessGroup() (int,string,error) {
	return 0,"",errors.New("Android process group contract unavailable on this platform")
}
