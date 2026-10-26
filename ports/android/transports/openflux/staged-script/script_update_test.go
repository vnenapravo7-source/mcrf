// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport/script"
)

func TestScriptPrivateRequestAndSecurePaths(t *testing.T) {
	file := filepath.Join(t.TempDir(), "request.json")
	for _, data := range []string{`{"action":"check","secret":"must not be accepted"}`, `{"action":"check"} {}`, strings.Repeat("x", 65537)} {
		os.WriteFile(file, []byte(data), 0600)
		if _, err := readScriptRequest(file); err == nil {
			t.Fatal("invalid request accepted")
		}
	}
	os.WriteFile(file, []byte(`{"action":"capabilities"}`), 0600)
	if _, err := readScriptRequest(file); err != nil {
		t.Fatal(err)
	}
	for _, url := range []string{"http://example.test/index", "https://user:secret@example.test/index", "https://example.test:123/index", "https://example.test/index#secret"} {
		if secureScriptURL(url) {
			t.Fatal("unsafe URL accepted")
		}
	}
	if !secureScriptURL("https://example.test/index") {
		t.Fatal("HTTPS refused")
	}
	dir := t.TempDir()
	for _, name := range []string{"../demo.flux", "demo.flux/other", ".flux", "demo.js"} {
		if safeScriptDirectory(dir, name) == nil {
			t.Fatal("unsafe file accepted")
		}
	}
	if safeScriptDirectory(dir, "demo.flux") != nil {
		t.Fatal("safe file refused")
	}
	if err := os.Symlink(file, filepath.Join(dir, "demo.flux")); err == nil {
		if safeScriptDirectory(dir, "demo.flux") == nil {
			t.Fatal("symlink accepted")
		}
	}
}

