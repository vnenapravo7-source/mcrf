//go:build android && cgo

package netbind

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"

import "errors"

func init() {
	mcrfBindProcess = func(handle uint64) error {
		if C.android_setprocnetwork(C.net_handle_t(handle)) != 0 {
			return errors.New("OpenFlux resolver could not bind to the physical network")
		}
		return nil
	}
	mcrfBindSocket = func(handle uint64, fd uintptr) error {
		if C.android_setsocknetwork(C.net_handle_t(handle), C.int(fd)) != 0 {
			return errors.New("OpenFlux socket could not bind to the physical network")
		}
		return nil
	}
}
