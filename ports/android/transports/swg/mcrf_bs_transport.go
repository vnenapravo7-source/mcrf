// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "errors"
    "os"
    "path/filepath"
    "runtime"
    "strconv"
    "strings"
)

const mcrfLegacyUnderlayEnv = "MCRF_CSQTT_LEGACY_UNDERLAY"
const mcrfModernUnderlayEnv = "MCRF_CSQTT_MODERN_UNDERLAY"

// Mode is supplied by the trusted Android host per ProcessBuilder. It is never
// decoded from the imported/private JSON. No process-global mutable mode exists.
func mcrfBSModeFlag(mode string) (string, error) {
    switch mode {
    case "csqtt", "csqtt20", "csqtt215": return "-csqtt-config", nil
    case "csqtt25": return "-csqtt25-config", nil
    case "qwdtt": return "-config", nil
    default: return "", errors.New("invalid BS host mode")
    }
}

func mcrfBSValidateMode(mode, transport string, args []string) error {
    if mode == "" { return nil } // Original standalone CLI remains unchanged.
    flag, err := mcrfBSModeFlag(mode)
    if err != nil { return err }
    if transport != "" || len(args) != 3 || args[1] != flag || args[2] == "" {
        return errors.New("BS host mode/CLI mismatch")
    }
    return nil
}

func mcrfBSValidateLaunch(args []string) error {
    mode, err := mcrfBSRustMode(os.Getenv("MCRF_BS_LAYOUT"), os.Getenv("MCRF_BS_MODE"), os.Getenv("MCRF_BS_RUST_MODE"))
    if err != nil { return err }
    if _, err := mcrfBSLegacyUnderlay(mode); err != nil { return err }
    if _, err := mcrfBSModernUnderlay(mode); err != nil { return err }
    return mcrfBSValidateMode(os.Getenv("MCRF_BS_MODE"), os.Getenv("MCRF_BS_TRANSPORT"), args)
}

func mcrfBSLegacyUnderlay(generation string) (string, error) {
    return mcrfBSProviderUnderlay(mcrfLegacyUnderlayEnv,generation=="2.0"||generation=="2.1.5"||generation=="2.1.9")
}

func mcrfBSModernUnderlay(generation string) (string,error) {
    return mcrfBSProviderUnderlay(mcrfModernUnderlayEnv,generation=="2.5.1"||generation=="2.5.2"||generation=="2.5.3")
}

func mcrfBSProviderUnderlay(key string, allowed bool)(string,error) {
    value, exists := os.LookupEnv(key)
    if !exists { return "", nil }
    handle, err := strconv.ParseUint(value, 10, 63)
    if !allowed||err != nil || handle == 0 || strconv.FormatUint(handle, 10) != value {
        return "", errors.New("invalid CSQTT physical network")
    }
    return value, nil
}

func mcrfBSRustMode(layout, mode, selected string) (string, error) {
    if layout == "" { return "", nil }
    invalid := errors.New("BS Rust host generation mismatch")
    if layout != "rust-multicall-v1" { return "", invalid }
    expected := map[string]string{"csqtt":"2.1.9", "csqtt20":"2.0", "csqtt215":"2.1.5", "qwdtt":""}
    if mode == "csqtt25" {
        if selected == "2.5.1" || selected == "2.5.2" || selected == "2.5.3" { return selected, nil }
        return "", invalid
    }
    want, ok := expected[mode]
    if !ok || selected != want { return "", invalid }
    return want, nil
}

// Construct a fresh child environment. Never mutate process-wide env, and never
// forward a selector from an imported JSON/private configuration.
func mcrfBSChildEnv(base []string) ([]string, error) {
    mode, err := mcrfBSRustMode(os.Getenv("MCRF_BS_LAYOUT"), os.Getenv("MCRF_BS_MODE"), os.Getenv("MCRF_BS_RUST_MODE"))
    if err != nil { return nil, err }
    underlay, err := mcrfBSLegacyUnderlay(mode)
    if err != nil { return nil, err }
    if _, err := mcrfBSModernUnderlay(mode); err != nil { return nil, err }
    out := make([]string, 0, len(base)+1)
    for _, row := range base {
        key := strings.ToUpper(strings.SplitN(row, "=", 2)[0])
        switch key { case "MCRF_BS_MODE", "MCRF_BS_LAYOUT", "MCRF_BS_TRANSPORT", "MCRF_BS_RUST_MODE", mcrfLegacyUnderlayEnv, mcrfModernUnderlayEnv: continue }
        out = append(out, row)
    }
    if mode != "" { out = append(out, "MCRF_BS_RUST_MODE="+mode) }
    if underlay != "" { out = append(out, mcrfLegacyUnderlayEnv+"="+underlay) }
    return out, nil
}

func mcrfBSModernImplementation(implementation string) error {
    mode, err := mcrfBSRustMode(os.Getenv("MCRF_BS_LAYOUT"), os.Getenv("MCRF_BS_MODE"), os.Getenv("MCRF_BS_RUST_MODE"))
    if err != nil { return err }
    if mode == "" { return nil }
    want := strings.TrimPrefix(implementation, "swg-panel-")
    if implementation == "" { want = "2.5.1" }
    if mode != want { return errors.New("BS modern profile does not match trusted host generation") }
    return nil
}

func mcrfBSLegacyName(mode, override, defaultName string) (string, error) {
    if mode != "" {
        if override != "" { return "", errors.New("ambiguous BS transport selection") }
        switch mode {
        case "csqtt": return "libcsqtt_transport.so", nil
        case "csqtt20": return "libcsqtt20_transport.so", nil
        case "csqtt215": return "libcsqtt215_transport.so", nil
        default: return "", errors.New("BS mode has no legacy transport")
        }
    }
    if override == "" { return defaultName, nil }
    switch override {
    case "libcsqtt_transport.so", "libcsqtt20_transport.so", "libcsqtt215_transport.so": return override, nil
    default: return "", errors.New("invalid BS transport selection")
    }
}

// Host-only selector. Never allow paths, a writable executable or an arbitrary
// imported profile to select a binary. Android verifies all installed APK bytes.
func mcrfBSTransport(defaultName string) (string, error) {
    mode, override := os.Getenv("MCRF_BS_MODE"), os.Getenv("MCRF_BS_TRANSPORT")
    name, err := mcrfBSLegacyName(mode, override, defaultName)
    if err != nil { return "", err }
    if os.Getenv("MCRF_BS_LAYOUT") == "rust-multicall-v1" {
        if _, err := mcrfBSRustMode(os.Getenv("MCRF_BS_LAYOUT"), mode, os.Getenv("MCRF_BS_RUST_MODE")); err != nil { return "", err }
        name = "libcsqtt_transport.so"
    }
    if mode == "" && override == "" { return defaultName, nil }
    invalid := errors.New("invalid BS transport selection")
    if runtime.GOOS != "android" { return "", invalid }
    exe, err := os.Executable()
    if err != nil { return "", invalid }
    file := filepath.Join(filepath.Dir(exe), name)
    info, err := os.Lstat(file)
    if err != nil || !info.Mode().IsRegular() || info.Mode().Perm() & 0111 == 0 { return "", invalid }
    return name, nil
}
