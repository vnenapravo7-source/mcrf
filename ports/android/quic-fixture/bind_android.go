//go:build android && cgo

package main

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"

import "syscall"

func bindAndroidNetwork(raw syscall.RawConn, network uint64) error {
	var denied bool
	if err := raw.Control(func(fd uintptr) {
		denied = C.android_setsocknetwork(C.net_handle_t(network), C.int(fd)) != 0
	}); err != nil {
		return err
	}
	if denied {
		return errVPNBinding
	}
	return nil
}
