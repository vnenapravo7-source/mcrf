//go:build linux || android

package main

import (
	"os/exec"
	"runtime"
)

// Test peers must not survive a killed/timed-out test process. Pdeathsig follows
// the creating Linux thread, so Start and Wait share one locked owner thread.
func fixtureStart(command *exec.Cmd, drained <-chan struct{}) (<-chan error, error) {
	started := make(chan error, 1)
	done := make(chan error, 1)
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		childProcessPolicy(command)
		err := command.Start()
		started <- err
		if err == nil {
			if drained != nil {
				<-drained
			}
			err = command.Wait()
		}
		done <- err
		close(done)
	}()
	return done, <-started
}
