// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"log"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"swiftsstp/tunnel"
)

func failure(err error) string {
	if err == nil {
		return "SSTP_SESSION_CLOSED"
	}
	var unknown x509.UnknownAuthorityError
	var hostname x509.HostnameError
	var invalid x509.CertificateInvalidError
	var verification *tls.CertificateVerificationError
	var network net.Error
	switch {
	case errors.As(err, &unknown):
		return "SSTP_TLS_UNKNOWN_AUTHORITY"
	case errors.As(err, &hostname):
		return "SSTP_TLS_NAME_MISMATCH"
	case errors.As(err, &invalid), errors.As(err, &verification):
		return "SSTP_TLS_CERTIFICATE"
	case errors.Is(err, context.Canceled):
		return "SSTP_CANCELLED"
	case errors.Is(err, context.DeadlineExceeded):
		return "SSTP_NETWORK_TIMEOUT"
	case errors.As(err, &network) && network.Timeout():
		return "SSTP_NETWORK_TIMEOUT"
	}
	switch err.Error() {
	case "SSTP_NETSTACK", "SSTP_LOCAL_LISTENER", "SSTP_LOCAL_ACCEPT", "SSTP_SESSION_CLOSED":
		return err.Error()
	}
	// This function accepts a terminal error, NEVER an informational log line.
	message := strings.ToLower(err.Error())
	if strings.Contains(message, "authentication") || strings.Contains(message, "ms-chap") || strings.Contains(message, "credentials") {
		return "SSTP_AUTH_REJECTED"
	}
	if strings.Contains(message, "tls") || strings.Contains(message, "certificate") {
		return "SSTP_TLS_HANDSHAKE"
	}
	if strings.Contains(message, "http handshake") {
		return "SSTP_HTTP_HANDSHAKE"
	}
	if strings.Contains(message, "callconnect") {
		return "SSTP_CONTROL_HANDSHAKE"
	}
	return "SSTP_SESSION_FAILED"
}

func run(ctx context.Context, p profile) error {
	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)
	mode := tunnel.TLSVerify
	if p.CA != "" {
		mode = tunnel.TLSCustomCA
	}
	cfg := tunnel.Config{Server: p.Server, Port: p.ServerPort, SNI: p.SNI, Username: p.Username, Password: p.Password, TLSMode: mode, CACerts: []byte(p.CA), EnableIPv6: true, KeepAlive: 30 * time.Second, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
	dialCtx, stopDial := context.WithTimeout(ctx, 30*time.Second)
	sess, result, err := tunnel.Dial(dialCtx, cfg)
	stopDial()
	if err != nil {
		return err
	}
	// STOP can cancel TLS/HTTP/PPP setup too; no signal queue waiting for Dial.
	stopSession := context.AfterFunc(ctx, func() { sess.McrfClose() })
	defer func() { stopSession(); sess.McrfClose() }()
	dns := append([]net.IP(nil), result.DNS...)
	if len(dns) == 0 {
		if result.PeerIP != nil {
			dns = append(dns, result.PeerIP)
		}
		dns = append(dns, net.ParseIP("1.1.1.1"), net.ParseIP("8.8.8.8"))
	}
	vn, err := newVirtualNet(sess, result, dns, true, result.IPv6Negotiated, func(string, ...any) {})
	if err != nil {
		return errors.New("SSTP_NETSTACK")
	}
	var closeNetOnce sync.Once
	closeNet := func() { closeNetOnce.Do(func() { sess.McrfClose(); vn.close() }) }
	defer func() { cancel(nil); closeNet() }()
	ln, err := net.Listen("tcp4", p.SOCKS)
	if err != nil {
		return errors.New("SSTP_LOCAL_LISTENER")
	}
	defer ln.Close()
	proxy := &SOCKSProxy{vn: vn, socksUser: p.SOCKSUser, socksPass: p.SOCKSPass, ctx: ctx, logf: func(string, ...any) {}}
	owned := sync.Map{}
	var workers sync.WaitGroup
	stopListener := context.AfterFunc(ctx, func() { ln.Close(); owned.Range(func(k, v any) bool { k.(net.Conn).Close(); return true }) })
	defer func() {
		cancel(nil)
		stopListener()
		ln.Close()
		owned.Range(func(k, v any) bool { k.(net.Conn).Close(); return true })
		closeNet()
		workers.Wait()
	}()
	sessionDone := make(chan error, 1)
	go func() {
		e := sess.Wait()
		if e == nil {
			e = errors.New("SSTP_SESSION_CLOSED")
		}
		sessionDone <- e
		cancel(e)
	}()
	defer func() { sess.McrfClose(); <-sessionDone }()
	fmt.Println("MCRF_READY")
	slots := make(chan struct{}, 512)
	for {
		select {
		case slots <- struct{}{}:
		case <-ctx.Done():
			return context.Cause(ctx)
		}
		conn, e := ln.Accept()
		if e != nil {
			<-slots
			if ctx.Err() != nil {
				return context.Cause(ctx)
			}
			return errors.New("SSTP_LOCAL_ACCEPT")
		}
		owned.Store(conn, true)
		if ctx.Err() != nil {
			conn.Close()
			owned.Delete(conn)
			<-slots
			return context.Cause(ctx)
		}
		workers.Add(1)
		go func() {
			defer workers.Done()
			defer func() { conn.Close(); owned.Delete(conn); <-slots }()
			proxy.handleSOCKS(conn)
		}()
	}
}

func main() {
	log.SetOutput(io.Discard)
	if len(os.Args) != 3 || os.Args[1] != "--mcrf-config" {
		fmt.Println("MCRF_ERROR SSTP_CONFIG_ARGUMENTS")
		os.Exit(2)
	}
	f, err := os.Open(os.Args[2])
	if err != nil {
		fmt.Println("MCRF_ERROR SSTP_CONFIG_READ")
		os.Exit(2)
	}
	p, err := readConfig(f)
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
	if err = run(ctx, p); err != nil && ctx.Err() == nil {
		fmt.Println("MCRF_ERROR " + failure(err))
		os.Exit(1)
	}
}
