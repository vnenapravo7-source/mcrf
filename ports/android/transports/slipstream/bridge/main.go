// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// The RFC1929 implementation is copied from the canonical MCRF VayDNS overlay
// by Prepare-Source, with only its package name changed. No SDK dependency.
type McrfBridge struct{ LocalAuth, RemoteAuth func(net.Conn) error }

var ErrMcrfRemoteProtocol = errors.New("REMOTE_SOCKS_PROTOCOL")
var ErrMcrfRemoteAuth = errors.New("REMOTE_SOCKS_AUTH")

func remote(ctx context.Context, path string, auth func(net.Conn) error) (net.Conn, error) {
	c, err := (&net.Dialer{}).DialContext(ctx, "unix", path)
	if err != nil {
		return nil, err
	}
	stop := context.AfterFunc(ctx, func() { c.Close() })
	defer stop()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	if err = auth(c); err != nil {
		c.Close()
		return nil, err
	}
	c.SetDeadline(time.Time{})
	return c, nil
}

func relay(ctx context.Context, ln net.Listener, path string, auth McrfBridge) error {
	ctx, cancel := context.WithCancelCause(ctx)
	var wg sync.WaitGroup
	owned := sync.Map{}
	stop := context.AfterFunc(ctx, func() { ln.Close(); owned.Range(func(k, v any) bool { k.(net.Conn).Close(); return true }) })
	defer func() {
		cancel(nil)
		stop()
		ln.Close()
		owned.Range(func(k, v any) bool { k.(net.Conn).Close(); return true })
		wg.Wait()
	}()
	slots := make(chan struct{}, 512)
	var outageReported atomic.Bool
	for {
		select {
		case slots <- struct{}{}:
		case <-ctx.Done():
			return context.Cause(ctx)
		}
		local, err := ln.Accept()
		if err != nil {
			<-slots
			if ctx.Err() != nil {
				return context.Cause(ctx)
			}
			return errors.New("LOCAL_ACCEPT")
		}
		owned.Store(local, true)
		if ctx.Err() != nil {
			local.Close()
			owned.Delete(local)
			<-slots
			return context.Cause(ctx)
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { local.Close(); owned.Delete(local); <-slots }()
			local.SetDeadline(time.Now().Add(10 * time.Second))
			// No QUIC stream or DNS query for an unauthenticated local app.
			if auth.LocalAuth(local) != nil {
				return
			}
			up, err := remote(ctx, path, auth.RemoteAuth)
			if err != nil {
				if errors.Is(err, ErrMcrfRemoteAuth) || errors.Is(err, ErrMcrfRemoteProtocol) {
					cancel(err)
					return
				}
				if ctx.Err() == nil && outageReported.CompareAndSwap(false, true) {
					fmt.Println("MCRF_STAGE REMOTE_STREAM_UNAVAILABLE")
				}
				return
			}
			outageReported.Store(false)
			owned.Store(up, true)
			defer func() { up.Close(); owned.Delete(up) }()
			if ctx.Err() != nil {
				return
			}
			local.SetDeadline(time.Time{})
			// Keep TCP/UDS half-close semantics; either cancellation closes both.
			done := make(chan error, 1)
			go func() {
				_, e := io.Copy(up, local)
				if w, ok := up.(interface{ CloseWrite() error }); ok {
					w.CloseWrite()
				}
				if e != nil {
					up.Close()
					local.Close()
				}
				done <- e
			}()
			_, copyErr := io.Copy(local, up)
			if w, ok := local.(interface{ CloseWrite() error }); ok {
				w.CloseWrite()
			}
			if copyErr != nil {
				local.Close()
				up.Close()
			}
			<-done
		}()
	}
}

