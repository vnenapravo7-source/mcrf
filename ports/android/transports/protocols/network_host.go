//go:build !android

// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"net"
)

// Host owned fixtures have no Android network handles; never pretend a host
// setprocnetwork operation succeeded for a nonzero captured Android handle.
func bindPhysical(handle uint64) error {
	if handle != 0 {
		return errors.New("PROTOCOL_PHYSICAL_NETWORK_BIND_FAILED")
	}
	return nil
}
func boundPhysical() (uint64, error) { return 0, nil }
func bindSocket(handle uint64, fd int) error {
	if handle != 0 || fd < 0 {
		return errors.New("PROTOCOL_PHYSICAL_NETWORK_BIND_FAILED")
	}
	return nil
}
func physicalLookup(owner context.Context) func(context.Context, string, string) ([]net.IP, error) {
	return net.DefaultResolver.LookupIP
}
