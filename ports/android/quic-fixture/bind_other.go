//go:build !android || !cgo

package main

import "syscall"

func bindAndroidNetwork(syscall.RawConn, uint64) error {
	return errUnsupportedClient
}
