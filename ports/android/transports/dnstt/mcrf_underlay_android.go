//go:build android && cgo

package main

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"

import "errors"

func dnsttBindProcess(handle uint64) error {
	if result, err := C.android_setprocnetwork(C.net_handle_t(handle)); result != 0 {
		if err != nil {
			return err
		}
		return errors.New("Android rejected the process network binding")
	}
	return nil
}

func dnsttBindSocket(handle uint64, fd uintptr) error {
	if result, err := C.android_setsocknetwork(C.net_handle_t(handle), C.int(fd)); result != 0 {
		if err != nil {
			return err
		}
		return errors.New("Android rejected the socket network binding")
	}
	return nil
}
