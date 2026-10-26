package main

import (
	"bufio"
	"bytes"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
	"unicode"
)

var mobile *mobileConfig

type mobileConfig struct {
	Domain     string `json:"domain"`
	Key        string `json:"key"`
	DoH        string `json:"doh"`
	DoT        string `json:"dot"`
	UDP        string `json:"udp"`
	SOCKS      string `json:"socks"`
	User       string `json:"socks_user"`
	Pass       string `json:"socks_pass"`
	RemoteUser string `json:"username"`
	RemotePass string `json:"password"`
}

func init() {
	if len(os.Args) != 3 || os.Args[1] != "--mcrf-config" {
		return
	}
	f, err := os.Open(os.Args[2])
	if err != nil {
		mobileReport("DNSTT_CONFIG_READ")
		os.Exit(2)
	}
	c, flag, resolver, err := mobileReadConfig(f)
	f.Close()
	if err != nil {
		mobileReport("DNSTT_CONFIG_REJECTED")
		os.Exit(2)
	}
	mobile = c
	// Keys are changed only in this process's Go args, never exposed as OS command-line arguments.
	os.Args = []string{os.Args[0], flag, resolver, "-pubkey", c.Key, c.Domain, c.SOCKS}
	log.SetOutput(io.Discard)
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(os.Stdin, 1024))
		for scanner.Scan() {
			if scanner.Text() == "STOP" {
				os.Exit(0)
			}
		}
		os.Exit(0)
	}()
}

func mobileReadConfig(reader io.Reader) (*mobileConfig, string, string, error) {
	rejected := errors.New("DNSTT_CONFIG_REJECTED")
	data, err := io.ReadAll(io.LimitReader(reader, 65537))
	if err != nil || len(data) > 65536 {
		return nil, "", "", rejected
	}
	shape := json.NewDecoder(bytes.NewReader(data))
	token, err := shape.Token()
	if err != nil || token != json.Delim('{') {
		return nil, "", "", rejected
	}
	seen := map[string]bool{}
	allowed := map[string]bool{"domain": true, "key": true, "doh": true, "dot": true, "udp": true, "socks": true, "socks_user": true, "socks_pass": true, "username": true, "password": true}
	for shape.More() {
		token, err = shape.Token()
		name, ok := token.(string)
		if err != nil || !ok || seen[name] || !allowed[name] {
			return nil, "", "", rejected
		}
		seen[name] = true
		var value json.RawMessage
		if shape.Decode(&value) != nil || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return nil, "", "", rejected
		}
	}
	if _, err = shape.Token(); err != nil {
		return nil, "", "", rejected
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	c := new(mobileConfig)
	if decoder.Decode(c) != nil {
		return nil, "", "", rejected
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return nil, "", "", rejected
	}
	key, err := hex.DecodeString(c.Key)
	host, _, listenErr := net.SplitHostPort(c.SOCKS)
	_, port, _ := net.SplitHostPort(c.SOCKS)
	n, portErr := strconv.Atoi(port)
	if err != nil || len(key) != 32 || listenErr != nil || host != "127.0.0.1" || portErr != nil || n < 1 || n > 65535 || len(c.User) < 1 || len(c.User) > 255 || len(c.Pass) < 1 || len(c.Pass) > 255 || !mobileDomain(c.Domain) {
		return nil, "", "", rejected
	}
	flag, resolver, count := "", "", 0
	for _, r := range []struct{ flag, value string }{{"-doh", c.DoH}, {"-dot", c.DoT}, {"-udp", c.UDP}} {
		if r.value != "" {
			flag, resolver = r.flag, r.value
			count++
		}
	}
	if count != 1 {
		return nil, "", "", rejected
	}
	if flag == "-doh" {
		uri, e := url.Parse(resolver)
		if e != nil || uri.Scheme != "https" || uri.Hostname() == "" || uri.User != nil || uri.Fragment != "" || uri.Opaque != "" {
			return nil, "", "", rejected
		}
		if uri.Port() != "" {
			n, e := strconv.Atoi(uri.Port())
			if e != nil || n < 1 || n > 65535 {
				return nil, "", "", rejected
			}
		}
	} else {
		h, p, e := net.SplitHostPort(resolver)
		n, pe := strconv.Atoi(p)
		if e != nil || pe != nil || n < 1 || n > 65535 || h == "" || strings.IndexFunc(h, unicode.IsControl) >= 0 {
			return nil, "", "", rejected
		}
		if flag == "-udp" && net.ParseIP(h) == nil {
			return nil, "", "", rejected
		}
		if flag == "-dot" && net.ParseIP(h) == nil && !mobileDomainName(h, 253) {
			return nil, "", "", rejected
		}
	}
	if len(c.RemoteUser) > 255 || len(c.RemotePass) > 255 || (c.RemoteUser == "") != (c.RemotePass == "") || strings.IndexFunc(c.RemoteUser+c.RemotePass, unicode.IsControl) >= 0 {
		return nil, "", "", rejected
	}
	return c, flag, resolver, nil
}

