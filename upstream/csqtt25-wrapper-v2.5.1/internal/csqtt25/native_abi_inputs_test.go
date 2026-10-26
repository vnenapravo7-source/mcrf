//go:build mcrf_native_inputs

package csqtt25

import (
	"os"
	"path/filepath"
	"testing"
)

// Enabled explicitly in the common native-input verification batch. No SKIP and
// no supplied-code execution: exercise the real loader against all pinned files.
func Test25AdditionalAbiActualArtifacts(t *testing.T) {
	additional, arm64 := os.Getenv("MCRF_CS25_ADDITIONAL_INPUTS"), os.Getenv("MCRF_CS25_ARM64_INPUT")
	if additional == "" || arm64 == "" {
		t.Fatal("explicit pinned native inputs required with mcrf_native_inputs")
	}
	for _, abi := range []string{"arm64-v8a", "armeabi-v7a", "x86_64"} {
		t.Run(abi, func(t *testing.T) {
			path := filepath.Join(additional, abi, "libcsqtt25_transport.so")
			if abi == "arm64-v8a" {
				path = arm64
			}
			loaded, err := LoadRuntime(path)
			if err != nil || loaded == nil {
				t.Fatal("pinned ABI input rejected", abi, err)
			}
			if loaded.path == "" {
				t.Fatal("verified runtime has no private path")
			}
		})
	}
}
