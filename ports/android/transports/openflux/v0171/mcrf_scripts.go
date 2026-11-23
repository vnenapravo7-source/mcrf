// SPDX-License-Identifier: GPL-3.0-or-later
package mobile

import (
	"embed"
	"errors"
	"os"
	"path/filepath"
	"sync"

	"github.com/p1neappleXpress/OpenFlux/transport/script"
)

// The producer embeds the exact signed packages from scripts-lock.json.
//
//go:embed mcrf-packages/*
var mcrfPackages embed.FS
var mcrfScriptStorage struct {
	sync.Mutex
	dir   string
	lease *os.File
}

func mcrfKnownScript(name string) bool {
	switch name {
	case "yandex", "vyandex", "boards", "mailru", "cupsonline", "oneme-iceinject", "oneme-webrtc", "mtslink", "bitrix", "pruffme":
		return true
	}
	return false
}
func McrfPrepareScripts(configPath string, c *McrfConfig) error {
	needed := false
	for _, t := range c.Transports {
		if t.Type == "script" {
			needed = true
		}
	}
	if !needed {
		return nil
	}
	pins, err := mcrfScriptPins()
	if err != nil {
		return err
	}
	mcrfScriptStorage.Lock()
	defer mcrfScriptStorage.Unlock()
	if mcrfScriptStorage.dir != "" {
		return errors.New("script storage already active")
	}
	store, lease, err := mcrfScriptLease(configPath)
	if err != nil {
		return err
	}
	leased := false
	defer func() {
		if !leased {
			mcrfReleaseScriptLease(lease)
		}
	}()
	dir, err := os.MkdirTemp(filepath.Dir(configPath), "openflux-scripts-")
	if err != nil {
		return errors.New("private script storage unavailable")
	}
	ok := false
	defer func() {
		if !ok {
			_ = os.RemoveAll(dir)
		}
	}()
	for _, t := range c.Transports {
		if t.Type != "script" {
			continue
		}
		name, _ := t.Params["name"].(string)
		pin, exists := pins[name]
		if !mcrfKnownScript(name) || !exists {
			return errors.New("official script package pending publication")
		}
		raw, _, err := mcrfCurrentScript(store, pin)
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(dir, name+".flux"), raw, 0600); err != nil {
			return errors.New("cannot stage official script")
		}
	}
	mcrfScriptStorage.dir = dir
	mcrfScriptStorage.lease = lease
	leased = true
	ok = true
	return nil
}
func mcrfScriptParams(params map[string]interface{}) (map[string]interface{}, error) {
	name, _ := params["name"].(string)
	mcrfScriptStorage.Lock()
	defer mcrfScriptStorage.Unlock()
	if !mcrfKnownScript(name) || mcrfScriptStorage.dir == "" {
		return nil, errors.New("official script unavailable")
	}
	path := filepath.Join(mcrfScriptStorage.dir, name+".flux")
	if _, err := os.Stat(path); err != nil {
		return nil, errors.New("official script package pending publication")
	}
	out := map[string]interface{}{}
	for k, v := range params {
		out[k] = v
	}
	out["path"] = path
	out["pubkey"] = script.OfficialKeyHex
	out["name"] = name
	return out, nil
}
func McrfCleanupScripts() {
	mcrfScriptStorage.Lock()
	defer mcrfScriptStorage.Unlock()
	if mcrfScriptStorage.dir != "" {
		_ = os.RemoveAll(mcrfScriptStorage.dir)
		mcrfScriptStorage.dir = ""
	}
	mcrfReleaseScriptLease(mcrfScriptStorage.lease)
	mcrfScriptStorage.lease = nil
}
