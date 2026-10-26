// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/url"
	"strconv"
	"strings"
	"unicode"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

func readConfig(reader io.Reader) (client.Config, error) {
	rejected := errors.New("OLC_CONFIG_REJECTED")
	data, err := io.ReadAll(io.LimitReader(reader, 65537))
	if err != nil || len(data) > 65536 {
		return client.Config{}, rejected
	}
	allowed := map[string]bool{"provider": true, "transport": true, "room": true, "key": true, "token": true, "channel": true, "dns": true, "device_id": true, "socks": true, "socks_user": true, "socks_pass": true, "options": true}
	if !strictObject(data, allowed) {
		return client.Config{}, rejected
	}
	var c config
	if json.Unmarshal(data, &c) != nil {
		return client.Config{}, rejected
	}
	options := map[string]bool{"vp8-fps": true, "vp8-batch": true, "fps": true, "batch": true, "frag": true, "ack-ms": true, "video-w": true, "video-h": true, "video-fps": true, "video-qr-size": true, "video-qr-recovery": true, "video-codec": true, "video-tile-module": true, "video-tile-rs": true}
	var raw map[string]json.RawMessage
	json.Unmarshal(data, &raw)
	if value, ok := raw["options"]; ok && !strictObject(value, options) {
		return client.Config{}, rejected
	}
	// Enforce every supplied option even if it belongs to another transport:
	// ignored typos and malformed values must not become invisible defaults.
	for key, value := range c.Options {
		switch key {
		case "video-codec":
			if text(c.Options, key, "") != "tile" && text(c.Options, key, "") != "qrcode" {
				return client.Config{}, rejected
			}
		case "video-qr-recovery":
			v := text(c.Options, key, "")
			if v != "low" && v != "medium" && v != "high" && v != "highest" {
				return client.Config{}, rejected
			}
		default:
			limits := map[string]int{"vp8-fps": 120, "vp8-batch": 64, "fps": 120, "batch": 64, "frag": 65535, "ack-ms": 60000, "video-w": 4096, "video-h": 4096, "video-fps": 120, "video-qr-size": 65535, "video-tile-module": 270, "video-tile-rs": 200}
			max, ok := limits[key]
			if !ok || bytes.Equal(value, []byte("null")) {
				return client.Config{}, rejected
			}
			min := 1
			if key == "video-qr-size" || key == "video-tile-rs" {
				min = 0
			}
			if _, err := number(c.Options, key, 0, min, max); err != nil {
				return client.Config{}, rejected
			}
		}
	}
	if len(c.Room) == 0 || len(c.Room) > 8192 || strings.IndexFunc(c.Room+c.DeviceID+c.Channel+c.Token, unicode.IsControl) >= 0 || len(c.DeviceID) > 256 || len(c.Token) > 16384 || len(c.Channel) > 8192 {
		return client.Config{}, rejected
	}
	host, port, err := net.SplitHostPort(c.DNS)
	n, pe := strconv.Atoi(port)
	if err != nil || pe != nil || net.ParseIP(host) == nil || n < 1 || n > 65535 {
		return client.Config{}, rejected
	}
	if c.Provider == "jitsi" {
		room := c.Room
		if !strings.HasPrefix(room, "https://") {
			room = "https://" + room
		}
		uri, err := url.Parse(room)
		if err != nil || uri.Scheme != "https" || uri.Hostname() == "" || uri.User != nil || len(uri.Path) < 2 {
			return client.Config{}, rejected
		}
		if uri.Port() != "" {
			port, err := strconv.Atoi(uri.Port())
			if err != nil || port < 1 || port > 65535 {
				return client.Config{}, rejected
			}
		}
	}
	cfg, err := makeClient(c)
	if err != nil {
		return client.Config{}, rejected
	}
	return cfg, nil
}

func strictObject(data []byte, allowed map[string]bool) bool {
	d := json.NewDecoder(bytes.NewReader(data))
	start, err := d.Token()
	if err != nil || start != json.Delim('{') {
		return false
	}
	seen := map[string]bool{}
	for d.More() {
		token, err := d.Token()
		key, ok := token.(string)
		if err != nil || !ok || seen[key] || !allowed[key] {
			return false
		}
		seen[key] = true
		var value json.RawMessage
		if d.Decode(&value) != nil || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return false
		}
	}
	if _, err := d.Token(); err != nil {
		return false
	}
	var extra any
	return d.Decode(&extra) == io.EOF
}