func mobileDomain(value string) bool {
	return mobileDomainName(value, 100) && strings.Contains(value, ".")
}
func mobileDomainName(value string, maximum int) bool {
	if len(value) == 0 || len(value) > maximum {
		return false
	}
	for _, label := range strings.Split(value, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, ch := range label {
			if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-') {
				return false
			}
		}
	}
	return true
}

// Authenticate before opening a tunnel stream: loopback is accessible to other Android apps.
func mobileAuth(local net.Conn) error {
	if mobile == nil {
		return nil
	}
	local.SetDeadline(time.Now().Add(5 * time.Second))
	defer local.SetDeadline(time.Time{})
	header := make([]byte, 2)
	if _, err := io.ReadFull(local, header); err != nil || header[0] != 5 || header[1] == 0 {
		return errors.New("invalid bridge handshake")
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(local, methods); err != nil {
		return err
	}
	found := false
	for _, m := range methods {
		if m == 2 {
			found = true
		}
	}
	if !found {
		local.Write([]byte{5, 255})
		return errors.New("authenticated bridge required")
	}
	if _, err := local.Write([]byte{5, 2}); err != nil {
		return err
	}
	if _, err := io.ReadFull(local, header); err != nil || header[0] != 1 {
		return errors.New("invalid bridge auth")
	}
	user := make([]byte, int(header[1]))
	if _, err := io.ReadFull(local, user); err != nil {
		return err
	}
	length := make([]byte, 1)
	if _, err := io.ReadFull(local, length); err != nil {
		return err
	}
	pass := make([]byte, int(length[0]))
	if _, err := io.ReadFull(local, pass); err != nil {
		return err
	}
	valid := subtle.ConstantTimeCompare(user, []byte(mobile.User)) & subtle.ConstantTimeCompare(pass, []byte(mobile.Pass))
	if valid != 1 {
		local.Write([]byte{1, 1})
		return errors.New("bridge auth rejected")
	}
	_, err := local.Write([]byte{1, 0})
	return err
}

// The remote end of the DNS tunnel must forward to a SOCKS5 server, not an arbitrary TCP service.
func mobileRemote(stream io.ReadWriter) error {
	if mobile == nil {
		return nil
	}
	method := byte(0)
	if mobile.RemoteUser != "" {
		method = 2
	}
	if _, err := stream.Write([]byte{5, 1, method}); err != nil {
		return err
	}
	reply := make([]byte, 2)
	if _, err := io.ReadFull(stream, reply); err != nil {
		return err
	}
	if reply[0] != 5 || reply[1] != method {
		return errors.New("remote SOCKS negotiation failed")
	}
	if method == 2 {
		auth := append([]byte{1, byte(len(mobile.RemoteUser))}, []byte(mobile.RemoteUser)...)
		auth = append(auth, byte(len(mobile.RemotePass)))
		auth = append(auth, []byte(mobile.RemotePass)...)
		if _, err := stream.Write(auth); err != nil {
			return err
		}
		if _, err := io.ReadFull(stream, reply); err != nil {
			return err
		}
		if reply[0] != 1 || reply[1] != 0 {
			return errors.New("remote SOCKS auth failed")
		}
	}
	return nil
}