func TestSignedScriptLifecycleUsesSignedMetadataAndPreservesRollback(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	_, attacker, _ := ed25519.GenerateKey(rand.Reader)
	dir := t.TempDir()
	old := filepath.Join(dir, "demo.flux")
	source := "https://original.test/update.json"
	manifest := script.PackageManifest{ID: "demo", Name: "demo", Version: "1.0.0", Wire: 1, API: 1, Update: []string{source}}
	if err := script.WritePackage(old, manifest, []byte(`var Transport={};`), nil, priv, true); err != nil {
		t.Fatal(err)
	}
	original, _ := os.ReadFile(old)
	inspected, err := inspectScript(scriptRequest{Directory: dir, File: "demo.flux", PublicKey: hex.EncodeToString(pub)})
	if err != nil || inspected["status"] != "verified" || inspected["id"] != "demo" || inspected["version"] != "1.0.0" || inspected["official"] != false {
		t.Fatalf("signed inspect=%+v err=%v", inspected, err)
	}
	if _, err = inspectScript(scriptRequest{Directory: dir, File: "demo.flux", PublicKey: strings.Repeat("0", 64)}); err == nil {
		t.Fatal("unsigned/unpinned descriptor adopted")
	}
	makePackage := func(version string, wire, api int, key ed25519.PrivateKey) []byte {
		m := manifest
		m.Version = version
		m.Wire = wire
		m.API = api
		path := filepath.Join(t.TempDir(), "new.flux")
		if err := script.WritePackage(path, m, []byte(`var Transport={};`), nil, key, true); err != nil {
			t.Fatal(err)
		}
		value, _ := os.ReadFile(path)
		return value
	}
	request := scriptRequest{Action: "apply", Directory: dir, File: "demo.flux", PublicKey: hex.EncodeToString(pub), Consent: true}
	var payload []byte
	var index []byte
	var hits int
	get := func(ctx context.Context, url string, limit int64) ([]byte, error) {
		hits++
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if url == source {
			return index, nil
		}
		if url == "https://original.test/new.flux" {
			return payload, nil
		}
		return nil, errors.New("unplanned request")
	}
	set := func(version string, wire, api int, key ed25519.PrivateKey, badHash bool) {
		payload = makePackage(version, wire, api, key)
		sum := sha256.Sum256(payload)
		digest := hex.EncodeToString(sum[:])
		if badHash {
			digest = strings.Repeat("0", 64)
		}
		index, _ = json.Marshal(script.UpdateIndex{Format: 1, ID: "demo", Channels: map[string]script.IndexChannel{"stable": {Version: version, Wire: wire, API: api, URL: "new.flux", SHA256: digest}}})
	}
	unchanged := func() {
		value, _ := os.ReadFile(old)
		if string(value) != string(original) {
			t.Fatal("failed/blocked operation changed installed package")
		}
		if _, err := os.Stat(old + ".prev"); !os.IsNotExist(err) {
			t.Fatal("unexpected previous package")
		}
	}
	set("1.1.0", 1, 1, priv, false)
	request.Active = true
	hits = 0
	report, err := performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != "active_deferred" || hits != 0 {
		t.Fatal("active update not deferred")
	}
	unchanged()
	request.Active = false
	request.Consent = false
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != "consent_required" {
		t.Fatal("missing consent accepted")
	}
	unchanged()
	request.Automatic = true
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != "consent_required" {
		t.Fatal("third-party update auto-accepted")
	}
	unchanged()
	request.Automatic = false
	request.Consent = true
	for _, test := range []struct {
		name, version, code string
		wire, api           int
		key                 ed25519.PrivateKey
		badHash             bool
	}{
		{"wrong signature", "1.1.0", script.CodeBadSignature, 1, 1, attacker, false},
		{"wrong digest", "1.1.0", script.CodeBadHash, 1, 1, priv, true},
		{"wire break", "2.0.0", script.CodeWireBreak, 2, 1, priv, false},
		{"new API", "1.1.0", script.CodeNeedsNewerApp, 1, 99, priv, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			set(test.version, test.wire, test.api, test.key, test.badHash)
			report, err := performScriptUpdate(context.Background(), request, get)
			if err != nil || report.Code != test.code {
				t.Fatalf("report=%+v err=%v", report, err)
			}
			unchanged()
		})
	}
	set("1.1.0", 1, 1, priv, false)
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Status != script.UpdateInstalled {
		t.Fatalf("install=%+v err=%v", report, err)
	}
	installed, err := script.LoadSignedPackage(old, pub)
	if err != nil || installed.Manifest.Version != "1.1.0" {
		t.Fatal("new signature/version not verified")
	}
	previous, err := script.LoadSignedPackage(old+".prev", pub)
	if err != nil || previous.Manifest.Version != "1.0.0" {
		t.Fatal("previous not retained")
	}
	request.Action = "rollback"
	wrongID := manifest
	wrongID.ID = "unrelated"
	if err := script.WritePackage(old+".prev", wrongID, []byte(`var Transport={};`), nil, priv, true); err != nil {
		t.Fatal(err)
	}
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != script.CodeIDMismatch {
		t.Fatal("rollback accepted different signed transport ID")
	}
	os.WriteFile(old+".prev", makePackage("1.0.0", 2, 1, priv), 0600)
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != script.CodeWireBreak {
		t.Fatal("rollback wire break not consented")
	}
	os.WriteFile(old+".prev", original, 0600)
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Status != script.UpdateInstalled || report.Latest != "1.0.0" {
		t.Fatalf("rollback=%+v err=%v", report, err)
	}
	restored, err := script.LoadSignedPackage(old, pub)
	if err != nil || restored.Manifest.Version != "1.0.0" {
		t.Fatal("rollback receipt failed")
	}
}

func TestScriptQueueLockAndWrongPinnedKeyFailClosed(t *testing.T) {
	dir := t.TempDir()
	r := scriptRequest{Action: "check", Directory: dir, File: "demo.flux", PublicKey: strings.Repeat("0", 64)}
	get := func(context.Context, string, int64) ([]byte, error) {
		t.Fatal("unverified package triggered network")
		return nil, nil
	}
	lock, err := acquireScriptLock(dir)
	if err != nil {
		t.Fatal(err)
	}
	report, err := performScriptUpdate(context.Background(), r, get)
	if err != nil || report.Code != "update_busy" {
		t.Fatal("concurrent updater not blocked")
	}
	unlockScriptFile(lock)
	lock.Close()
	if _, err := performScriptUpdate(context.Background(), r, get); err == nil {
		t.Fatal("missing/untrusted current script accepted")
	}
	// File existence is no longer ownership; kernel lock is released after failure.
	retry, err := acquireScriptLock(dir)
	if err != nil {
		t.Fatal("lock not released after validation failure")
	}
	unlockScriptFile(retry)
	retry.Close()
}

