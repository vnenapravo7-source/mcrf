package csqtt25

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// SanityProtocol/swg-panel csqtt-app-2.5.1, upstream d02da9b +
// csqtt-app-2.5.1.patch (UDP flow pin/bulk distribution fix).
// APK SHA256: 733962c7a09f6d811797ca09c03ac5040ec21b0d6623ea21201a2c85762e7c12.
// This generation must not silently accept the withdrawn prerelease artifact.
const NativeSHA256 = "9dad51554bafb2e6cf386a48cc278fb08c434a78ece40baff00fb51f203b237c"
const NativeBytes int64 = 9306144

// Runtime has no public path constructor: the legacy binary cannot accidentally
// satisfy the new adapter. APK signature/package trust remains the Android host's gate.
type Runtime struct{ path string }

func (r Runtime) String() string { return "CSQTT25Runtime(arm64,verified)" }

func LoadRuntime(path string) (*Runtime, error) {
	invalid := errors.New("CSQTT 2.5 native artifact does not match the pinned swg-panel 2.5.1 arm64 release")
	if filepath.Base(path) != "libcsqtt25_transport.so" {
		return nil, invalid
	}
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() != NativeBytes {
		return nil, invalid
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, invalid
	}
	defer file.Close()
	var header [20]byte
	if _, err = io.ReadFull(file, header[:]); err != nil || string(header[:4]) != "\x7fELF" || header[4] != 2 || header[5] != 1 || binary.LittleEndian.Uint16(header[18:]) != 183 {
		return nil, invalid
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return nil, invalid
	}
	hash := sha256.New()
	size, err := io.Copy(hash, io.LimitReader(file, NativeBytes+1))
	if err != nil || size != NativeBytes || hex.EncodeToString(hash.Sum(nil)) != NativeSHA256 {
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
		case "CSQTT_CONFIG_FILE", "CSQTT_PASSWORD", "CSQTT_EVENTS", "XDG_CACHE_HOME", "LD_PRELOAD", "LD_LIBRARY_PATH", "MCRF_CAPTCHA_IDS":
			continue
		}
		command.Env = append(command.Env, value)
	}
	command.Env = append(command.Env, "CSQTT_EVENTS=1", "CSQTT_PASSWORD="+spec.password, "XDG_CACHE_HOME="+privateDir)
	return command, nil
}

// Bootstrap is private stdin data. It must never be logged or placed in argv.
func (s LaunchSpec) Bootstrap() string { return s.bootstrap }
