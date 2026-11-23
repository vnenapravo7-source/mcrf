//go:build !windows

// SPDX-License-Identifier: GPL-3.0-or-later
package mobile

import (
	"golang.org/x/sys/unix"
	"os"
)

func mcrfLockScriptFile(file *os.File) error {
	return unix.Flock(int(file.Fd()), unix.LOCK_EX|unix.LOCK_NB)
}
func mcrfUnlockScriptFile(file *os.File) { _ = unix.Flock(int(file.Fd()), unix.LOCK_UN) }
