package main

import (
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

// TLS Read can itself write alerts/KeyUpdate replies. Bound EVERY wire write,
// not only application Write calls, without another reader extending a stalled
// writer's deadline. Closing raw on STOP interrupts this wait immediately.
type boundedWrite49 struct {
	net.Conn
	mu sync.Mutex
}

func (c *boundedWrite49) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	_ = c.Conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	n, e := c.Conn.Write(p)
	_ = c.Conn.SetWriteDeadline(time.Time{})
	if e != nil {
		return n, errors.Join(errTLSWrite49, e)
	}
	if n != len(p) {
		return n, errors.Join(errTLSWrite49, io.ErrShortWrite)
	}
	return n, nil
}

// Standard Go TLS (Xray's explicit "unsafe" fingerprint name) has no exported
// pending-buffer API. Prevent its input reader from prefetching the next wire
// record. Read a whole plaintext record into OUR bounded buffer before serving
// short native reads; Vision can then drain it without reflection/unsafe casts.
type recordBoundary49 struct {
	net.Conn
	header  [5]byte
	headerN int
	left    int
}

func (c *recordBoundary49) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	if c.headerN < 5 {
		limit := 5 - c.headerN
		if len(p) > limit {
			p = p[:limit]
		}
		n, e := c.Conn.Read(p)
		copy(c.header[c.headerN:], p[:n])
		c.headerN += n
		if c.headerN == 5 {
			c.left = int(c.header[3])<<8 | int(c.header[4])
			if c.left > 18432 {
				return n, errLimit
			}
			if c.left == 0 {
				c.headerN = 0
			}
		}
		return n, e
	}
	if c.left <= 0 {
		return 0, io.ErrUnexpectedEOF
	}
	if len(p) > c.left {
		p = p[:c.left]
	}
	n, e := c.Conn.Read(p)
	c.left -= n
	if c.left == 0 {
		c.headerN = 0
	}
	return n, e
}
