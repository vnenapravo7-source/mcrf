// SPDX-License-Identifier: GPL-3.0-or-later
package client

import (
	"context"
	"net"
	"sync"
	"time"
)

type mcrfOwnedConnection struct {
	net.Conn
	once      sync.Once
	handshake sync.Once
	release   func()
}

func (c *mcrfOwnedConnection) Close() error {
	err := c.Conn.Close()
	c.once.Do(c.release)
	return err
}
func (c *mcrfOwnedConnection) CloseWrite() error {
	if half, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return half.CloseWrite()
	}
	return nil
}
func (c *mcrfOwnedConnection) CloseRead() error {
	if half, ok := c.Conn.(interface{ CloseRead() error }); ok {
		return half.CloseRead()
	}
	return nil
}
func (c *mcrfOwnedConnection) Write(data []byte) (int, error) {
	// With mandatory RFC1929 the method reply is 05 02, never 05 00.
	// A successful CONNECT/UDP-ASSOCIATE reply clears the handshake deadline
	// before the asynchronous SDK stream takes ownership of this connection.
	if len(data) >= 4 && data[0] == 5 && data[1] == 0 && data[2] == 0 {
		c.handshake.Do(func() { c.Conn.SetDeadline(time.Time{}) })
	}
	return c.Conn.Write(data)
}

func (l *TCPListener) mcrfAccept(ctx context.Context, listener net.Listener) {
	slots := make(chan struct{}, 512)
	owned := sync.Map{}
	stop := context.AfterFunc(ctx, func() {
		listener.Close()
		owned.Range(func(k, v any) bool { k.(*mcrfOwnedConnection).Close(); return true })
	})
	defer func() {
		stop()
		listener.Close()
		owned.Range(func(k, v any) bool { k.(*mcrfOwnedConnection).Close(); return true })
	}()
	for {
		select {
		case slots <- struct{}{}:
		case <-ctx.Done():
			return
		}
		raw, err := listener.Accept()
		if err != nil {
			<-slots
			return
		}
		conn := &mcrfOwnedConnection{Conn: raw}
		conn.release = func() { owned.Delete(conn); <-slots }
		owned.Store(conn, true)
		if ctx.Err() != nil {
			conn.Close()
			return
		}
		conn.SetDeadline(time.Now().Add(10 * time.Second))
		// HandleSOCKS5 hands connections to asynchronous SDK streams. Do not
		// defer Close here: release the slot only on the actual stream Close.
		go l.handleConnection(ctx, conn, l.protocolType)
	}
}
