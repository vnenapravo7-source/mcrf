// Copyright 2026 MCRF contributors. Original CSQTT required notice: Copyright 2026 amurcanov.
// The separately supplied CSQTT runtime remains PolyForm Noncommercial 1.0.0.
package csqtt25

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"unicode"
)

// Version is mandatory at this boundary: an old profile must never select the 2.5 binary.
type Profile struct {
	Version                 string   `json:"csqtt_generation"`
	Peer                    string   `json:"peer"`
	Password                string   `json:"password"`
	Hashes                  []string `json:"hashes"`
	Token                   string   `json:"vk_token,omitempty"`
	HashMode                string   `json:"vk_hash_mode"`
	AllowHashRedistribution bool     `json:"allow_hash_redistribution"`
	Workers                 int      `json:"workers"`
	ExactWorkers            bool     `json:"exact_workers"`
	DeviceID                string   `json:"device_id"`
	Listen                  string   `json:"listen"`
	CaptchaMode             string   `json:"captcha_mode"`
	VKAuthMode              string   `json:"vk_auth_mode"`
	Fingerprint             string   `json:"fingerprint"`
	Obfs                    string   `json:"obfs"`
	ClientIDs               string   `json:"client_ids,omitempty"`
	Generation              int      `json:"generation,omitempty"`
	Salt                    string   `json:"salt,omitempty"`
	TurnTransport           string   `json:"turn_transport"`
	CustomTurnIPs           []string `json:"custom_turn_ips,omitempty"`
	SOCKS                   string   `json:"socks,omitempty"`
	SOCKSUser               string   `json:"socks_user,omitempty"`
	SOCKSPass               string   `json:"socks_pass,omitempty"`
	ProbeOnly               bool     `json:"probe_only,omitempty"`
}

func (p Profile) String() string { return "CSQTT25Profile(redacted)" }

func ReadProfile(r io.Reader) (Profile, error) {
	var p Profile
	data, err := io.ReadAll(io.LimitReader(r, 65537))
	if err != nil || len(data) > 65536 {
		return p, errors.New("invalid private CSQTT 2.5 profile")
	}
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	start, e := decoder.Token()
	if e != nil || start != json.Delim('{') {
		return Profile{}, errors.New("invalid private CSQTT 2.5 profile")
	}
	fields := map[string]json.RawMessage{}
	for decoder.More() {
		token, e := decoder.Token()
		key, ok := token.(string)
		if e != nil || !ok {
			return Profile{}, errors.New("invalid private CSQTT 2.5 profile")
		}
		if _, duplicate := fields[key]; duplicate {
			return Profile{}, errors.New("invalid private CSQTT 2.5 profile")
		}
		var raw json.RawMessage
		if decoder.Decode(&raw) != nil {
			return Profile{}, errors.New("invalid private CSQTT 2.5 profile")
		}
		fields[key] = raw
	}
	end, e := decoder.Token()
	if e != nil || end != json.Delim('}') {
		return Profile{}, errors.New("invalid private CSQTT 2.5 profile")
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return Profile{}, errors.New("invalid private CSQTT 2.5 profile")
	}
	normalized, _ := json.Marshal(fields)
	decoder = json.NewDecoder(strings.NewReader(string(normalized)))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&p) != nil {
		return Profile{}, errors.New("invalid private CSQTT 2.5 profile")
	}
	if err = p.Validate(); err != nil {
		return Profile{}, err
	}
	return p, nil
}

