// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

type olcRunner func(context.Context, func()) error

// One owner, no unbounded shutdown wait and no periodic polling goroutine.
// Returning from main is the process-level backstop for a broken SDK/provider.
func ownSession(parent context.Context, input io.Reader, runner olcRunner, ready func()) error {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(input, 4096))
		for scanner.Scan() {
			if scanner.Text() == "STOP" {
				cancel()
				return
			}
		}
		cancel()
	}()
	done := make(chan error, 1)
	go func() {
		done <- runner(ctx, func() {
			if ctx.Err() == nil {
				ready()
			}
		})
	}()
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		select {
		case <-done:
			return nil
		case <-time.After(2 * time.Second):
			return nil
		}
	}
}

func run() int {
	fail := func(code string) int { fmt.Fprintln(os.Stderr, "MCRF_ERROR "+code); return 1 }
	if len(os.Args) != 3 || os.Args[1] != "--qthrone-config" {
		return fail("OLC_CONFIG_ARGUMENTS")
	}
	file, err := os.Open(os.Args[2])
	if err != nil {
		return fail("OLC_CONFIG_READ")
	}
	cfg, err := readConfig(file)
	file.Close()
	if err != nil {
		return fail("OLC_CONFIG_REJECTED")
	}
	log.SetOutput(io.Discard)
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer cancel()
	adapter := client.New(cfg)
	err = ownSession(ctx, os.Stdin, adapter.RunWithReady, func() { fmt.Println("MCRF_READY") })
	if err != nil && ctx.Err() == nil {
		return fail(failureCode(err))
	}
	return 0
}
func main() { os.Exit(run()) }
