// SPDX-License-Identifier: GPL-3.0-or-later
// Compiled only by exact source-bound isolated script/fresh-core candidates.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"syscall"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport/script"
)

type scriptRequest struct {
	Action         string `json:"action"`
	Directory      string `json:"directory"`
	File           string `json:"file"`
	PublicKey      string `json:"pubkey"`
	Channel        string `json:"channel"`
	Active         bool   `json:"active"`
	Automatic      bool   `json:"automatic"`
	Consent        bool   `json:"consent"`
	AllowWireBreak bool   `json:"allowWireBreak"`
	ExpectedID     string `json:"expectedId"`
	ExpectedWire   int    `json:"expectedWire"`
}

func init() { stagedScriptUpdate = runStagedScriptUpdate }

func readScriptRequest(path string) (scriptRequest, error) {
	var r scriptRequest
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 65536 {
		return r, errors.New("private request required")
	}
	file, err := os.Open(path)
	if err != nil {
		return r, errors.New("private request required")
	}
	defer file.Close()
	decoder := json.NewDecoder(io.LimitReader(file, 65537))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&r) != nil {
		return r, errors.New("bad request")
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return r, errors.New("bad request")
	}
	return r, nil
}

func secureScriptURL(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && u.Scheme == "https" && u.Hostname() != "" && u.User == nil && u.Fragment == "" && (u.Port() == "" || u.Port() == "443")
}

// Strict HTTPS on every hop, finite response, cancellable I/O; no relaxed TLS.
func fetchScript(ctx context.Context, raw string, limit int64) ([]byte, error) {
	if !secureScriptURL(raw) {
		return nil, errors.New("insecure URL")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, raw, nil)
	if err != nil {
		return nil, err
	}
	client := &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 5 || !secureScriptURL(req.URL.String()) {
			return errors.New("unsafe redirect")
		}
		return nil
	}}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return nil, errors.New("fetch failed")
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, limit+1))
	if err != nil || int64(len(data)) > limit {
		return nil, errors.New("response too large")
	}
	return data, nil
}

