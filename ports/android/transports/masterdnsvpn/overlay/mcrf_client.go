// SPDX-License-Identifier: GPL-3.0-or-later
package client

import (
	"context"
	"errors"
	"net"
)

// Set only before Run starts; never poll the upstream's unsynchronised
// sessionReady flag from another goroutine or a periodic readiness timer.
func (c *Client) McrfRun(ctx context.Context, event func(string)) error {
	c.mcrfContext = ctx
	c.mcrfEvent = event
	c.mcrfWorkerLimit = c.cfg.RX_TX_Workers
	defer c.StopAsyncRuntime()
	err := c.Run(ctx)
	if err == nil || ctx.Err() != nil {
		return err
	}
	var network net.Error
	if errors.As(err, &network) && network.Timeout() {
		return errors.New("MASTERDNS_NETWORK_TIMEOUT")
	}
	var operation *net.OpError
	if errors.As(err, &operation) && operation.Op == "listen" {
		if operation.Net == "tcp" || operation.Net == "tcp4" || operation.Net == "tcp6" {
			return errors.New("MASTERDNS_LOCAL_LISTENER")
		}
		return errors.New("MASTERDNS_LOCAL_UDP")
	}
	return errors.New("MASTERDNS_RUNTIME")
}

func (c *Client) mcrfRootContext() context.Context {
	if c.mcrfContext != nil {
		return c.mcrfContext
	}
	return context.Background()
}

func (c *Client) mcrfStage(stage string) {
	switch stage {
	case "mtu", "mtu-failed", "session", "session-failed", "busy", "ready", "lost":
		if c.mcrfEvent != nil {
			c.mcrfEvent(stage)
		}
	}
}

func (c *Client) mcrfRXWorkers(requested int) int {
	if c.mcrfWorkerLimit > 0 && requested > c.mcrfWorkerLimit {
		return c.mcrfWorkerLimit
	}
	return requested
}
