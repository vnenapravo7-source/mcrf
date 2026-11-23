// SPDX-License-Identifier: GPL-3.0-or-later
package mobile

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport/script"
)

const mcrfPackageLimit = 4 << 20
const mcrfCorePin = "f492a1a33b7581ad8f88118f97e0b866adda3cdc"
const mcrfTransportRepo = "p1neappleXpress/OpenFluxTransports"

var mcrfDigestPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
var mcrfVersionPattern = regexp.MustCompile(`^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$`)

type mcrfScriptPin struct {
	ID      string `json:"id"`
	Version string `json:"version"`
	Wire    int    `json:"wire"`
	API     int    `json:"api"`
	SHA     string `json:"sha256"`
}

// This checkpoint pins bytes already verified under the module's author key.
// It never supplies a key, URL, ABI, API permission or provider acceptance.
type mcrfScriptSelection struct {
	ID      string `json:"id"`
	Version string `json:"version"`
	SHA     string `json:"sha256"`
}

type McrfScriptUpdateReport struct {
	Status           string `json:"status"`
	ID               string `json:"id"`
	Version          string `json:"version"`
	SHA              string `json:"sha256"`
	Core             string `json:"coreCommit"`
	ABI              string `json:"abi"`
	RuntimePreflight bool   `json:"runtimePreflight"`
	RuntimeAccepted  bool   `json:"runtimeAccepted"`
	Wire             int    `json:"wire"`
	API              int    `json:"api"`
	CurrentSHA       string `json:"currentSha256"`
	CurrentVersion   string `json:"currentVersion"`
	PreviousSHA      string `json:"previousSha256,omitempty"`
	PreviousVersion  string `json:"previousVersion,omitempty"`
}

type McrfScriptInventory struct {
	Status          string                   `json:"status"`
	Interface       int                      `json:"interface"`
	Updater         int                      `json:"scriptUpdater"`
	CLI             string                   `json:"cli"`
	Core            string                   `json:"coreCommit"`
	ABI             string                   `json:"abi"`
	Packages        []McrfScriptUpdateReport `json:"packages"`
	RuntimeAccepted bool                     `json:"runtimeAccepted"`
}

// Offline recovery receipt: hold the same kernel lease as live sessions and
// verify every selected/current/previous package, including its VM contract.
func McrfInspectOfficialScripts(ctx context.Context, requestPath string) (McrfScriptInventory, error) {
	result := McrfScriptInventory{Status: "verified_inventory", Interface: 1, Updater: 2, CLI: "official-pinned-v1", Core: mcrfCorePin, ABI: mcrfRuntimeABI()}
	pins, err := mcrfScriptPins()
	if err != nil {
		return result, err
	}
	dir, lease, err := mcrfScriptLease(requestPath)
	if err != nil {
		return result, err
	}
	defer mcrfReleaseScriptLease(lease)
	for _, id := range []string{"yandex", "vyandex", "boards", "mailru", "cupsonline", "oneme-iceinject", "oneme-webrtc", "mtslink", "bitrix", "pruffme"} {
		if err := ctx.Err(); err != nil {
			return result, err
		}
		pin := pins[id]
		_, current, err := mcrfCurrentScript(dir, pin)
		if err != nil {
			return result, err
		}
		r := McrfScriptUpdateReport{Status: "verified_current", ID: id, Version: current.Version, SHA: current.SHA, CurrentSHA: current.SHA, CurrentVersion: current.Version, Core: mcrfCorePin, ABI: result.ABI, Wire: pin.Wire, API: pin.API, RuntimePreflight: true}
		if _, err := os.Lstat(filepath.Join(dir, id+".prev.json")); !os.IsNotExist(err) {
			_, previous, err := mcrfSelectedScript(dir, pin, id+".prev.json")
			if err != nil {
				return result, err
			}
			r.PreviousSHA, r.PreviousVersion = previous.SHA, previous.Version
		}
		result.Packages = append(result.Packages, r)
	}
	return result, nil
}

