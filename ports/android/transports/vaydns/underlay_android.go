//go:build android && cgo

package main

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"

import "errors"

func init() {
	bindProcessNetwork = func(handle uint64) error {
		if C.android_setprocnetwork(C.net_handle_t(handle)) != 0 {
			return errors.New("physical DNS binding failed")
		}
		return nil
	}
	bindSocketNetwork = func(handle uint64, fd uintptr) error {
		if C.android_setsocknetwork(C.net_handle_t(handle), C.int(fd)) != 0 {
			return errors.New("physical socket binding failed")
		}
		return nil
	}
}
