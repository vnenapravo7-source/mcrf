//go:build !windows

// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"golang.org/x/sys/unix"
	"os"
)

// Kernel ownership is released even on SIGKILL; an empty persistent lock file is harmless.
func lockScriptFile(file *os.File) error {
	return unix.Flock(int(file.Fd()), unix.LOCK_EX|unix.LOCK_NB)
}
func unlockScriptFile(file *os.File) { _ = unix.Flock(int(file.Fd()), unix.LOCK_UN) }
