package client

// MCRF addition to the pinned CC0 VayDNS SDK. Upstream ListenAndServe is a
// raw TCP forwarder: never use it as an Android loopback SOCKS listener.
import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/xtaci/smux"
)

// ErrMcrfRemoteAuth is terminal until an explicit new start. A bad password
// must not cause a reconnect loop or one authentication attempt per browser flow.
var ErrMcrfRemoteAuth = errors.New("remote SOCKS authentication rejected")
var ErrMcrfRemoteProtocol = errors.New("remote service is not the configured SOCKS5 backend")

const mcrfVayMaxClients = 512

func mcrfTerminal(err error) bool {
	return errors.Is(err, ErrMcrfRemoteAuth) || errors.Is(err, ErrMcrfRemoteProtocol)
}

type McrfBridge struct {
	// Both hooks are mandatory. LocalAuth runs BEFORE opening a DNS stream.
	LocalAuth  func(net.Conn) error
	RemoteAuth func(net.Conn) error
	Ready      func()
	// Diagnostic receives categories only, never upstream errors/config values.
	Diagnostic func(string)
}

func mcrfPause(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// McrfServe exclusively owns this Tunnel; callers must not also call its
// step API or Close. ln must be a private loopback listener. Cancellation
// interrupts accept/backoff immediately; SDK handshake/open are bounded by
// HandshakeTimeout/OpenStreamTimeout. The process owner retains its kill bound.
func (t *Tunnel) McrfServe(ctx context.Context, ln *net.TCPListener, bridge McrfBridge) error {
	if ln == nil {
		return errors.New("private listener required")
	}
	defer ln.Close()
	addr, ok := ln.Addr().(*net.TCPAddr)
	if !ok || !addr.IP.Equal(net.ParseIP("127.0.0.1")) || bridge.LocalAuth == nil || bridge.RemoteAuth == nil {
		return errors.New("authenticated IPv4 loopback bridge required")
	}
	t.applyDefaults()
	if t.HandshakeTimeout <= 0 || t.HandshakeTimeout > 15*time.Second {
		t.HandshakeTimeout = 15 * time.Second
	}
	if t.OpenStreamTimeout <= 0 || t.OpenStreamTimeout > 10*time.Second {
		t.OpenStreamTimeout = 10 * time.Second
	}
	mtu := DNSNameCapacity(t.TunnelServer.Addr, t.TunnelServer.effectiveMaxQnameLen(), t.TunnelServer.MaxNumLabels) - t.wireConfig.DataOverhead()
	if mtu < 25 {
		return errors.New("DNS tunnel MTU too small")
	}
	defer t.closeTransportLayers()
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			ln.Close()
		case <-done:
		}
	}()
	delay := time.Second
	for ctx.Err() == nil {
		if err := t.resetTransportLayers(); err != nil {
			if bridge.Diagnostic != nil {
				bridge.Diagnostic("resolver-reconnect")
			}
			if err = mcrfPause(ctx, delay); err != nil {
				return err
			}
			delay = mcrfNextDelay(delay)
			continue
		}
		conn, sess, err := t.createSession(mtu)
		if err != nil {
			t.closeTransportLayers()
			if bridge.Diagnostic != nil {
				bridge.Diagnostic("session-reconnect")
			}
			if err = mcrfPause(ctx, delay); err != nil {
				return err
			}
			delay = mcrfNextDelay(delay)
			continue
		}
		if ctx.Err() != nil {
			sess.Close()
			conn.Close()
			return ctx.Err()
		}
		// Authenticate one backend stream before READY, not merely the local
		// listener. No CONNECT destination or arbitrary external probe is sent.
		stream, authErr := openStreamWithTimeout(conn.GetConv(), t.OpenStreamTimeout, sess.OpenStream)
		if authErr == nil {
			authErr = mcrfRemoteAuth(stream, bridge.RemoteAuth)
			stream.Close()
		}
		if authErr != nil {
			sess.Close()
			conn.Close()
			t.closeTransportLayers()
			if mcrfTerminal(authErr) {
				return authErr
			}
			if bridge.Diagnostic != nil {
				bridge.Diagnostic("backend-reconnect")
			}
			if err = mcrfPause(ctx, delay); err != nil {
				return err
			}
			delay = mcrfNextDelay(delay)
			continue
		}
		if ctx.Err() != nil {
			sess.Close()
			conn.Close()
			return ctx.Err()
		}
		if bridge.Ready != nil {
			bridge.Ready()
		}
		sessionStarted := time.Now()
		err = t.mcrfAccept(ctx, ln, sess, conn.GetConv(), bridge)
		sess.Close()
		conn.Close()
		t.closeTransportLayers()
		if mcrfTerminal(err) {
			return err
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if errors.Is(err, net.ErrClosed) {
			return err
		}
		if bridge.Diagnostic != nil {
			bridge.Diagnostic("session-lost")
		}
		// A succession of momentarily ready sessions must not reset every
		// retry to one second and keep a failing radio busy indefinitely.
		if time.Since(sessionStarted) >= 30*time.Second {
			delay = time.Second
		}
		if err = mcrfPause(ctx, delay); err != nil {
			return err
		}
		delay = mcrfNextDelay(delay)
	}
	return ctx.Err()
}

