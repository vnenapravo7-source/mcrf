//go:build !linux && !android

package main

import "os/exec"

func childProcessPolicy(cmd *exec.Cmd) {}
