// SPDX-License-Identifier: GPL-3.0-or-later
package mobile

import (
	"context"
	"crypto/ed25519"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/p1neappleXpress/OpenFlux/transport/script"
)

func TestMcrf0171PinnedScriptIdentityAndSignature(t *testing.T) {
	pins, err := mcrfScriptPins()
	if err != nil {
		t.Fatal(err)
	}
	for _, pin := range pins {
		raw, selection, err := mcrfEmbeddedScript(pin)
		if err != nil {
			t.Fatal(pin.ID, err)
		}
		for _, mutate := range []func(*mcrfScriptPin, *mcrfScriptSelection){
			func(_ *mcrfScriptPin, s *mcrfScriptSelection) { s.SHA = "0" + s.SHA[1:] },
			func(_ *mcrfScriptPin, s *mcrfScriptSelection) { s.ID = "another" },
			func(_ *mcrfScriptPin, s *mcrfScriptSelection) { s.Version = "999.0.0" },
			func(p *mcrfScriptPin, _ *mcrfScriptSelection) { p.Wire++ },
			func(p *mcrfScriptPin, _ *mcrfScriptSelection) { p.API++ },
		} {
			p, s := pin, selection
			mutate(&p, &s)
			if _, err := mcrfVerifyScript(raw, p, s); err == nil {
				t.Fatal("metadata mismatch accepted", pin.ID)
			}
		}
		// Correct digest and manifest do not authorize a foreign signing key.
		pkg, err := script.ReadPackage(raw)
		if err != nil {
			t.Fatal(err)
		}
		_, foreign, err := ed25519.GenerateKey(nil)
		if err != nil {
			t.Fatal(err)
		}
		path := filepath.Join(t.TempDir(), "foreign.flux")
		if err := script.WritePackage(path, pkg.Manifest, pkg.Script, pkg.Icon, foreign, true); err != nil {
			t.Fatal(err)
		}
		other, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		selection.SHA = mcrfScriptHash(other)
		if _, err := mcrfVerifyScript(other, pin, selection); err == nil {
			t.Fatal("foreign signature accepted")
		}
	}
	for _, id := range []string{"mtslink-webrtc", "vkteams", "../yandex"} {
		if mcrfKnownScript(id) {
			t.Fatal("unpublished script accepted")
		}
	}
}

func TestMcrf0171ScriptSelectionIsPinnedAndLeaseSurvivesOwner(t *testing.T) {
	pins, err := mcrfScriptPins()
	if err != nil {
		t.Fatal(err)
	}
	pin := pins["yandex"]
	path := filepath.Join(t.TempDir(), "profile.json")
	dir, lease, err := mcrfScriptLease(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, second, err := mcrfScriptLease(path); err == nil {
		mcrfReleaseScriptLease(second)
		t.Fatal("concurrent updater admitted")
	}
	raw, selected, err := mcrfEmbeddedScript(pin)
	if err != nil {
		t.Fatal(err)
	}
	if err := mcrfInstallScript(dir, pin, raw, selected, selected); err != nil {
		t.Fatal(err)
	}
	if _, got, err := mcrfCurrentScript(dir, pin); err != nil || got != selected {
		t.Fatal("installed pin lost", err)
	}
	// Simulate corrupt selection; never silently downgrade to the embedded copy.
	bad, _ := json.Marshal(mcrfScriptSelection{ID: pin.ID, Version: pin.Version, SHA: "0000000000000000000000000000000000000000000000000000000000000000"})
	if err := mcrfAtomicScriptWrite(filepath.Join(dir, pin.ID+".json"), bad); err != nil {
		t.Fatal(err)
	}
	if _, _, err := mcrfCurrentScript(dir, pin); err == nil {
		t.Fatal("corrupt selected pin ignored")
	}
	if _, got, err := mcrfSelectedScript(dir, pin, pin.ID+".prev.json"); err != nil || got != selected {
		t.Fatal("previous verified pin lost", err)
	}
	mcrfReleaseScriptLease(lease)
	_, next, err := mcrfScriptLease(path)
	if err != nil {
		t.Fatal("lease not released", err)
	}
	mcrfReleaseScriptLease(next)
}

func TestMcrf0171UpdateNeverTrustsAnArbitraryLatestURL(t *testing.T) {
	for _, address := range []string{
		"http://github.com/p1neappleXpress/OpenFluxTransports/releases/download/x/a.flux",
		"https://github.com.evil.example/p1neappleXpress/OpenFluxTransports/releases/download/x/a.flux",
		"https://github.com/another/OpenFluxTransports/releases/download/x/a.flux",
		"https://raw.githubusercontent.com/another/repo/main/update.json",
		"https://github.com/p1neappleXpress/OpenFlux/releases/latest",
		"https://raw.githubusercontent.com/p1neappleXpress/OpenFluxTransports/../another/main/update.json",
	} {
		if mcrfOfficialScriptURL(address) {
			t.Fatal("nonofficial source accepted")
		}
	}
	for _, version := range []string{"latest", "1.0.0-beta", "01.0.0", "999999999999999999999.0.0", "2.999999999999999999999.0"} {
		if _, err := mcrfCompareVersions(version, "1.0.0"); err == nil {
			t.Fatal("unstable version accepted")
		}
	}
	if _, err := McrfUpdateOfficialScript(context.Background(), filepath.Join(t.TempDir(), "request.json"), "apply", "yandex", ""); err == nil {
		t.Fatal("unpinned apply admitted")
	}
}

func TestMcrf0171OfficialInventoryIsOfflineAndVerified(t *testing.T) {
	path := filepath.Join(t.TempDir(), "request.json")
	receipt, err := McrfInspectOfficialScripts(context.Background(), path)
	if err != nil || receipt.Status != "verified_inventory" || receipt.Updater != 2 || receipt.CLI != "official-pinned-v1" || receipt.Core != mcrfCorePin || receipt.RuntimeAccepted || len(receipt.Packages) != 10 {
		t.Fatal("offline inventory rejected", err)
	}
	for _, p := range receipt.Packages {
		if !p.RuntimePreflight || p.RuntimeAccepted || p.SHA != p.CurrentSHA || p.Version != p.CurrentVersion || p.Wire != 1 || p.API != 1 {
			t.Fatal("incomplete verified receipt")
		}
	}
	dir, lease, err := mcrfScriptLease(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := McrfInspectOfficialScripts(context.Background(), path); err == nil {
		t.Fatal("inventory bypassed active native lease")
	}
	mcrfReleaseScriptLease(lease)
	// A correct-looking checkpoint cannot authorize missing/unverified bytes.
	bad, _ := json.Marshal(mcrfScriptSelection{ID: "yandex", Version: "1.1.0", SHA: "0000000000000000000000000000000000000000000000000000000000000000"})
	if err := mcrfAtomicScriptWrite(filepath.Join(dir, "yandex.json"), bad); err != nil {
		t.Fatal(err)
	}
	if _, err := McrfInspectOfficialScripts(context.Background(), path); err == nil {
		t.Fatal("unverified inventory cleared for recovery")
	}
}
