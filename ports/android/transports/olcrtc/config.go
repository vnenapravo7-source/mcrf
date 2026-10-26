// SPDX-License-Identifier: GPL-3.0-or-later
// Transport-option mapping derived from ttrroyy/qThroneMobile 8463d3397b0a8522f92811f5c0698679ab8efc77, carriers/olcrtc/main.go.
package main

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
	"net"
	"strconv"
	"strings"
	"time"
)

type config struct {
	Provider  string                     `json:"provider"`
	Transport string                     `json:"transport"`
	Room      string                     `json:"room"`
	Key       string                     `json:"key"`
	Token     string                     `json:"token"`
	Channel   string                     `json:"channel"`
	DNS       string                     `json:"dns"`
	DeviceID  string                     `json:"device_id"`
	SOCKS     string                     `json:"socks"`
	SOCKSUser string                     `json:"socks_user"`
	SOCKSPass string                     `json:"socks_pass"`
	Options   map[string]json.RawMessage `json:"options"`
}

func number(options map[string]json.RawMessage, key string, fallback, min, max int) (int, error) {
	raw, exists := options[key]
	if !exists {
		return fallback, nil
	}
	var value int
	if err := json.Unmarshal(raw, &value); err != nil {
		var text string
		if json.Unmarshal(raw, &text) != nil {
			return 0, errors.New("invalid numeric transport option")
		}
		var err error
		value, err = strconv.Atoi(text)
		if err != nil {
			return 0, errors.New("invalid numeric transport option")
		}
	}
	if value < min || value > max {
		return 0, errors.New("transport option out of range")
	}
	return value, nil
}
func text(options map[string]json.RawMessage, key, fallback string) string {
	raw, ok := options[key]
	if !ok {
		return fallback
	}
	var s string
	if json.Unmarshal(raw, &s) != nil {
		return ""
	}
	return s
}
func makeClient(c config) (client.Config, error) {
	if len(c.SOCKSUser) < 1 || len(c.SOCKSUser) > 255 || len(c.SOCKSPass) < 1 || len(c.SOCKSPass) > 255 {
		return client.Config{}, errors.New("authenticated bridge required")
	}
	key, err := hex.DecodeString(c.Key)
	if err != nil || len(key) != 32 {
		return client.Config{}, errors.New("invalid encryption key")
	}
	host, port, err := net.SplitHostPort(c.SOCKS)
	n, _ := strconv.Atoi(port)
	if err != nil || host != "127.0.0.1" || n < 1 || n > 65535 || c.DeviceID == "" || strings.TrimSpace(c.Room) == "" {
		return client.Config{}, errors.New("invalid local session configuration")
	}
	if c.Provider != "jitsi" && c.Provider != "telemost" && c.Provider != "wbstream" {
		return client.Config{}, errors.New("unknown service")
	}
	if c.Provider == "telemost" && (c.Transport == "datachannel" || c.Transport == "seichannel") {
		return client.Config{}, errors.New("unsupported Telemost transport")
	}
	if c.Provider == "wbstream" && c.Transport == "datachannel" && c.Token == "" {
		return client.Config{}, errors.New("WB Stream DataChannel requires a publish token")
	}
	if _, _, err = net.SplitHostPort(c.DNS); err != nil {
		return client.Config{}, errors.New("invalid DNS resolver")
	}
	cfg := client.Config{Provider: c.Provider, Transport: c.Transport, RoomURL: c.Room, KeyHex: c.Key, ProviderToken: c.Token, ChannelID: c.Channel, DNSServer: c.DNS, LocalAddr: c.SOCKS, SOCKSUser: c.SOCKSUser, SOCKSPass: c.SOCKSPass, DeviceID: c.DeviceID, Liveness: client.LivenessConfig{Interval: 10 * time.Second, Timeout: 15 * time.Second, Failures: 4}}
	get := func(key string, def, min, max int) int {
		v, e := number(c.Options, key, def, min, max)
		if e != nil {
			err = e
		}
		return v
	}
	switch c.Transport {
	case "datachannel":
	case "vp8channel":
		cfg.TransportOptions = client.VP8Options{FPS: get("vp8-fps", 30, 1, 120), BatchSize: get("vp8-batch", 64, 1, 64)}
	case "seichannel":
		cfg.TransportOptions = client.SEIOptions{FPS: get("fps", 30, 1, 120), BatchSize: get("batch", 64, 1, 64), FragmentSize: get("frag", 900, 1, 65535), AckTimeoutMS: get("ack-ms", 2000, 1, 60000)}
	case "videochannel":
		v := client.VideoOptions{Width: get("video-w", 1080, 1, 4096), Height: get("video-h", 1080, 1, 4096), FPS: get("video-fps", 30, 1, 120), QRSize: get("video-qr-size", 0, 0, 65535), QRRecovery: text(c.Options, "video-qr-recovery", "low"), Codec: text(c.Options, "video-codec", "qrcode"), TileModule: get("video-tile-module", 4, 1, 270), TileRS: get("video-tile-rs", 0, 0, 200)}
		if v.Codec != "qrcode" && v.Codec != "tile" {
			return cfg, errors.New("unknown video codec")
		}
		if v.Codec == "tile" && (v.Width != 1080 || v.Height != 1080) {
			return cfg, errors.New("tile requires 1080x1080")
		}
		if v.QRRecovery != "low" && v.QRRecovery != "medium" && v.QRRecovery != "high" && v.QRRecovery != "highest" {
			return cfg, errors.New("invalid QR recovery")
		}
		cfg.TransportOptions = v
	default:
		return cfg, errors.New("unknown transport")
	}
	return cfg, err
}
