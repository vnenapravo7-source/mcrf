// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"slices"
	"syscall"
	"time"
)

func runSession(ctx context.Context, cPath string, check bool, output io.Writer) error {
	f, e := os.Open(cPath)
	if e != nil {
		return errConfig
	}
	c, e := readConfig(f)
	f.Close()
	if e != nil {
		return e
	}
	if !check {
		if e = bindPhysical(c.PhysicalNetworkHandle); e != nil {
			return e
		}
	}
	core, out, e := createCore(ctx, c)
	if e != nil {
		return e
	}
	defer core.Close()
	if check {
		fmt.Fprintln(output, "MCRF_VALID")
		return nil
	}
	if e = core.Start(); e != nil {
		return e
	}
	ln, e := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: mustPort(c.SOCKS)})
	if e != nil {
		return e
	}
	diag := &diagnostics{output: os.Stderr}
	return serve(ctx, ln, c.User, c.Password, out, slices.Contains(out.Network(), "udp"), diag, func() {
		if ctx.Err() == nil {
			fmt.Fprintln(output, "MCRF_READY")
		}
	})
}
func mustPort(address string) int { a, _ := net.ResolveTCPAddr("tcp4", address); return a.Port }
func own(parent context.Context, input io.Reader, runner func(context.Context) error) error {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(input, 8192))
		scanner.Buffer(make([]byte, 256), 1024)
		for scanner.Scan() {
			if scanner.Text() == "STOP" || scanner.Text() == "NETWORK_CHANGED" {
				cancel()
				return
			}
		}
		cancel()
	}()
	done := make(chan error, 1)
	go func() { done <- runner(ctx) }()
	select {
	case e := <-done:
		return e
	case <-ctx.Done():
		select {
		case <-done:
		case <-time.After(2 * time.Second):
		}
		return nil
	}
}
func run() (code int) {
	// SDK panics must not spill a profile or token into stderr. A native crash is
	// still reported honestly; it is not converted into READY or a retry loop.
	defer func() {
		if recover() != nil {
			fmt.Fprintln(os.Stderr, "MCRF_ERROR PROTOCOL_INTERNAL_PANIC")
			code = 1
		}
	}()
	requireNaiveBuild()
	log.SetOutput(io.Discard)
	if len(os.Args) == 2 && os.Args[1] == "--mcrf-describe" {
		fmt.Println(descriptor)
		return 0
	}
	if len(os.Args) != 3 || (os.Args[1] != "--mcrf-config" && os.Args[1] != "--mcrf-check") {
		fmt.Fprintln(os.Stderr, "MCRF_ERROR PROTOCOL_CONFIG_REJECTED")
		return 1
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	var e error
	if os.Args[1] == "--mcrf-check" {
		e = runSession(ctx, os.Args[2], true, os.Stdout)
	} else {
		e = own(ctx, os.Stdin, func(ctx context.Context) error { return runSession(ctx, os.Args[2], false, os.Stdout) })
	}
	if e != nil && ctx.Err() == nil {
		fmt.Fprintln(os.Stderr, "MCRF_ERROR "+failureCode(e))
		return 1
	}
	return 0
}
func main() { os.Exit(run()) }
