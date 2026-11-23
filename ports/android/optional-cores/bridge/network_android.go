//go:build android && cgo

// SPDX-License-Identifier: GPL-3.0-or-later
package bridge

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"

func bindProcessNetwork(n PhysicalNetwork) error {
	if C.android_setprocnetwork(C.net_handle_t(n)) != 0 {
		return errPhysicalNetwork
	}
	return nil
}

func bindSocketNetwork(n PhysicalNetwork, fd int) error {
	if fd < 0 || C.android_setsocknetwork(C.net_handle_t(n), C.int(fd)) != 0 {
		return errPhysicalNetwork
	}
	return nil
}