func mcrfScriptPins() (map[string]mcrfScriptPin, error) {
	raw, err := mcrfPackages.ReadFile("mcrf-packages/manifest.json")
	if err != nil {
		return nil, errors.New("official script manifest missing")
	}
	var manifest struct {
		Repository  string          `json:"repository"`
		OfficialKey string          `json:"officialKey"`
		Packages    []mcrfScriptPin `json:"packages"`
	}
	if json.Unmarshal(raw, &manifest) != nil || manifest.Repository != mcrfTransportRepo || manifest.OfficialKey != script.OfficialKeyHex || len(manifest.Packages) != 10 {
		return nil, errors.New("official script manifest rejected")
	}
	out := map[string]mcrfScriptPin{}
	for _, p := range manifest.Packages {
		if !mcrfKnownScript(p.ID) || out[p.ID].ID != "" || !mcrfDigestPattern.MatchString(p.SHA) || !mcrfVersionPattern.MatchString(p.Version) || p.Wire < 1 || p.API < 1 || p.API > script.APIVersion {
			return nil, errors.New("official script pin rejected")
		}
		out[p.ID] = p
	}
	return out, nil
}

func mcrfScriptHash(raw []byte) string {
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

func mcrfCompareVersions(a, b string) (int, error) {
	if !mcrfVersionPattern.MatchString(a) || !mcrfVersionPattern.MatchString(b) {
		return 0, errors.New("stable numeric version required")
	}
	x, y := strings.Split(a, "."), strings.Split(b, ".")
	var left, right [3]uint64
	for i := range x {
		u, e1 := strconv.ParseUint(x[i], 10, 32)
		v, e2 := strconv.ParseUint(y[i], 10, 32)
		if e1 != nil || e2 != nil {
			return 0, errors.New("version overflow")
		}
		left[i], right[i] = u, v
	}
	for i := range left {
		if left[i] < right[i] {
			return -1, nil
		}
		if left[i] > right[i] {
			return 1, nil
		}
	}
	return 0, nil
}

// Signature BEFORE evaluating info(). Inspect denies network and limits VM time.
// This preflight is deliberately not a provider/open/send runtime attestation.
func mcrfVerifyScript(raw []byte, pin mcrfScriptPin, selection mcrfScriptSelection) (*script.RawPackage, error) {
	if len(raw) > mcrfPackageLimit || !mcrfDigestPattern.MatchString(selection.SHA) || mcrfScriptHash(raw) != selection.SHA || selection.ID != pin.ID {
		return nil, errors.New("official script digest rejected")
	}
	pkg, err := script.ReadPackage(raw)
	if err != nil {
		return nil, errors.New("official script archive rejected")
	}
	key, err := script.DecodePublicKeyHex(script.OfficialKeyHex)
	if err != nil || pkg.Verify(key) != nil {
		return nil, errors.New("official script signature rejected")
	}
	m := pkg.Manifest
	if m.Name == "" || (m.Icon != "" && pkg.Icon == nil) {
		return nil, errors.New("official script manifest incomplete")
	}
	order, err := mcrfCompareVersions(m.Version, pin.Version)
	if err != nil || order < 0 || m.EffectiveID() != pin.ID || m.Version != selection.Version || m.EffectiveWire() != pin.Wire || m.EffectiveAPI() != pin.API || m.EffectiveAPI() > script.APIVersion {
		return nil, errors.New("official script identity/wire/API rejected")
	}
	// Validate the functions the real host calls without calling open/write.
	// The signature covers the original source; this fixed host-owned suffix
	// is evaluated only inside upstream's bounded, I/O-denying inspection VM.
	preflight := append([]byte(nil), pkg.Script...)
	preflight = append(preflight, []byte("\n;if(typeof Transport.open!=='function'||typeof Transport.write!=='function')throw new Error('transport contract missing');")...)
	info, err := script.Inspect(preflight)
	if err != nil || info.Version != m.Version {
		return nil, errors.New("official script runtime preflight rejected")
	}
	return pkg, nil
}

func mcrfPrivateDirectory(dir string) error {
	if !filepath.IsAbs(dir) {
		return errors.New("absolute private directory required")
	}
	for p := filepath.Clean(dir); ; p = filepath.Dir(p) {
		info, err := os.Lstat(p)
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("private directory rejected")
		}
		if filepath.Dir(p) == p {
			return nil
		}
	}
}

