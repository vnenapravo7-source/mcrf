//go:build !android || !cgo

// SPDX-License-Identifier: GPL-3.0-or-later
package bridge

func bindProcessNetwork(PhysicalNetwork) error { return errPhysicalNetwork }

func bindSocketNetwork(PhysicalNetwork, int) error { return errPhysicalNetwork }