func safeScriptDirectory(directory, file string) error {
	if !filepath.IsAbs(directory) || !regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\.flux$`).MatchString(file) || filepath.Base(file) != file {
		return errors.New("bad private path")
	}
	// No symlink/reparse traversal of the persistent directory or update targets.
	for current := filepath.Clean(directory); ; current = filepath.Dir(current) {
		info, err := os.Lstat(current)
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("bad private directory")
		}
		if filepath.Dir(current) == current {
			break
		}
	}
	for _, suffix := range []string{"", ".prev", ".new"} {
		info, err := os.Lstat(filepath.Join(directory, file+suffix))
		if os.IsNotExist(err) {
			continue
		}
		if err != nil || !info.Mode().IsRegular() || info.Size() > 4<<20 {
			return errors.New("bad private file")
		}
	}
	return nil
}

// Installed metadata comes from the signed package, never the unsigned update index/request.
func installedScript(r scriptRequest) (script.Installed, error) {
	var result script.Installed
	pub, err := script.DecodePublicKeyHex(r.PublicKey)
	if err != nil {
		return result, errors.New("bad key")
	}
	value, err := script.LoadSignedPackage(filepath.Join(r.Directory, r.File), pub)
	if err != nil {
		return result, errors.New("bad package signature")
	}
	m := value.Manifest
	if m.EffectiveID() == "" || m.EffectiveAPI() > script.APIVersion {
		return result, errors.New("bad installed API")
	}
	for _, source := range m.Update {
		if !secureScriptURL(source) {
			return result, errors.New("insecure signed update source")
		}
	}
	result = script.Installed{ID: m.EffectiveID(), File: r.File, Version: m.Version, Wire: m.EffectiveWire(), PubkeyHex: r.PublicKey, Update: m.Update}
	return result, nil
}

// Rotation may recover a previous package signed by another already-compiled official key.
func signedPrevious(path, key string) (*script.Package, string, error) {
	keys := []string{key}
	if script.IsOfficialKey(key) {
		keys = append(keys, script.OfficialKeys()...)
	}
	for _, trusted := range keys {
		pub, err := script.DecodePublicKeyHex(trusted)
		if err != nil {
			continue
		}
		value, err := script.LoadSignedPackage(path, pub)
		if err == nil {
			return value, trusted, nil
		}
	}
	return nil, "", errors.New("bad previous signature")
}

func acquireScriptLock(directory string) (*os.File, error) {
	path := filepath.Join(directory, ".mcrf-update.lock")
	if info, err := os.Lstat(path); err == nil && !info.Mode().IsRegular() {
		return nil, errors.New("bad lock path")
	} else if err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err = lockScriptFile(file); err != nil {
		file.Close()
		return nil, err
	}
	return file, nil
}

// Inspect signed metadata without executing JS or fetching an unsigned index.
func inspectScript(r scriptRequest) (map[string]any, error) {
	if err := safeScriptDirectory(r.Directory, r.File); err != nil {
		return nil, err
	}
	lock, err := acquireScriptLock(r.Directory)
	if err != nil {
		return nil, errors.New("update busy")
	}
	defer func() { unlockScriptFile(lock); lock.Close() }()
	value, key, err := signedPrevious(filepath.Join(r.Directory, r.File), r.PublicKey)
	if err != nil {
		return nil, err
	}
	m := value.Manifest
	if m.EffectiveID() == "" || len(m.EffectiveID()) > 128 || len(m.Version) > 256 || m.EffectiveAPI() > script.APIVersion {
		return nil, errors.New("bad signed descriptor")
	}
	for _, source := range m.Update {
		if !secureScriptURL(source) {
			return nil, errors.New("insecure signed update source")
		}
	}
	return map[string]any{"status": "verified", "id": m.EffectiveID(), "file": r.File, "version": m.Version, "wire": m.EffectiveWire(), "api": m.EffectiveAPI(), "pubkey": key, "official": script.IsOfficialKey(key)}, nil
}

// Repair only the interrupted rename gap, never overwrite a current package or adopt .new.
func recoverScript(ctx context.Context, r scriptRequest) (script.UpdateReport, error) {
	report := script.UpdateReport{Status: script.UpdateBlocked, Code: "consent_required"}
	if err := ctx.Err(); err != nil { return report, err }
	if !r.Consent {
		return report, nil
	}
	if r.ExpectedID == "" || r.ExpectedWire < 1 {
		return script.UpdateReport{Status: script.UpdateBlocked, Code: "identity_required"}, nil
	}
	current := filepath.Join(r.Directory, r.File)
	pub, err := script.DecodePublicKeyHex(r.PublicKey)
	if err != nil {
		return report, errors.New("bad pinned key")
	}
	if _, err := os.Lstat(current); err == nil {
		value, err := script.LoadSignedPackage(current, pub)
		if err != nil {
			return script.UpdateReport{Status: script.UpdateError, Code: script.CodeBadSignature}, nil
		}
		if value.Manifest.EffectiveID() != r.ExpectedID {
			return script.UpdateReport{Status: script.UpdateBlocked, Code: script.CodeIDMismatch}, nil
		}
		if value.Manifest.EffectiveAPI() > script.APIVersion {
			return script.UpdateReport{Status: script.UpdateBlocked, Code: script.CodeNeedsNewerApp}, nil
		}
		if value.Manifest.EffectiveWire() != r.ExpectedWire && !r.AllowWireBreak {
			return script.UpdateReport{Status: script.UpdateBlocked, Code: script.CodeWireBreak, WireBreak: true}, nil
		}
		return script.UpdateReport{Status: script.UpdateUpToDate, Code: "current_present", Current: value.Manifest.Version}, nil
	} else if !os.IsNotExist(err) {
		return report, err
	}
	previous, recoveredKey, err := signedPrevious(current+".prev", r.PublicKey)
	if err != nil {
		return script.UpdateReport{Status: script.UpdateError, Code: script.CodeBadSignature}, nil
	}
	if previous.Manifest.EffectiveAPI() > script.APIVersion {
		return script.UpdateReport{Status: script.UpdateBlocked, Code: script.CodeNeedsNewerApp}, nil
	}
	if previous.Manifest.EffectiveID() != r.ExpectedID {
		return script.UpdateReport{Status: script.UpdateBlocked, Code: script.CodeIDMismatch}, nil
	}
	if previous.Manifest.EffectiveWire() != r.ExpectedWire && !r.AllowWireBreak {
		return script.UpdateReport{Status: script.UpdateBlocked, Code: script.CodeWireBreak, WireBreak: true}, nil
	}
	if err = ctx.Err(); err != nil { return report, err }
	if err = os.Rename(current+".prev", current); err != nil {
		return script.UpdateReport{Status: script.UpdateError, Code: script.CodeInstallFailed}, nil
	}
	result := script.UpdateReport{Status: script.UpdateInstalled, Code: "interrupted_update_restored", Latest: previous.Manifest.Version}
	if recoveredKey != r.PublicKey {
		result.NewKey = recoveredKey
	}
	return result, nil
}

func performScriptUpdate(ctx context.Context, r scriptRequest, get script.Fetcher) (script.UpdateReport, error) {
	var empty script.UpdateReport
	if err := ctx.Err(); err != nil { return empty, err }
	if r.Action != "check" && r.Action != "apply" && r.Action != "rollback" && r.Action != "recover" {
		return empty, errors.New("bad action")
	}
	if r.Channel != "" && r.Channel != "stable" {
		return empty, errors.New("stable channel required")
	}
	if r.Action != "check" && r.Active {
		return script.UpdateReport{Status: script.UpdateBlocked, Code: "active_deferred"}, nil
	}
	if err := safeScriptDirectory(r.Directory, r.File); err != nil {
		return empty, err
	}
	lock, err := acquireScriptLock(r.Directory)
	if err != nil {
		return script.UpdateReport{Status: script.UpdateBlocked, Code: "update_busy"}, nil
	}
	defer func() { unlockScriptFile(lock); lock.Close() }()
	if err := ctx.Err(); err != nil { return empty, err }
	if r.Action == "recover" {
		return recoverScript(ctx, r)
	}
	inst, err := installedScript(r)
	if err != nil {
		return empty, err
	}

	// Our stricter distribution contract requires an exact stable-channel SHA-256,
	// including when upstream would otherwise accept a signature-only index.
	originalFetch := get
	get = func(ctx context.Context, address string, limit int64) ([]byte, error) {
		if err := ctx.Err(); err != nil { return nil, err }
		if !secureScriptURL(address) {
			return nil, errors.New("insecure URL")
		}
		data, err := originalFetch(ctx, address, limit)
		if err != nil {
			return nil, err
		}
		if err := ctx.Err(); err != nil { return nil, err }
		if int64(len(data)) > limit {
			return nil, errors.New("response too large")
		}
		if limit == 256<<10 {
			var index script.UpdateIndex
			if json.Unmarshal(data, &index) != nil || !regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(index.Channels["stable"].SHA256) {
				return nil, errors.New("stable digest required")
			}
		}
		return data, nil
	}
	if r.Action == "check" {
		return script.CheckUpdate(ctx, inst, "stable", get), nil
	}
	if r.Action == "rollback" {
		if !r.Consent {
			return script.UpdateReport{Status: script.UpdateBlocked, Code: "consent_required"}, nil
		}
		previous, previousKey, err := signedPrevious(filepath.Join(r.Directory, r.File)+".prev", r.PublicKey)
		if err != nil {
			return script.UpdateReport{Status: script.UpdateError, Code: script.CodeBadSignature}, nil
		}
		if previous.Manifest.EffectiveID() != inst.ID {
			return script.UpdateReport{Status: script.UpdateBlocked, Code: script.CodeIDMismatch}, nil
		}
		if previous.Manifest.EffectiveAPI() > script.APIVersion {
			return script.UpdateReport{Status: script.UpdateBlocked, Code: script.CodeNeedsNewerApp}, nil
		}
		if previous.Manifest.EffectiveWire() != inst.Wire && !r.AllowWireBreak {
			return script.UpdateReport{Status: script.UpdateBlocked, Code: script.CodeWireBreak, WireBreak: true}, nil
		}
		if err := ctx.Err(); err != nil { return empty, err }
		result := script.RollbackPackage(r.Directory, r.File, r.PublicKey)
		if result.Status == script.UpdateInstalled && previousKey != r.PublicKey {
			result.NewKey = previousKey
		}
		return result, nil
	}
	report := script.CheckUpdate(ctx, inst, "stable", get)
	if report.Status != script.UpdateAvailable {
		return report, nil
	}
	if r.Automatic {
		if !report.AutoOK || report.WireBreak {
			return script.UpdateReport{Status: script.UpdateBlocked, Code: "consent_required", Current: report.Current, Latest: report.Latest, WireBreak: report.WireBreak}, nil
		}
	} else if !r.Consent {
		return script.UpdateReport{Status: script.UpdateBlocked, Code: "consent_required"}, nil
	}
	// Upstream rechecks and verifies digest/signature/id/version/wire/API immediately before atomic install.
	// Never pass a wire-break permission for an automatic operation.
	if err := ctx.Err(); err != nil { return empty, err }
	return script.ApplyUpdate(ctx, inst, "stable", r.Directory, !r.Automatic && r.Consent && r.AllowWireBreak, get), nil
}

func runStagedScriptUpdate(path string) error {
	request, err := readScriptRequest(path)
	if err != nil {
		return err
	}
	if request.Action == "capabilities" {
		fmt.Println(`MCRF_SCRIPT {"interface":1,"scriptUpdater":1,"channel":"stable","format":".flux","actions":["inspect","check","apply","rollback","recover"]}`)
		return nil
	}
	if request.Action == "inspect" {
		info, err := inspectScript(request)
		if err != nil {
			return err
		}
		data, err := json.Marshal(info)
		if err != nil {
			return err
		}
		fmt.Println("MCRF_SCRIPT " + string(data))
		return nil
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	ctx, cancel := context.WithTimeout(ctx, 3*time.Minute)
	defer cancel()
	// Parent process death closes stdin; cancellation never silently drops the app's durable gate marker.
	go func() { _, _ = io.Copy(io.Discard, os.Stdin); cancel() }()
	report, err := performScriptUpdate(ctx, request, fetchScript)
	if err != nil {
		return err
	}
	data, err := json.Marshal(report)
	if err != nil {
		return err
	}
	fmt.Println("MCRF_SCRIPT " + string(data))
	return nil
}
