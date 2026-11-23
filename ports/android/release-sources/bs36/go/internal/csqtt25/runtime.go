package csqtt25

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// Owned CSQTT25 d02da9b + SWG 2.5.1 dispatcher + negotiated worker limit.
// Exact rebuilt transport identities are bound by the native source receipt.
const NativeSHA256 = "35921006d43b95c65beea5bc0f64f56e2604dbd8af2adcf8a25a5eccf4a5a0dc"
const NativeBytes int64 = 9447392

// Runtime has no public path constructor: the legacy binary cannot accidentally
// satisfy the new adapter. APK signature/package trust remains the Android host's gate.
type Runtime struct{ path string }

func (r Runtime) String() string { return "CSQTT25Runtime(verified)" }

func LoadRuntime(path string) (*Runtime, error) {
	if filepath.Base(path)=="libcsqtt_transport.so" { return loadBSMulticallRuntime(path) }
	invalid := errors.New("CSQTT 2.5 native artifact does not match its pinned SWG release or this Android ABI")
	name := filepath.Base(path)
	if name != "libcsqtt25_transport.so" && name != "libcsqtt252_transport.so" {
		return nil, invalid
	}
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() < 20 {
		return nil, invalid
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, invalid
	}
	defer file.Close()
	var header [20]byte
	if _, err = io.ReadFull(file, header[:]); err != nil {
		return nil, invalid
	}
	spec, ok := nativeForHeader(header[:], runtime.GOOS, runtime.GOARCH)
	if ok && name == "libcsqtt252_transport.so" { spec, ok = newNativeForABI(spec.abi) }
	if !ok || info.Size() != spec.bytes {
		return nil, invalid
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return nil, invalid
	}
	hash := sha256.New()
	size, err := io.Copy(hash, io.LimitReader(file, spec.bytes+1))
	if err != nil || size != spec.bytes || hex.EncodeToString(hash.Sum(nil)) != spec.sha256 {
		return nil, invalid
	}
	resolved, err := filepath.Abs(path)
	if err != nil {
		return nil, invalid
	}
	return &Runtime{path: resolved}, nil
}

// Construction only: this does not start supplied code or create provider calls.
// The panel 2.5.1 password ingress is CSQTT_PASSWORD, not a legacy CLI flag.
// Never log command.Env or bootstrap; expose sanitized lifecycle events only.
func (r *Runtime) Command(ctx context.Context, spec LaunchSpec, privateDir string) (*exec.Cmd, error) {
	if r == nil || ctx == nil || ctx.Err() != nil || len(spec.args) == 0 || !clean(spec.password, 1024) {
		return nil, errors.New("CSQTT 2.5 launch is not prepared")
	}
	if _, err := LoadRuntime(r.path); err != nil {
		return nil, err
	}
	info, err := os.Lstat(privateDir)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("CSQTT 2.5 private directory is unavailable")
	}
	// The owning bridge sends STOP and reaps this exact child with its VK leave
	// grace period. CommandContext's default immediate Kill would skip that cleanup.
	command := exec.Command(r.path, append([]string(nil), spec.args...)...)
	command.Dir = privateDir
	// Do not inherit the patched legacy adapter's private profile or a caller's
	// loader override into a different generation. There must be exactly one
	// value for each adapter-owned setting, on both Windows host tests and Android.
	for _, value := range os.Environ() {
		key := strings.ToUpper(strings.SplitN(value, "=", 2)[0])
		switch key {
		case "MCRF_BS_RUST_MODE", "MCRF_BS_MODE", "MCRF_BS_LAYOUT", "MCRF_BS_TRANSPORT", "MCRF_CSQTT_LEGACY_UNDERLAY", "CSQTT_CONFIG_FILE", "CSQTT_PASSWORD", "CSQTT_EVENTS", "XDG_CACHE_HOME", "LD_PRELOAD", "LD_LIBRARY_PATH", "MCRF_CAPTCHA_IDS":
			continue
		}
		command.Env = append(command.Env, value)
	}
	command.Env = append(command.Env, "CSQTT_EVENTS=1", "CSQTT_PASSWORD="+spec.password, "XDG_CACHE_HOME="+privateDir)
	if filepath.Base(r.path)=="libcsqtt_transport.so" { mode,err:=bsMulticallMode(); if err!=nil{return nil,err}; command.Env=append(command.Env,"MCRF_BS_RUST_MODE="+mode) }
	return command, nil
}

// Bootstrap is private stdin data. It must never be logged or placed in argv.
func (s LaunchSpec) Bootstrap() string { return s.bootstrap }
