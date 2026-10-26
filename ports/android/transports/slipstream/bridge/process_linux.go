//go:build linux || android

package main

import (
	"os/exec"
	"syscall"
)

func childProcessPolicy(cmd *exec.Cmd) {
	// Also stop the backend if Android kills the wrapper without STOP/SIGTERM.
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
}