func mcrfReadScriptFile(path string, limit int64) ([]byte, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() > limit {
		return nil, errors.New("private script file rejected")
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	opened, err := f.Stat()
	if err != nil || !os.SameFile(info, opened) {
		return nil, errors.New("private script file changed")
	}
	raw, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err != nil || int64(len(raw)) > limit {
		return nil, errors.New("script file too large")
	}
	return raw, nil
}

func mcrfScriptLease(configPath string) (string, *os.File, error) {
	parent := filepath.Dir(configPath)
	if err := mcrfPrivateDirectory(parent); err != nil {
		return "", nil, err
	}
	if runtime.GOOS == "android" {
		// NativeCarrier creates cache/mcrf-carrier-<UUID>/config.json and
		// deletes that session directory on stop. Anchor updates/leases in
		// the owning app's no_backup, never in the disposable session cache.
		cache := filepath.Dir(parent)
		if filepath.Base(cache) != "cache" || !regexp.MustCompile(`^mcrf-carrier-[0-9a-fA-F-]{36}$`).MatchString(filepath.Base(parent)) {
			return "", nil, errors.New("private Android carrier request path required")
		}
		parent = filepath.Join(filepath.Dir(cache), "no_backup")
		if err := os.Mkdir(parent, 0700); err != nil && !os.IsExist(err) {
			return "", nil, err
		}
		if err := mcrfPrivateDirectory(parent); err != nil {
			return "", nil, err
		}
	}
	dir := filepath.Join(parent, "openflux-script-packages")
	if err := os.Mkdir(dir, 0700); err != nil && !os.IsExist(err) {
		return "", nil, err
	}
	if err := mcrfPrivateDirectory(dir); err != nil {
		return "", nil, err
	}
	path := filepath.Join(dir, ".lease")
	if info, err := os.Lstat(path); err == nil && !info.Mode().IsRegular() {
		return "", nil, errors.New("script lease rejected")
	} else if err != nil && !os.IsNotExist(err) {
		return "", nil, err
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return "", nil, err
	}
	opened, statErr := f.Stat()
	named, nameErr := os.Lstat(path)
	if statErr != nil || nameErr != nil || !named.Mode().IsRegular() || !os.SameFile(opened, named) {
		f.Close()
		return "", nil, errors.New("script lease changed")
	}
	if err := mcrfLockScriptFile(f); err != nil {
		f.Close()
		return "", nil, errors.New("script session/update busy")
	}
	return dir, f, nil
}

func mcrfReleaseScriptLease(f *os.File) {
	if f != nil {
		mcrfUnlockScriptFile(f)
		_ = f.Close()
	}
}

func mcrfEmbeddedScript(pin mcrfScriptPin) ([]byte, mcrfScriptSelection, error) {
	s := mcrfScriptSelection{ID: pin.ID, Version: pin.Version, SHA: pin.SHA}
	raw, err := mcrfPackages.ReadFile("mcrf-packages/" + pin.ID + ".flux")
	if err == nil {
		_, err = mcrfVerifyScript(raw, pin, s)
	}
	return raw, s, err
}

func mcrfSelectedScript(dir string, pin mcrfScriptPin, checkpoint string) ([]byte, mcrfScriptSelection, error) {
	var s mcrfScriptSelection
	raw, err := mcrfReadScriptFile(filepath.Join(dir, checkpoint), 4096)
	if err != nil {
		return nil, s, err
	}
	if json.Unmarshal(raw, &s) != nil || s.ID != pin.ID || !mcrfDigestPattern.MatchString(s.SHA) {
		return nil, s, errors.New("script checkpoint rejected")
	}
	data, err := mcrfReadScriptFile(filepath.Join(dir, pin.ID+"-"+s.SHA+".flux"), mcrfPackageLimit)
	if err == nil {
		_, err = mcrfVerifyScript(data, pin, s)
	}
	return data, s, err
}

func mcrfCurrentScript(dir string, pin mcrfScriptPin) ([]byte, mcrfScriptSelection, error) {
	if _, err := os.Lstat(filepath.Join(dir, pin.ID+".json")); os.IsNotExist(err) {
		return mcrfEmbeddedScript(pin)
	}
	// A corrupt selected update is an error, never a silent downgrade.
	return mcrfSelectedScript(dir, pin, pin.ID+".json")
}

func mcrfAtomicScriptWrite(path string, raw []byte) error {
	if info, err := os.Lstat(path); err == nil && !info.Mode().IsRegular() {
		return errors.New("script target rejected")
	} else if err != nil && !os.IsNotExist(err) {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".script-new-")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	if _, err = f.Write(raw); err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = os.Rename(name, path); err != nil {
		return err
	}
	// Android requires a durable directory entry as well as durable file bytes.
	if runtime.GOOS != "windows" {
		d, err := os.Open(filepath.Dir(path))
		if err != nil {
			return err
		}
		defer d.Close()
		return d.Sync()
	}
	return nil
}

// Pin content first, then atomically select it; a crash cannot select a partial
// package. Previous checkpoint and immutable bytes remain for explicit rollback.
func mcrfInstallScript(dir string, pin mcrfScriptPin, raw []byte, next, previous mcrfScriptSelection) error {
	if _, err := mcrfVerifyScript(raw, pin, next); err != nil {
		return err
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	var total int64
	for _, e := range entries {
		info, err := e.Info()
		if err != nil {
			return err
		}
		total += info.Size()
	}
	if total+int64(len(raw)) > 64<<20 {
		return errors.New("script store limit reached")
	}
	if err := mcrfAtomicScriptWrite(filepath.Join(dir, pin.ID+"-"+next.SHA+".flux"), raw); err != nil {
		return err
	}
	if previous.SHA == pin.SHA {
		old, _, err := mcrfEmbeddedScript(pin)
		if err != nil {
			return err
		}
		if err = mcrfAtomicScriptWrite(filepath.Join(dir, pin.ID+"-"+previous.SHA+".flux"), old); err != nil {
			return err
		}
	}
	prev, _ := json.Marshal(previous)
	selected, _ := json.Marshal(next)
	if err := mcrfAtomicScriptWrite(filepath.Join(dir, pin.ID+".prev.json"), prev); err != nil {
		return err
	}
	return mcrfAtomicScriptWrite(filepath.Join(dir, pin.ID+".json"), selected)
}

func mcrfOfficialScriptURL(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.User != nil || u.Fragment != "" || u.RawQuery != "" || u.RawPath != "" || u.Port() != "" || path.Clean(u.Path) != u.Path || strings.Contains(u.EscapedPath(), "%") {
		return false
	}
	if u.Host == "raw.githubusercontent.com" {
		return strings.HasPrefix(u.Path, "/"+mcrfTransportRepo+"/")
	}
	return u.Host == "github.com" && strings.HasPrefix(u.Path, "/"+mcrfTransportRepo+"/releases/download/")
}

func mcrfFetchScript(ctx context.Context, address string, limit int64) ([]byte, error) {
	if !mcrfOfficialScriptURL(address) {
		return nil, errors.New("official HTTPS source required")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return nil, err
	}
	client := &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		u := req.URL
		asset := u.Host == "release-assets.githubusercontent.com" || u.Host == "objects.githubusercontent.com"
		if len(via) >= 5 || u.Scheme != "https" || u.User != nil || u.Fragment != "" || u.Port() != "" || !(mcrfOfficialScriptURL(u.String()) || asset) {
			return errors.New("unsafe script redirect")
		}
		return nil
	}}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, errors.New("official script fetch failed")
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil || int64(len(raw)) > limit {
		return nil, errors.New("script response limit")
	}
	return raw, nil
}