func TestScriptLockChild(t *testing.T) {
	dir := os.Getenv("MCRF_SCRIPT_LOCK_CHILD")
	if dir == "" {
		return
	}
	lock, err := acquireScriptLock(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	fmt.Println("LOCKED")
	for {
		time.Sleep(time.Hour)
	}
}

func TestScriptKernelLockReleasesAfterKilledProcess(t *testing.T) {
	dir := t.TempDir()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command(executable, "-test.run=^TestScriptLockChild$")
	command.Env = append(os.Environ(), "MCRF_SCRIPT_LOCK_CHILD="+dir)
	out, err := command.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = command.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if command.ProcessState == nil {
			command.Process.Kill()
			command.Wait()
		}
	}()
	ready := make(chan string, 1)
	go func() { line, _ := bufio.NewReader(out).ReadString('\n'); ready <- line }()
	select {
	case line := <-ready:
		if line != "LOCKED\n" {
			t.Fatalf("child did not hold lock: %q", line)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("lock child timeout")
	}
	if second, err := acquireScriptLock(dir); err == nil {
		second.Close()
		t.Fatal("live process lock bypassed")
	}
	if err = command.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	command.Wait()
	second, err := acquireScriptLock(dir)
	if err != nil {
		t.Fatalf("stale lock not released by kernel: %v", err)
	}
	unlockScriptFile(second)
	second.Close()
}

func TestScriptInterruptedRenameRecoveryReverifiesPreviousAndNeverAdoptsNew(t *testing.T) {
	pub, key, _ := ed25519.GenerateKey(rand.Reader)
	_, attacker, _ := ed25519.GenerateKey(rand.Reader)
	dir := t.TempDir()
	current := filepath.Join(dir, "demo.flux")
	request := scriptRequest{Action: "recover", Directory: dir, File: "demo.flux", PublicKey: hex.EncodeToString(pub), Consent: true, ExpectedID: "demo", ExpectedWire: 1}
	get := func(context.Context, string, int64) ([]byte, error) {
		t.Fatal("recovery unexpectedly fetched network")
		return nil, nil
	}
	manifest := script.PackageManifest{ID: "demo", Name: "demo", Version: "1.0.0", API: 1}
	if err := script.WritePackage(current+".prev", manifest, []byte(`var Transport={};`), nil, attacker, true); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(current+".new", []byte("unverified partial download"), 0600)
	report, err := performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != script.CodeBadSignature {
		t.Fatalf("bad-signature recovery=%+v err=%v", report, err)
	}
	if _, err = os.Stat(current); !os.IsNotExist(err) {
		t.Fatal("untrusted previous promoted")
	}
	if err = script.WritePackage(current+".prev", manifest, []byte(`var Transport={};`), nil, key, true); err != nil {
		t.Fatal(err)
	}
	request.ExpectedID = "unrelated"
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != script.CodeIDMismatch {
		t.Fatal("wrong recovery ID accepted")
	}
	request.ExpectedID = "demo"
	request.ExpectedWire = 2
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != script.CodeWireBreak {
		t.Fatal("wrong recovery wire accepted")
	}
	request.ExpectedWire = 1
	request.Consent = false
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != "consent_required" {
		t.Fatal("recovery without consent")
	}
	request.Consent = true
	request.Active = true
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != "active_deferred" {
		t.Fatal("active recovery mutation")
	}
	request.Active = false
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != "interrupted_update_restored" || report.Latest != "1.0.0" {
		t.Fatalf("recovery=%+v err=%v", report, err)
	}
	verified, err := script.LoadSignedPackage(current, pub)
	if err != nil || verified.Manifest.Version != "1.0.0" {
		t.Fatal("recovery did not preserve signature")
	}
	original, _ := os.ReadFile(current)
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != "current_present" {
		t.Fatal("existing current overwritten")
	}
	after, _ := os.ReadFile(current)
	if string(after) != string(original) {
		t.Fatal("idempotent recovery changed current")
	}
	leftover, _ := os.ReadFile(current + ".new")
	if string(leftover) != "unverified partial download" {
		t.Fatal("recovery consumed/modified untrusted .new")
	}
	os.WriteFile(current, []byte("corrupt current"), 0600)
	report, err = performScriptUpdate(context.Background(), request, get)
	if err != nil || report.Code != script.CodeBadSignature {
		t.Fatal("corrupt current reported as verified/up-to-date")
	}
}
