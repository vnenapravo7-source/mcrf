//go:build android || linux || darwin

package main

/*
#include "decoder49.h"
*/
import "C"

import (
	utls "github.com/refraction-networking/utls"
	"unsafe"
)

var decoderInstalled49 = utls.MCRFInstallDecoder49(decompress49)

func decompress49(algorithm uint16, expected uint32, input []byte) ([]byte, error) {
	if algorithm < 1 || algorithm > 3 || expected == 0 || expected > 40960 || len(input) == 0 || len(input) > 131072 {
		return nil, errLimit
	}
	body := make([]byte, 8+len(input))
	body[0] = byte(algorithm >> 8)
	body[1] = byte(algorithm)
	body[2] = byte(expected >> 16)
	body[3] = byte(expected >> 8)
	body[4] = byte(expected)
	body[5] = byte(len(input) >> 16)
	body[6] = byte(len(input) >> 8)
	body[7] = byte(len(input))
	copy(body[8:], input)
	out := make([]byte, int(expected))
	var n C.size_t
	rc := C.mcrf_decode49(C.uint(1<<algorithm), (*C.uchar)(unsafe.Pointer(&body[0])), C.size_t(len(body)), (*C.uchar)(unsafe.Pointer(&out[0])), C.size_t(len(out)), &n)
	if rc != 0 || uint64(n) != uint64(expected) {
		return nil, errPolicy
	}
	return out, nil
}