func run(ctx context.Context, c config, backend, privateParent string) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	dir, err := os.MkdirTemp(privateParent, "slip-")
	if err != nil {
		return errors.New("PRIVATE_DIRECTORY")
	}
	// MkdirTemp mode0700 precedes socket creation. Android app-private parent.
	defer os.RemoveAll(dir)
	path := filepath.Join(dir, "relay.sock")
	if len(path) > 100 {
		return errors.New("PRIVATE_SOCKET_PATH")
	}
	cert := filepath.Join(dir, "leaf.pem")
	if os.WriteFile(cert, []byte(c.Certificate), 0600) != nil {
		return errors.New("CERTIFICATE_WRITE")
	}
	args := []string{"--domain", c.Domain, "--resolver", c.Resolver, "--cert", cert, "--keep-alive-interval", strconv.Itoa(int(*c.KeepAliveMS))}
	child := exec.CommandContext(ctx, backend, args...)
	childProcessPolicy(child)
	// No inherited key log, SIP003 overrides, proxy, tracing or credential env.
	child.Env = []string{"MCRF_SLIPSTREAM_SOCKET=" + path, "RUST_LOG=off"}
	child.Stdout = io.Discard
	stderr, err := child.StderrPipe()
	if err != nil {
		return errors.New("BACKEND_DIAGNOSTIC_PIPE")
	}
	var backendReason atomic.Pointer[string]
	diagnosticDone := make(chan struct{})
	var exitCode atomic.Int64
	exitCode.Store(-999)
	done := make(chan error, 1)
	started := make(chan error, 1)
	go func() {
		// Linux Pdeathsig follows the creating OS thread, not just its process.
		// Keep that thread alive until this one owned child has been reaped.
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		e := child.Start()
		started <- e
		if e != nil {
			return
		}
		// Drain the final safe diagnostic before Wait closes the pipe.
		<-diagnosticDone
		e = child.Wait()
		exitCode.Store(int64(child.ProcessState.ExitCode()))
		done <- e
		cancel()
	}()
	if err = <-started; err != nil {
		stderr.Close()
		return errors.New("BACKEND_START")
	}
	go func() {
		defer close(diagnosticDone)
		scanner := bufio.NewScanner(stderr)
		scanner.Buffer(make([]byte, 1024), 8192)
		for scanner.Scan() {
			line := scanner.Text()
			if line == "MCRF_BACKEND CERTIFICATE_REQUIRED" || line == "MCRF_BACKEND CERTIFICATE_MISMATCH" {
				reason := strings.TrimPrefix(line, "MCRF_BACKEND ")
				if backendReason.CompareAndSwap(nil, &reason) {
					fmt.Println("MCRF_STAGE " + reason)
					cancel()
				}
			}
		}
		// A long upstream diagnostic is never printed, but must not block child exit.
		io.Copy(io.Discard, stderr)
	}()
	defer func() { cancel(); <-done; <-diagnosticDone }()
	backendFailure := func() error {
		if reason := backendReason.Load(); reason != nil {
			return errors.New(*reason)
		}
		if code := exitCode.Load(); code != -999 {
			return fmt.Errorf("BACKEND_EXIT_%d", code)
		}
		return errors.New("BACKEND_EXIT_OR_CANCEL")
	}
	auth, err := McrfSOCKS(c.User, c.Password, c.RemoteUser, c.RemotePassword)
	if err != nil {
		return errors.New("CONFIG_CREDENTIALS")
	}
	// READY means the pinned QUIC tunnel reached its remote SOCKS endpoint,
	// not merely that a process exists or a local TCP socket has been opened.
	deadline := time.NewTimer(40 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(50 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return backendFailure()
		case <-deadline.C:
			return errors.New("TUNNEL_NOT_READY")
		case <-tick.C:
			info, e := os.Lstat(path)
			if e != nil {
				if backendReason.Load() != nil || ctx.Err() != nil {
					return backendFailure()
				}
				continue
			}
			if info.Mode()&os.ModeSocket == 0 || info.Mode().Perm() != 0600 {
				return errors.New("PRIVATE_SOCKET_PERMISSIONS")
			}
			probeCtx, stop := context.WithTimeout(ctx, 25*time.Second)
			probe, e := remote(probeCtx, path, auth.RemoteAuth)
			stop()
			if e != nil {
				if backendReason.Load() != nil || ctx.Err() != nil {
					return backendFailure()
				}
				if errors.Is(e, ErrMcrfRemoteAuth) || errors.Is(e, ErrMcrfRemoteProtocol) {
					return e
				}
				return errors.New("TUNNEL_AUTHENTICATION_OR_NETWORK")
			}
			probe.Close()
			ln, e := net.Listen("tcp4", c.SOCKS)
			if e != nil {
				return errors.New("LOCAL_LISTEN")
			}
			// Startup discovery must not leave a 20Hz timer waking the runtime
			// throughout an otherwise idle tunnel session.
			tick.Stop()
			deadline.Stop()
			fmt.Println("MCRF_READY")
			e = relay(ctx, ln, path, auth)
			if ctx.Err() != nil {
				return backendFailure()
			}
			return e
		}
	}
}

func main() {
	if len(os.Args) != 3 || os.Args[1] != "--mcrf-config" {
		fmt.Println("MCRF_ERROR CONFIG_ARGUMENTS")
		os.Exit(2)
	}
	f, err := os.Open(os.Args[2])
	if err != nil {
		fmt.Println("MCRF_ERROR CONFIG_READ")
		os.Exit(2)
	}
	c, err := readConfig(f)
	f.Close()
	if err != nil {
		fmt.Println("MCRF_ERROR " + err.Error())
		os.Exit(2)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		for scanner.Scan() {
			if strings.TrimSpace(scanner.Text()) == "STOP" {
				break
			}
		}
		cancel()
	}()
	exe, err := os.Executable()
	if err != nil {
		fmt.Println("MCRF_ERROR EXECUTABLE_PATH")
		os.Exit(2)
	}
	err = run(ctx, c, filepath.Join(filepath.Dir(exe), "libslipstream_core.so"), filepath.Dir(os.Args[2]))
	if err != nil && ctx.Err() == nil {
		fmt.Println("MCRF_ERROR " + err.Error())
		os.Exit(1)
	}
}
