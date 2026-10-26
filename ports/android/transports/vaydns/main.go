package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/net2share/vaydns/client"
	log "github.com/sirupsen/logrus"
)

type config struct {
	Domain         string `json:"domain"`
	Key            string `json:"key"`
	DoH            string `json:"doh"`
	DoT            string `json:"dot"`
	UDP            string `json:"udp"`
	SOCKS          string `json:"socks"`
	User           string `json:"socks_user"`
	Password       string `json:"socks_pass"`
	RemoteUser     string `json:"username"`
	RemotePassword string `json:"password"`
}

func readConfig(reader io.Reader) (config, error) {
	var c config
	data, err := io.ReadAll(io.LimitReader(reader, 65537))
	if err != nil || len(data) > 65536 {
		return c, errors.New("config size")
	}
	// encoding/json otherwise silently lets a duplicate key replace a value.
	shape := json.NewDecoder(bytes.NewReader(data))
	start, err := shape.Token()
	if err != nil || start != json.Delim('{') {
		return c, errors.New("config object required")
	}
	seen := map[string]bool{}
	for shape.More() {
		token, e := shape.Token()
		name, ok := token.(string)
		if e != nil || !ok || seen[name] {
			return c, errors.New("duplicate or invalid config field")
		}
		seen[name] = true
		var raw json.RawMessage
		if shape.Decode(&raw) != nil {
			return c, errors.New("config value")
		}
	}
	if _, err = shape.Token(); err != nil {
		return c, errors.New("config object incomplete")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&c); err != nil {
		return config{}, errors.New("config format")
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return config{}, errors.New("config trailing data")
	}
	key, err := hex.DecodeString(c.Key)
	if err != nil || len(key) != 32 || len(c.Domain) == 0 || len(c.Domain) > 100 || strings.ContainsAny(c.Domain, "\r\n\t /:@") {
		return config{}, errors.New("server key or domain")
	}
	labels := strings.Split(c.Domain, ".")
	if len(labels) < 2 {
		return config{}, errors.New("delegated DNS domain required")
	}
	for _, label := range labels {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return config{}, errors.New("invalid DNS domain label")
		}
		for _, ch := range label {
			if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-') {
				return config{}, errors.New("invalid DNS domain label")
			}
		}
	}
	host, port, err := net.SplitHostPort(c.SOCKS)
	number, portErr := strconv.Atoi(port)
	if err != nil || host != "127.0.0.1" || portErr != nil || number < 1 || number > 65535 {
		return config{}, errors.New("private listener")
	}
	if _, err = client.McrfSOCKS(c.User, c.Password, c.RemoteUser, c.RemotePassword); err != nil {
		return config{}, errors.New("SOCKS credentials")
	}
	count := 0
	for _, address := range []string{c.DoH, c.DoT, c.UDP} {
		if address != "" {
			count++
		}
		if len(address) > 2048 || strings.ContainsAny(address, "\r\n\t ") {
			return config{}, errors.New("resolver format")
		}
	}
	if count != 1 {
		return config{}, errors.New("one resolver required")
	}
	if c.DoH != "" {
		uri, e := url.Parse(c.DoH)
		if e != nil || uri.Scheme != "https" || uri.Hostname() == "" || uri.User != nil || uri.Fragment != "" || uri.Opaque != "" {
			return config{}, errors.New("DoH HTTPS required")
		}
		if uri.Port() != "" {
			n, e := strconv.Atoi(uri.Port())
			if e != nil || n < 1 || n > 65535 {
				return config{}, errors.New("DoH port")
			}
		}
	}
	for _, address := range []string{c.DoT, c.UDP} {
		if address == "" {
			continue
		}
		h, p, e := net.SplitHostPort(address)
		n, pe := strconv.Atoi(p)
		if e != nil || pe != nil || n < 1 || n > 65535 || h == "" || strings.ContainsAny(h, "/@?#") {
			return config{}, errors.New("resolver endpoint")
		}
		if address == c.UDP && net.ParseIP(h) == nil {
			return config{}, errors.New("numeric UDP resolver required")
		}
	}
	return c, nil
}

func run(ctx context.Context, c config) error {
	kind, address := client.ResolverTypeDOH, c.DoH
	if c.DoT != "" {
		kind, address = client.ResolverTypeDOT, c.DoT
	}
	if c.UDP != "" {
		kind, address = client.ResolverTypeUDP, c.UDP
	}
	resolver, err := client.NewResolver(kind, address)
	if err != nil {
		return errors.New("resolver configuration")
	}
	// Preserve upstream's resolver wire/socket policy; do not trade random
	// source ports for one persistent fingerprint as a hidden optimisation.
	server, err := client.NewTunnelServer(c.Domain, c.Key)
	if err != nil {
		return errors.New("server configuration")
	}
	tunnel, err := client.NewTunnel(resolver, server)
	if err != nil {
		return errors.New("tunnel configuration")
	}
	bridge, err := client.McrfSOCKS(c.User, c.Password, c.RemoteUser, c.RemotePassword)
	if err != nil {
		return errors.New("SOCKS configuration")
	}
	bridge.Ready = func() { fmt.Println("MCRF_READY") }
	bridge.Diagnostic = func(category string) { fmt.Println("MCRF_STAGE " + category) }
	addr, err := net.ResolveTCPAddr("tcp4", c.SOCKS)
	if err != nil {
		return errors.New("listener configuration")
	}
	ln, err := net.ListenTCP("tcp4", addr)
	if err != nil {
		return errors.New("local listener unavailable")
	}
	return tunnel.McrfServe(ctx, ln, bridge)
}

func main() {
	// Upstream logs contain endpoint/session details. Publish only our bounded
	// categories; never raw config, URL, key, credentials or stack traces.
	log.SetOutput(io.Discard)
	if len(os.Args) != 3 || os.Args[1] != "--mcrf-config" {
		fmt.Println("MCRF_ERROR CONFIG_ARGUMENTS")
		os.Exit(2)
	}
	file, err := os.Open(os.Args[2])
	if err != nil {
		fmt.Println("MCRF_ERROR CONFIG_READ")
		os.Exit(2)
	}
	c, err := readConfig(file)
	file.Close()
	if err != nil {
		fmt.Println("MCRF_ERROR CONFIG_INVALID: " + err.Error())
		os.Exit(2)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(os.Stdin, 1024))
		for scanner.Scan() {
			if scanner.Text() == "STOP" {
				cancel()
				return
			}
		}
		cancel()
	}()
	// SDK resolver/Noise calls have their own time bounds. Parent STOP/EOF
	// still guarantees bounded process exit without concurrent Tunnel.Close.
	go func() {
		<-ctx.Done()
		timer := time.NewTimer(2 * time.Second)
		defer timer.Stop()
		<-timer.C
		os.Exit(0)
	}()
	if err = run(ctx, c); err != nil && ctx.Err() == nil {
		switch {
		case errors.Is(err, client.ErrMcrfRemoteAuth):
			fmt.Println("MCRF_ERROR REMOTE_SOCKS_AUTH_REJECTED")
		case errors.Is(err, client.ErrMcrfRemoteProtocol):
			fmt.Println("MCRF_ERROR REMOTE_SOCKS_PROTOCOL_REQUIRED")
		default:
			fmt.Println("MCRF_ERROR VAYDNS_SESSION_STOPPED")
		}
		os.Exit(1)
	}
}