func mcrfNextDelay(delay time.Duration) time.Duration {
	if delay >= 15*time.Second {
		return 30 * time.Second
	}
	return delay * 2
}

func mcrfRemoteAuth(stream net.Conn, auth func(net.Conn) error) error {
	if err := stream.SetDeadline(time.Now().Add(10 * time.Second)); err != nil {
		return err
	}
	err := auth(stream)
	clearErr := stream.SetDeadline(time.Time{})
	if err != nil {
		return err
	}
	return clearErr
}

func mcrfVayAcquire(ctx context.Context, slots chan struct{}, closed <-chan struct{}, resolver <-chan error, fatal <-chan error) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	select {
	case slots <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-closed:
		return errors.New("session closed")
	case <-resolver:
		return errors.New("resolver closed")
	case err := <-fatal:
		return err
	}
}

func (t *Tunnel) mcrfAccept(ctx context.Context, ln *net.TCPListener, sess *smux.Session, conv uint32, bridge McrfBridge) (result error) {
	workers := make(chan struct{}, mcrfVayMaxClients)
	fatal := make(chan error, 1)
	stop := make(chan struct{})
	var wg sync.WaitGroup
	// Close all local sockets before retiring the SDK's transport fields.
	defer func() {
		close(stop)
		sess.Close()
		wg.Wait()
		// A simultaneous session close must not hide a worker's terminal
		// authentication refusal and restart with the same bad credentials.
		select {
		case err := <-fatal:
			result = err
		default:
		}
	}()
	for {
		select {
		case err := <-fatal:
			return err
		case <-ctx.Done():
			return ctx.Err()
		case <-sess.CloseChan():
			return errors.New("session closed")
		case <-t.dnsPacketConn.TransportErrors():
			return errors.New("resolver closed")
		default:
		}
		// Backpressure before Accept, never reset a browser flow just because
		// all current DNS streams are occupied. Session/cancel also wake a full queue.
		if err := mcrfVayAcquire(ctx, workers, sess.CloseChan(), t.dnsPacketConn.TransportErrors(), fatal); err != nil {
			return err
		}
		ln.SetDeadline(time.Now().Add(500 * time.Millisecond))
		local, err := ln.AcceptTCP()
		if err != nil {
			<-workers
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				continue
			}
			return err
		}
		wg.Add(1)
		go func(local *net.TCPConn) {
			defer wg.Done()
			defer func() { <-workers }()
			defer local.Close()
			finished := make(chan struct{})
			defer close(finished)
			go func() {
				select {
				case <-stop:
					local.Close()
				case <-ctx.Done():
					local.Close()
				case <-finished:
				}
			}()
			local.SetDeadline(time.Now().Add(5 * time.Second))
			if bridge.LocalAuth(local) != nil {
				return
			}
			local.SetDeadline(time.Time{})
			stream, err := openStreamWithTimeout(conv, t.OpenStreamTimeout, sess.OpenStream)
			if err != nil {
				return
			}
			defer stream.Close()
			if err = mcrfRemoteAuth(stream, bridge.RemoteAuth); err != nil {
				if mcrfTerminal(err) {
					select {
					case fatal <- err:
					default:
					}
				}
				return
			}
			// smux has no wire half-close. Preserve upstream's full-close EOF
			// semantics rather than inventing a CloseWrite protocol extension.
			copied := make(chan struct{})
			go func() { io.Copy(stream, local); stream.Close(); close(copied) }()
			io.Copy(local, stream)
			local.Close()
			stream.Close()
			<-copied
		}(local)
	}
}
