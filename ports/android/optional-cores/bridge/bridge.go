// SPDX-License-Identifier: GPL-3.0-or-later
package bridge

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// Only one outbound crosses the trust boundary. Caller cannot inject inbounds,
// API listeners, file logs, routing, remote rule downloads or process commands.
type Config struct {
	API                   int             `json:"api"`
	SOCKS                 string          `json:"socks"`
	User                  string          `json:"socks_user"`
	Password              string          `json:"socks_pass"`
	Outbound              json.RawMessage `json:"outbound"`
	CertificateStore      string          `json:"certificate_store,omitempty"`
	ServerDNS             *ServerDNS      `json:"server_dns,omitempty"`
	PhysicalNetworkHandle uint64          `json:"physical_network_handle,omitempty"`
}

func CAStore(value string) (string, error) {
	if value == "" {
		return "system", nil
	}
	if value != "system" && value != "mozilla" {
		return "", errors.New("unsupported certificate store")
	}
	return value, nil
}

func Read(path string) (Config, error) {
	var c Config
	f, err := os.Open(path)
	if err != nil {
		return c, errors.New("private configuration unavailable")
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, 65537))
	if err != nil || len(data) > 65536 {
		return c, errors.New("private configuration exceeds 64 KiB")
	}
	d := json.NewDecoder(strings.NewReader(string(data)))
	d.DisallowUnknownFields()
	if err = d.Decode(&c); err != nil {
		return c, errors.New("invalid private configuration")
	}
	if d.Decode(new(any)) != io.EOF {
		return c, errors.New("trailing configuration")
	}
	if _, err = CAStore(c.CertificateStore); err != nil {
		return c, err
	}
	if c.ServerDNS != nil {
		if err = c.ServerDNS.Validate(); err != nil {
			return c, err
		}
	}
	host, port, err := net.SplitHostPort(c.SOCKS)
	n, _ := strconv.Atoi(port)
	if c.API != 1 || err != nil || host != "127.0.0.1" || n < 1 || n > 65535 || len([]byte(c.User)) < 16 || len([]byte(c.User)) > 255 || len([]byte(c.Password)) < 16 || len([]byte(c.Password)) > 255 {
		return c, errors.New("API 1 and authenticated IPv4 loopback required")
	}
	var out map[string]json.RawMessage
	if json.Unmarshal(c.Outbound, &out) != nil || out == nil {
		return c, errors.New("one outbound object required")
	}
	return c, nil
}

func Object(raw json.RawMessage) (map[string]json.RawMessage, error) {
	var m map[string]json.RawMessage
	if json.Unmarshal(raw, &m) != nil || m == nil {
		return nil, errors.New("invalid outbound")
	}
	return m, nil
}

func RejectKeys(m map[string]json.RawMessage, keys ...string) error {
	for _, k := range keys {
		if _, ok := m[k]; ok {
			return errors.New("unsafe or unsupported outbound field")
		}
	}
	return nil
}

// Auth probe checks BOTH rejection of anonymous access and successful RFC1929.
func Ready(c Config) bool {
	dial := func() (net.Conn, error) {
		n, e := net.DialTimeout("tcp", c.SOCKS, 100*time.Millisecond)
		if e == nil {
			n.SetDeadline(time.Now().Add(300 * time.Millisecond))
		}
		return n, e
	}
	n, e := dial()
	if e != nil {
		return false
	}
	n.Write([]byte{5, 1, 0})
	b := make([]byte, 2)
	_, e = io.ReadFull(n, b)
	n.Close()
	if e != nil || b[0] != 5 || b[1] != 255 {
		return false
	}
	n, e = dial()
	if e != nil {
		return false
	}
	defer n.Close()
	n.Write([]byte{5, 1, 2})
	if _, e = io.ReadFull(n, b); e != nil || b[0] != 5 || b[1] != 2 {
		return false
	}
	auth := append([]byte{1, byte(len([]byte(c.User)))}, []byte(c.User)...)
	auth = append(auth, byte(len([]byte(c.Password))))
	auth = append(auth, []byte(c.Password)...)
	n.Write(auth)
	_, e = io.ReadFull(n, b)
	return e == nil && b[0] == 1 && b[1] == 0
}

type Core interface {
	Start() error
	Close() error
}
type Factory func(context.Context, Config) (Core, error)

func Run(c Config, factory Factory, input io.Reader, output io.Writer) error {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go func() {
		s := bufio.NewScanner(io.LimitReader(input, 8192))
		s.Buffer(make([]byte, 256), 1024)
		for s.Scan() {
			if s.Text() == "STOP" || s.Text() == "NETWORK_CHANGED" {
				cancel()
				return
			}
		}
		cancel()
	}()
	if ctx.Err() != nil {
		return nil
	}
	core, err := factory(ctx, c)
	if err != nil {
		return errors.New("core configuration rejected")
	}
	defer core.Close()
	if ctx.Err() != nil {
		return nil
	}
	// Single-hop outbounds are lazy; Start only creates the local ingress.
	// Serialize Start/Close to avoid cancellation racing native initialization.
	if err = core.Start(); err != nil {
		return errors.New("core could not start")
	}
	deadline := time.NewTimer(5 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(50 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-deadline.C:
			return errors.New("authenticated listener unavailable")
		case <-tick.C:
			if Ready(c) {
				if ctx.Err() != nil {
					return nil
				}
				fmt.Fprintln(output, "MCRF_READY")
				<-ctx.Done()
				return nil
			}
		}
	}
}

func Main(descriptor string, factory Factory) {
	if len(os.Args) == 2 && os.Args[1] == "--mcrf-describe" {
		fmt.Println(descriptor)
		return
	}
	if len(os.Args) != 3 || (os.Args[1] != "--mcrf-config" && os.Args[1] != "--mcrf-check") {
		fmt.Fprintln(os.Stderr, "private configuration required")
		os.Exit(2)
	}
	c, err := Read(os.Args[2])
	if err == nil {
		if os.Args[1] == "--mcrf-check" {
			var core Core
			core, err = factory(context.Background(), c)
			if err == nil {
				err = core.Close()
				if err == nil {
					fmt.Println("MCRF_VALID")
				}
			}
		} else {
			err = Run(c, factory, os.Stdin, os.Stdout)
		}
	}
	// Do not propagate native parse errors: they can quote UUIDs, endpoints or passwords.
	if err != nil {
		fmt.Fprintln(os.Stderr, "optional core request rejected")
		os.Exit(1)
	}
}
