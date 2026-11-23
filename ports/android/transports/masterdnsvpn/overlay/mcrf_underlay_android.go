//go:build android && cgo

// SPDX-License-Identifier: GPL-3.0-or-later
package client

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"

import "errors"

func bindMasterDNSProcess(handle uint64) error {
	if result, err := C.android_setprocnetwork(C.net_handle_t(handle)); result != 0 {
		if err != nil {
			return err
		}
		return errors.New("Android rejected MasterDNS process network binding")
	}
	return nil
}

func bindMasterDNSSocket(handle uint64, fd uintptr) error {
	if result, err := C.android_setsocknetwork(C.net_handle_t(handle), C.int(fd)); result != 0 {
		if err != nil {
			return err
		}
		return errors.New("Android rejected MasterDNS socket network binding")
	}
	return nil
}