func clean(s string, max int) bool {
	return len(s) > 0 && len(s) <= max && !strings.ContainsRune(s, '|') && strings.IndexFunc(s, unicode.IsControl) < 0
}
func address(s string, loopback bool) bool {
	host, port, err := net.SplitHostPort(s)
	n, e := strconv.Atoi(port)
	return err == nil && e == nil && n > 0 && n <= 65535 && clean(host, 253) && strings.IndexFunc(host, unicode.IsSpace) < 0 &&
		!strings.ContainsAny(host, "/@\\") && (!loopback || host == "127.0.0.1")
}
func (p Profile) Validate() error {
	invalid := errors.New("invalid private CSQTT 2.5 profile")
	if p.Version != "2.5" || !address(p.Peer, false) || !address(p.Listen, true) || !clean(p.Password, 1024) || !clean(p.DeviceID, 256) {
		return invalid
	}
	if p.ExactWorkers {
		if p.Workers < 3 || p.Workers > 100 {
			return invalid
		}
	} else if p.Workers < 10 || p.Workers > 100 || p.Workers%10 != 0 {
		return invalid
	}
	switch p.HashMode {
	case "manual":
		if len(p.Hashes) == 0 || p.Token != "" {
			return invalid
		}
	case "auto_js":
		if len(p.Hashes) != 0 || p.Token == "" {
			return invalid
		}
	default:
		return invalid
	}
	if !p.ExactWorkers && !p.AllowHashRedistribution && p.HashMode == "manual" && p.Workers > len(p.Hashes)*10 {
		return invalid
	}
	seen := map[string]bool{}
	for _, hash := range p.Hashes {
		if !clean(hash, 4096) || len(hash) < 16 || strings.IndexFunc(hash, unicode.IsSpace) >= 0 || strings.Contains(hash, ",") || seen[hash] {
			return invalid
		}
		seen[hash] = true
	}
	if p.Token != "" && !clean(p.Token, 4096) {
		return invalid
	}
	if p.CaptchaMode != "auto" && p.CaptchaMode != "wv" && p.CaptchaMode != "rjs" {
		return invalid
	}
	if p.VKAuthMode != "vkcalls" && p.VKAuthMode != "legacy" && p.VKAuthMode != "auto_js" {
		return invalid
	}
	if p.VKAuthMode == "auto_js" && p.HashMode != "auto_js" {
		return invalid
	}
	if p.TurnTransport != "udp" && p.TurnTransport != "tcp" {
		return invalid
	}
	if (p.Fingerprint != "" && !clean(p.Fingerprint, 64)) || (p.Obfs != "unified" && p.Obfs != "audio" && p.Obfs != "video") ||
		(p.ClientIDs != "" && !clean(p.ClientIDs, 4096)) || p.Generation < 0 ||
		(p.Salt != "" && !clean(p.Salt, 1024)) || len(p.CustomTurnIPs) > 256 {
		return invalid
	}
	seen = map[string]bool{}
	for _, ip := range p.CustomTurnIPs {
		parsed := net.ParseIP(ip)
		if parsed == nil || parsed.IsUnspecified() || parsed.IsLoopback() || parsed.IsMulticast() || seen[ip] {
			return invalid
		}
		seen[ip] = true
	}
	return nil
}

// The authenticated loopback bridge is host-side, never a native CLI option.
func (p Profile) ValidateBridge() error {
	if err := p.Validate(); err != nil {
		return err
	}
	if !address(p.SOCKS, true) || !clean(p.SOCKSUser, 255) || !clean(p.SOCKSPass, 255) {
		return errors.New("invalid private CSQTT 2.5 loopback bridge")
	}
	return nil
}

// LaunchSpec keeps sensitive native parameters out of error/String logging.
// Unlike the patched legacy binary, the supplied unmodified2.5 has no
// CSQTT_CONFIG_FILE ingress. Its upstream CLI is a separate contract, not a
// reason to replace or relax the legacy private-file adapter.
type LaunchSpec struct {
	args      []string
	bootstrap string
	password  string
}

func (s LaunchSpec) String() string { return "CSQTT25Launch(redacted)" }

func Prepare(p Profile) (LaunchSpec, error) {
	if err := p.Validate(); err != nil {
		return LaunchSpec{}, err
	}
	args := []string{"-peer", p.Peer, "-listen", p.Listen, "-n", strconv.Itoa(p.Workers),
		"-captcha-mode", p.CaptchaMode, "-vk-auth-mode", p.VKAuthMode,
		"-obfs", p.Obfs, "-turn-transport", p.TurnTransport, "-vk-hash-mode", p.HashMode}
	if p.Fingerprint != "" {
		args = append(args, "-fingerprint", p.Fingerprint)
	}
	if p.ClientIDs != "" {
		args = append(args, "-client-ids", p.ClientIDs)
	}
	if p.Generation > 0 {
		args = append(args, "-gen", strconv.Itoa(p.Generation))
	}
	if p.Salt != "" {
		args = append(args, "-salt", p.Salt)
	}
	if p.ExactWorkers {
		args = append(args, "--exact-workers")
	}
	if len(p.Hashes) > 0 {
		args = append(args, "-vk", strings.Join(p.Hashes, ","))
	}
	if len(p.CustomTurnIPs) > 0 {
		args = append(args, "-custom-turn-ips", strings.Join(p.CustomTurnIPs, ","))
	}
	bootstrap := ""
	if p.Token != "" {
		data, _ := json.Marshal(map[string]string{"token": p.Token})
		bootstrap = "VK_JS_BOOTSTRAP:" + base64.StdEncoding.EncodeToString(data) + "\n"
	}
	return LaunchSpec{args: args, bootstrap: bootstrap, password: p.Password}, nil
}
