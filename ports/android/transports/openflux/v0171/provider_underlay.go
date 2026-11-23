package netbind

import (
	"errors"
	"net"
	"os"
	"strconv"
	"syscall"
)

const mcrfUnderlayEnv = "MCRF_OPENFLUX_UNDERLAY"

var mcrfBindProcess = func(uint64) error { return errors.New("physical network binding unavailable") }
var mcrfBindSocket = func(uint64, uintptr) error { return errors.New("physical network binding unavailable") }

// Called once by the private executable before any provider or resolver starts.
func McrfConfigurePhysicalNetwork() error {
	value, requested := os.LookupEnv(mcrfUnderlayEnv)
	if !requested {
		return nil
	}
	handle, err := strconv.ParseUint(value, 10, 63)
	if err != nil || handle == 0 || strconv.FormatUint(handle, 10) != value {
		return errors.New("invalid OpenFlux physical network")
	}
	if err := mcrfBindProcess(handle); err != nil {
		return err
	}
	// Keep Android's system resolver after setting the process network. All
	// provider dialers and default HTTP/WebSocket sockets use the same handle.
	resolver := net.DefaultResolver
	install(func(_, address string, raw syscall.RawConn) error {
		host, _, err := net.SplitHostPort(address)
		if err != nil {
			return err
		}
		if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
			return nil
		}
		var bindError error
		if err := raw.Control(func(fd uintptr) { bindError = mcrfBindSocket(handle, fd) }); err != nil {
			return err
		}
		return bindError
	})
	net.DefaultResolver = resolver
	return nil
}
