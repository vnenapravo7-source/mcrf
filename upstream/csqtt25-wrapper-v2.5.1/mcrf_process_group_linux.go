//go:build linux || android

// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"errors"
	"os"
	"strconv"
	"strings"
	"syscall"
)

func mcrfCreateProcessGroup() (int, string, error) {
	if err := syscall.Setpgid(0,0); err != nil { return 0,"",errors.New("private process group unavailable") }
	data, err := os.ReadFile("/proc/self/stat")
	if err != nil { return 0,"",errors.New("private process birth unavailable") }
	end := strings.LastIndex(string(data), ") ")
	if end < 0 { return 0,"",errors.New("private process identity invalid") }
	fields := strings.Fields(string(data)[end+2:])
	if len(fields)<20 || fields[2]!=strconv.Itoa(os.Getpid()) { return 0,"",errors.New("private process group invalid") }
	birth,err := strconv.ParseUint(fields[19],10,64)
	if err!=nil || birth==0 { return 0,"",errors.New("private process birth invalid") }
	return os.Getpid(),fields[19],nil
}
