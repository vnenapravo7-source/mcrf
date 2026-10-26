// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "context"
    "crypto/ed25519"
    "crypto/rand"
    "encoding/hex"
    "errors"
    "os"
    "path/filepath"
    "testing"

    "github.com/p1neappleXpress/OpenFlux/transport/script"
)

func freshScriptFixture(t *testing.T, current bool) (scriptRequest, string) {
    t.Helper()
    pub, key, err := ed25519.GenerateKey(rand.Reader)
    if err != nil { t.Fatal(err) }
    directory := t.TempDir()
    path := filepath.Join(directory, "demo.flux")
    manifest := script.PackageManifest{ID: "demo", Name: "demo", Version: "1.0.0", API: 1, Wire: 1}
    if err := script.WritePackage(path+".prev", manifest, []byte(`var Transport={};`), nil, key, true); err != nil { t.Fatal(err) }
    if current {
        manifest.Version = "2.0.0"
        if err := script.WritePackage(path, manifest, []byte(`var Transport={};`), nil, key, true); err != nil { t.Fatal(err) }
    }
    return scriptRequest{Action: "recover", Directory: directory, File: "demo.flux", PublicKey: hex.EncodeToString(pub), Consent: true, ExpectedID: "demo", ExpectedWire: 1}, path
}

func noFreshScriptFetch(t *testing.T) script.Fetcher {
    return func(context.Context, string, int64) ([]byte, error) { t.Fatal("local operation fetched network"); return nil, nil }
}

func TestMcrfFreshCancelledRecoveryDoesNotPromotePrevious(t *testing.T) {
    request, path := freshScriptFixture(t, false)
    previous, err := os.ReadFile(path+".prev"); if err != nil { t.Fatal(err) }
    ctx, cancel := context.WithCancel(context.Background()); cancel()
    _, err = performScriptUpdate(ctx, request, noFreshScriptFetch(t))
    if !errors.Is(err, context.Canceled) { t.Fatal("cancel not preserved") }
    if _, err = os.Stat(path); !os.IsNotExist(err) { t.Fatal("cancelled recovery promoted previous") }
    after, err := os.ReadFile(path+".prev")
    if err != nil || string(after) != string(previous) { t.Fatal("cancelled recovery changed previous") }
}

func TestMcrfFreshCancelledRollbackDoesNotSwapFiles(t *testing.T) {
    request, path := freshScriptFixture(t, true); request.Action = "rollback"
    before, err := os.ReadFile(path); if err != nil { t.Fatal(err) }
    previous, err := os.ReadFile(path+".prev"); if err != nil { t.Fatal(err) }
    ctx, cancel := context.WithCancel(context.Background()); cancel()
    _, err = performScriptUpdate(ctx, request, noFreshScriptFetch(t))
    if !errors.Is(err, context.Canceled) { t.Fatal("cancel not preserved") }
    after, err := os.ReadFile(path)
    if err != nil || string(before) != string(after) { t.Fatal("cancelled rollback changed current") }
    after, err = os.ReadFile(path+".prev")
    if err != nil || string(previous) != string(after) { t.Fatal("cancelled rollback changed previous") }
}

func TestMcrfFreshCurrentRecoveryChecksExpectedWire(t *testing.T) {
    request, path := freshScriptFixture(t, true); request.ExpectedWire = 2
    before, err := os.ReadFile(path); if err != nil { t.Fatal(err) }
    report, err := performScriptUpdate(context.Background(), request, noFreshScriptFetch(t))
    if err != nil || report.Status != script.UpdateBlocked || report.Code != script.CodeWireBreak { t.Fatal("incompatible current reported as ready") }
    after, err := os.ReadFile(path)
    if err != nil || string(before) != string(after) { t.Fatal("wire check changed current") }
}
