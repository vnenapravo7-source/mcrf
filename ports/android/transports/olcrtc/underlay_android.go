//go:build android && cgo

// SPDX-License-Identifier: GPL-3.0-or-later
package main

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"

import (
	"errors"

	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

func init() {
	bindUnderlay = func(handle uint64) error {
		if C.android_setprocnetwork(C.net_handle_t(handle)) != 0 {
			return errors.New("OLC RTC resolver could not bind to the physical network")
		}
		// The pinned SDK applies this callback to signaling, ICE and TURN
		// sockets, including unconnected UDP listeners used by Pion.
		protect.SetProtector(func(fd int) bool {
			return fd >= 0 && C.android_setsocknetwork(C.net_handle_t(handle), C.int(fd)) == 0
		})
		return nil
	}
}
