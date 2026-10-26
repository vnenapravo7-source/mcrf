// SPDX-License-Identifier: GPL-3.0-or-later
package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"unicode"
)

type McrfSettings struct {
	Domains             []string `json:"domains"`
	Resolvers           []string `json:"resolvers"`
	EncryptionKey       string   `json:"encryption_key"`
	EncryptionMethod    *int     `json:"encryption_method"`
	BaseEncodeData      bool     `json:"base_encode_data"`
	UploadCompression   int      `json:"upload_compression"`
	DownloadCompression int      `json:"download_compression"`
	Workers             *int     `json:"workers"`
	SOCKS               string   `json:"socks"`
	User                string   `json:"socks_user"`
	Password            string   `json:"socks_pass"`
}

func McrfRead(reader io.Reader) (McrfSettings, error) {
	var p McrfSettings
	data, err := io.ReadAll(io.LimitReader(reader, 65537))
	if err != nil || len(data) > 65536 {
		return p, errors.New("MASTERDNS_CONFIG_SIZE")
	}
	shape := json.NewDecoder(bytes.NewReader(data))
	start, err := shape.Token()
	if err != nil || start != json.Delim('{') {
		return p, errors.New("MASTERDNS_CONFIG_OBJECT")
	}
	seen := map[string]bool{}
	allowed := map[string]bool{"domains": true, "resolvers": true, "encryption_key": true, "encryption_method": true, "base_encode_data": true, "upload_compression": true, "download_compression": true, "workers": true, "socks": true, "socks_user": true, "socks_pass": true}
	for shape.More() {
		t, e := shape.Token()
		name, ok := t.(string)
		if e != nil || !ok || seen[name] || !allowed[name] {
			return p, errors.New("MASTERDNS_CONFIG_DUPLICATE")
		}
		seen[name] = true
		var value json.RawMessage
		if shape.Decode(&value) != nil || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return p, errors.New("MASTERDNS_CONFIG_VALUE")
		}
	}
	if _, err = shape.Token(); err != nil {
		return p, errors.New("MASTERDNS_CONFIG_OBJECT")
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if d.Decode(&p) != nil {
		return p, errors.New("MASTERDNS_CONFIG_FORMAT")
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return p, errors.New("MASTERDNS_CONFIG_TRAILING")
	}
	if len(p.Domains) == 0 || len(p.Resolvers) == 0 {
		return p, errors.New("MASTERDNS_ENDPOINTS_REQUIRED")
	}
	for _, domain := range p.Domains {
		if len(domain) > 253 || !strings.Contains(domain, ".") {
			return p, errors.New("MASTERDNS_DOMAIN")
		}
		for _, label := range strings.Split(domain, ".") {
			if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
				return p, errors.New("MASTERDNS_DOMAIN")
			}
			for _, ch := range label {
				if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-') {
					return p, errors.New("MASTERDNS_DOMAIN")
				}
			}
		}
	}
	for _, address := range p.Resolvers {
		host, port, e := net.SplitHostPort(address)
		n, pe := strconv.Atoi(port)
		if e != nil || pe != nil || net.ParseIP(host) == nil || n < 1 || n > 65535 {
			return p, errors.New("MASTERDNS_RESOLVER")
		}
	}
	if p.EncryptionMethod == nil || *p.EncryptionMethod < 0 || *p.EncryptionMethod > 5 || len(p.EncryptionKey) == 0 || len(p.EncryptionKey) > 4096 || strings.IndexFunc(p.EncryptionKey, unicode.IsControl) >= 0 {
		return p, errors.New("MASTERDNS_ENCRYPTION")
	}
	if p.UploadCompression < 0 || p.UploadCompression > 3 || p.DownloadCompression < 0 || p.DownloadCompression > 3 {
		return p, errors.New("MASTERDNS_COMPRESSION")
	}
	if p.Workers == nil {
		workers := 2
		p.Workers = &workers
	}
	if *p.Workers < 1 || *p.Workers > 8 {
		return p, errors.New("MASTERDNS_WORKERS")
	}
	h, port, e := net.SplitHostPort(p.SOCKS)
	n, pe := strconv.Atoi(port)
	if e != nil || pe != nil || h != "127.0.0.1" || n < 1 || n > 65535 || len(p.User) == 0 || len(p.Password) == 0 || len(p.User) > 255 || len(p.Password) > 255 {
		return p, errors.New("MASTERDNS_LOCAL_AUTH")
	}
	return p, nil
}

// The app controls the private directory and local endpoint. Imported profiles
// cannot select original CLI file paths, logging, DNS listeners or persistence.
func McrfConfig(p McrfSettings, privateDirectory string) (ClientConfig, error) {
	cfg := defaultClientConfig()
	data, err := json.Marshal(p)
	if err != nil {
		return cfg, errors.New("MASTERDNS_CONFIG_FORMAT")
	}
	p, err = McrfRead(bytes.NewReader(data))
	if err != nil {
		return cfg, err
	}
	info, err := os.Lstat(privateDirectory)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return cfg, errors.New("MASTERDNS_PRIVATE_DIRECTORY")
	}
	// Use an exclusive random file, never overwrite a supplied resolver path.
	file, err := os.CreateTemp(privateDirectory, "resolvers-")
	if err != nil {
		return cfg, errors.New("MASTERDNS_PRIVATE_RESOLVERS")
	}
	defer file.Close()
	if file.Chmod(0600) != nil {
		return cfg, errors.New("MASTERDNS_PRIVATE_RESOLVERS")
	}
	if _, err = io.WriteString(file, strings.Join(p.Resolvers, "\n")+"\n"); err != nil {
		return cfg, errors.New("MASTERDNS_PRIVATE_RESOLVERS")
	}
	if err = file.Close(); err != nil {
		return cfg, errors.New("MASTERDNS_PRIVATE_RESOLVERS")
	}
	// The owning app-private process directory is removed on retirement.
	cfg.ConfigDir = privateDirectory
	cfg.ConfigPath = "<mcrf-private>"
	cfg.ResolversFilePath = filepath.Clean(file.Name())
	cfg.ProtocolType = "SOCKS5"
	cfg.ListenIP = "127.0.0.1"
	_, port, _ := net.SplitHostPort(p.SOCKS)
	cfg.ListenPort, _ = strconv.Atoi(port)
	cfg.SOCKS5Auth = true
	cfg.SOCKS5User = p.User
	cfg.SOCKS5Pass = p.Password
	cfg.Domains = append([]string(nil), p.Domains...)
	cfg.DataEncryptionMethod = *p.EncryptionMethod
	cfg.EncryptionKey = p.EncryptionKey
	cfg.BaseEncodeData = p.BaseEncodeData
	cfg.UploadCompressionType = p.UploadCompression
	cfg.DownloadCompressionType = p.DownloadCompression
	cfg.RX_TX_Workers = *p.Workers
	cfg.TunnelProcessWorkers = *p.Workers
	cfg.explicitRX_TX_Workers = true
	cfg.explicitTunnelProcessWorkers = true
	cfg.LocalDNSEnabled = false
	cfg.LocalDNSCachePersist = false
	cfg.SaveMTUServersToFile = false
	cfg.LogLevel = "ERROR"
	return finalizeClientConfig(cfg)
}
