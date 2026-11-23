//go:build mcrf_native_inputs

package csqtt25

import (
    "os"
    "path/filepath"
    "testing"
)

func TestAlignedSwgNativeActualArtifacts(t *testing.T) {
    root, old := os.Getenv("MCRF_SWG_ALIGNED_INPUTS"), os.Getenv("MCRF_SWG_UPSTREAM_INPUTS")
    if root == "" || old == "" { t.Fatal("explicit aligned and upstream input stages required") }
    for _, abi := range []string{"arm64-v8a", "armeabi-v7a", "x86_64"} {
        t.Run(abi, func(t *testing.T) {
            if _, err := LoadRuntime(filepath.Join(root, abi, "libcsqtt252_transport.so")); err != nil { t.Fatal(err) }
        })
    }
    if _, err := LoadRuntime(filepath.Join(old, "armeabi-v7a", "libcsqtt252_transport.so")); err == nil {
        t.Fatal("old four-KiB ARMv7 transport unexpectedly admitted")
    }
}
