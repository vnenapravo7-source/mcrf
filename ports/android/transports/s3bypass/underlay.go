package s3

import (
	"errors"
	"net"
	"os"
	"strconv"
	"syscall"
)

const underlayEnvironment = "MCRF_S3BYPASS_UNDERLAY"

var physicalNetwork uint64
var bindProcessNetwork = func(uint64) error { return errors.New("physical network binding unavailable") }
var bindSocketNetwork = func(uint64, uintptr) error { return errors.New("physical socket binding unavailable") }

// ConfigureMcrfUnderlay runs once, before the CLI constructs its HTTP clients.
func ConfigureMcrfUnderlay() error {
	value, requested := os.LookupEnv(underlayEnvironment)
	if !requested {
		return nil
	}
	handle, err := strconv.ParseUint(value, 10, 63)
	if err != nil || handle == 0 || strconv.FormatUint(handle, 10) != value {
		return errors.New("invalid physical network handle")
	}
	if err := bindProcessNetwork(handle); err != nil {
		return err
	}
	physicalNetwork = handle
	return nil
}

func underlayDialer(dialer net.Dialer) *net.Dialer {
	handle := physicalNetwork
	if handle != 0 {
		dialer.Control = func(_, _ string, raw syscall.RawConn) error {
			var bindErr error
			if err := raw.Control(func(fd uintptr) { bindErr = bindSocketNetwork(handle, fd) }); err != nil {
				return err
			}
			return bindErr
		}
	}
	return &dialer
}
