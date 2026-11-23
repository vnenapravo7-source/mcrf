// Generated only in the BS wrapper; standalone SWG release loaders stay exact.
package csqtt25

import (
    "crypto/sha256"
    "encoding/hex"
    "errors"
    "io"
    "os"
    "path/filepath"
    "runtime"
)

var bsMulticallPins = map[string]struct{hash string; bytes int64}{
        "arm64-v8a": {"c7145c614f772ef76d158782070c16c21b77e228ffe520077c73eda0f028712b", 8951848},
    "armeabi-v7a": {"93d7b4bb8cdcac3e69bfb857049c60eb08737842d65372b2e4d6d69c54ad21ef", 6564176},
    "x86_64": {"ac5e7c3d93ee8512fe0ebc72c9a5c0160f7acd26c1dc1b931c8ddddcae50be5a", 10729664},
}

func bsMulticallMode() (string, error) {
    mode := os.Getenv("MCRF_BS_RUST_MODE")
    if os.Getenv("MCRF_BS_LAYOUT") != "rust-multicall-v1" || os.Getenv("MCRF_BS_MODE") != "csqtt25" ||
        (mode != "2.5.1" && mode != "2.5.2" && mode != "2.5.3") {
        return "", errors.New("CSQTT multi-call requires trusted modern host generation")
    }
    return mode, nil
}

func loadBSMulticallRuntime(path string) (*Runtime, error) {
    invalid := errors.New("CSQTT multi-call native identity mismatch")
    if _, err := bsMulticallMode(); err != nil { return nil, err }
    if filepath.Base(path) != "libcsqtt_transport.so" { return nil, invalid }
    info, err := os.Lstat(path)
    if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0111 == 0 { return nil, invalid }
    file, err := os.Open(path); if err != nil { return nil, invalid }; defer file.Close()
    var header [20]byte
    if _, err = io.ReadFull(file, header[:]); err != nil { return nil, invalid }
    descriptor, ok := nativeForHeader(header[:], runtime.GOOS, runtime.GOARCH)
    pin, present := bsMulticallPins[descriptor.abi]
    if !ok || !present || info.Size() != pin.bytes { return nil, invalid }
    if _, err = file.Seek(0, io.SeekStart); err != nil { return nil, invalid }
    hash := sha256.New()
    n, err := io.Copy(hash, io.LimitReader(file, pin.bytes+1))
    if err != nil || n != pin.bytes || hex.EncodeToString(hash.Sum(nil)) != pin.hash { return nil, invalid }
    resolved, err := filepath.Abs(path); if err != nil { return nil, invalid }
    return &Runtime{path:resolved}, nil
}
