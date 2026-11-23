//go:build android || linux || darwin

package main

/*
#include <stdint.h>
#include <stddef.h>
#include "decoder49.h"
*/
import "C"

import (
	"golang.org/x/sys/unix"
	"io"
	"net"
	"os"
	"unsafe"
)

func main() {}

//export mcrf49_set_decoder
func mcrf49_set_decoder(decoder C.mcrf_decoder49) C.int {
	return C.int(C.mcrf_install_decoder49(decoder))
}

//export mcrf49_begin
func mcrf49_begin() C.uint64_t { return C.uint64_t(live49.begin()) }

//export mcrf49_stop
func mcrf49_stop(epoch C.uint64_t) { live49.stop(uint64(epoch)) }

//export mcrf49_validate
func mcrf49_validate(data *C.char, n C.size_t) C.int {
	if data == nil || n == 0 || n > 32768 {
		return -100
	}
	o, e := parseOptions49(C.GoBytes(unsafe.Pointer(data), C.int(n)))
	if e == nil {
		e = validateHello49(o)
	}
	return C.int(status49(e))
}

//export mcrf49_open
func mcrf49_open(fd C.int, epoch C.uint64_t, data *C.char, n C.size_t, roots *C.char, rootsN C.size_t, out *C.uint64_t) C.int {
	if out == nil {
		return -100
	}
	*out = 0
	if fd < 0 || data == nil || n == 0 || n > 32768 || rootsN > 1024*1024 || (rootsN > 0 && roots == nil) {
		return -100
	}
	o, e := parseOptions49(C.GoBytes(unsafe.Pointer(data), C.int(n)))
	if e != nil {
		return C.int(status49(e))
	}
	dup, e := unix.FcntlInt(uintptr(fd), unix.F_DUPFD_CLOEXEC, 0)
	if e != nil {
		return -109
	}
	f := os.NewFile(uintptr(dup), "steer-owned-tls")
	conn, e := net.FileConn(f)
	_ = f.Close() // FileConn owns its own duplicate.
	if e != nil {
		return -109
	}
	// FileConn shares O_NONBLOCK with the original C fd. Native TLS/direct I/O
	// integration MUST therefore use EAGAIN/poll-aware reads and writes.
	id, e := live49.open(uint64(epoch), conn, o, C.GoBytes(unsafe.Pointer(roots), C.int(rootsN)))
	if e != nil {
		return C.int(status49(e))
	}
	*out = C.uint64_t(id)
	return 0
}

//export mcrf49_read
func mcrf49_read(handle C.uint64_t, data *C.char, cap C.size_t, got *C.size_t, direct C.int) C.int {
	if got == nil {
		return -100
	}
	*got = 0
	if data == nil || cap == 0 || cap > 1024*1024 {
		return -100
	}
	s := live49.get(uint64(handle))
	if s == nil {
		return -105
	}
	dst := unsafe.Slice((*byte)(unsafe.Pointer(data)), int(cap))
	n, e := s.read(dst, direct != 0)
	*got = C.size_t(n)
	if n > 0 {
		return 0
	}
	if direct != 0 && e == io.EOF {
		return 1
	} // pending drained; native raw read now
	return C.int(status49(e))
}

//export mcrf49_write
func mcrf49_write(handle C.uint64_t, data *C.char, n C.size_t) C.int {
	if n > 1024*1024 || (n > 0 && data == nil) {
		return -100
	}
	s := live49.get(uint64(handle))
	if s == nil {
		return -105
	}
	e := s.write(unsafe.Slice((*byte)(unsafe.Pointer(data)), int(n)))
	if e != nil {
		live49.close(uint64(handle))
	}
	return C.int(status49(e))
}

//export mcrf49_pending
func mcrf49_pending(handle C.uint64_t, direct C.int) C.int {
	s := live49.get(uint64(handle))
	if s == nil {
		return 0
	}
	if s.hasData(direct != 0) {
		return 1
	}
	return 0
}

//export mcrf49_alpn
func mcrf49_alpn(handle C.uint64_t, data *C.char, cap C.size_t) C.int {
	if data == nil || cap < 1 || cap > 256 {
		return -100
	}
	s := live49.get(uint64(handle))
	if s == nil {
		return -105
	}
	p := s.alpn
	if len(p) >= int(cap) {
		return -100
	}
	dst := unsafe.Slice((*byte)(unsafe.Pointer(data)), int(cap))
	copy(dst, p)
	dst[len(p)] = 0
	return 0
}

//export mcrf49_close
func mcrf49_close(handle C.uint64_t) { live49.close(uint64(handle)) }