func mcrfRuntimeABI() string {
	if runtime.GOOS != "android" {
		return ""
	}
	switch runtime.GOARCH {
	case "arm64":
		return "arm64-v8a"
	case "arm":
		return "armeabi-v7a"
	case "amd64":
		return "x86_64"
	}
	return ""
}

// No automatic "latest" install: check verifies the candidate and returns a
// digest. Apply must name those exact bytes; drift requires a fresh check.
// This does not replace the native core or bypass Android's APK signer/ABI gate.
func McrfUpdateOfficialScript(ctx context.Context, requestPath, action, id, expectedSHA string) (McrfScriptUpdateReport, error) {
	report := McrfScriptUpdateReport{ID: id, Core: mcrfCorePin, ABI: mcrfRuntimeABI()}
	if action != "check" && action != "apply" && action != "rollback" {
		return report, errors.New("unsupported script update action")
	}
	if action != "check" && (report.ABI == "" || !mcrfDigestPattern.MatchString(expectedSHA)) {
		return report, errors.New("Android ABI and exact candidate pin required")
	}
	pins, err := mcrfScriptPins()
	if err != nil {
		return report, err
	}
	pin, ok := pins[id]
	if !ok {
		return report, errors.New("official package unavailable")
	}
	dir, lease, err := mcrfScriptLease(requestPath)
	if err != nil {
		return report, err
	}
	defer mcrfReleaseScriptLease(lease)
	_, current, err := mcrfCurrentScript(dir, pin)
	if err != nil {
		return report, err
	}
	report.Wire, report.API, report.CurrentSHA, report.CurrentVersion = pin.Wire, pin.API, current.SHA, current.Version
	var raw []byte
	var next mcrfScriptSelection
	if action == "rollback" {
		raw, next, err = mcrfSelectedScript(dir, pin, pin.ID+".prev.json")
	} else {
		// Sources are taken only from the signature-verified embedded baseline.
		base, s, e := mcrfEmbeddedScript(pin)
		if e != nil {
			return report, e
		}
		pkg, e := mcrfVerifyScript(base, pin, s)
		if e != nil {
			return report, e
		}
		var index script.UpdateIndex
		found := false
		for _, source := range pkg.Manifest.Update {
			data, e := mcrfFetchScript(ctx, source, 256<<10)
			if e == nil && json.Unmarshal(data, &index) == nil && index.Format == 1 && index.ID == id {
				found = true
				break
			}
		}
		if !found {
			return report, errors.New("signed official update source unavailable")
		}
		candidate, ok := index.Channels["stable"]
		if !ok || candidate.Wire != pin.Wire || candidate.API != pin.API || candidate.API > script.APIVersion || (candidate.PublicKey != "" && candidate.PublicKey != script.OfficialKeyHex) || !mcrfDigestPattern.MatchString(candidate.SHA256) {
			return report, errors.New("stable index compatibility rejected")
		}
		next = mcrfScriptSelection{ID: id, Version: candidate.Version, SHA: candidate.SHA256}
		order, e := mcrfCompareVersions(next.Version, current.Version)
		if e != nil || order < 0 || (order == 0 && next.SHA != current.SHA) {
			return report, errors.New("script downgrade/retag rejected")
		}
		// Bind apply before fetching any candidate, then verify again before commit.
		if action == "apply" && expectedSHA != next.SHA {
			return report, errors.New("candidate changed; check again")
		}
		raw, err = mcrfFetchScript(ctx, candidate.URL, mcrfPackageLimit)
	}
	if err != nil {
		return report, err
	}
	if _, err = mcrfVerifyScript(raw, pin, next); err != nil {
		return report, err
	}
	report.Version, report.SHA, report.RuntimePreflight = next.Version, next.SHA, true
	if action == "check" {
		report.Status = "verified_candidate"
		return report, nil
	}
	if expectedSHA != next.SHA {
		return report, errors.New("candidate changed; check again")
	}
	if err = ctx.Err(); err != nil {
		return report, err
	}
	if next.SHA == current.SHA {
		report.Status = "up_to_date"
		return report, nil
	}
	if err = mcrfInstallScript(dir, pin, raw, next, current); err != nil {
		return report, err
	}
	report.Status = "installed_for_next_session"
	return report, nil
}
