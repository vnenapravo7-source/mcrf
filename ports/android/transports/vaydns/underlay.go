package main

import (
	"errors"
	"net"
	"net/http"
	"os"
	"strconv"
	"syscall"
	"time"
)

const underlayEnvironment = "MCRF_VAYDNS_UNDERLAY"

var bindProcessNetwork = func(uint64) error { return errors.New("physical network unavailable") }
var bindSocketNetwork = func(uint64, uintptr) error { return errors.New("physical socket binding unavailable") }

func requestedUnderlay() (uint64, error) {
	value, requested := os.LookupEnv(underlayEnvironment)
	if !requested {
		return 0, nil
	}
	handle, err := strconv.ParseUint(value, 10, 63)
	if err != nil || handle == 0 || strconv.FormatUint(handle, 10) != value {
		return 0, errors.New("invalid physical network handle")
	}
	if err := bindProcessNetwork(handle); err != nil {
		return 0, err
	}
	return handle, nil
}

func underlayControl(handle uint64) func(string, string, syscall.RawConn) error {
	return func(_, _ string, raw syscall.RawConn) error {
		var bindErr error
		if err := raw.Control(func(fd uintptr) { bindErr = bindSocketNetwork(handle, fd) }); err != nil {
			return err
		}
		return bindErr
	}
}

func underlayHTTP(control func(string, string, syscall.RawConn) error) *http.Transport {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	dialer := &net.Dialer{Timeout: 30 * time.Second, KeepAlive: 30 * time.Second, Control: control}
	transport.DialContext = dialer.DialContext
	transport.Proxy = nil
	return transport
}
