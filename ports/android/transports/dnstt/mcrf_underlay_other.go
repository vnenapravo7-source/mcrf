//go:build !android || !cgo

package main

import "errors"

func dnsttBindProcess(uint64) error {
	return errors.New("physical network binding requires Android")
}

func dnsttBindSocket(uint64, uintptr) error {
	return errors.New("physical network binding requires Android")
}
