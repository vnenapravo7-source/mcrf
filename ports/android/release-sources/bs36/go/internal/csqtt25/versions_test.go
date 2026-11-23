package csqtt25

import (
    "encoding/json"
    "os"
    "path/filepath"
    "strings"
    "testing"
)

func TestSwgVersionsSelectExactTransportWithoutLegacyFallback(t *testing.T) {
    for _, implementation := range []string{"", "swg-panel-2.5.1", "swg-panel-2.5.2", "swg-panel-2.5.3"} {
        p := fixture(); p.Implementation = implementation
        name, err := NativeFilename(p)
        if err != nil { t.Fatal(err) }
        modern := implementation == "swg-panel-2.5.2" || implementation == "swg-panel-2.5.3"
        if (name == "libcsqtt252_transport.so") != modern { t.Fatal("wrong native implementation") }
        if _,err := Prepare(p); err != nil { t.Fatal(err) }
    }
    p := fixture(); p.Implementation = "swg-panel-2.5.4"
    if _,err := NativeFilename(p); err == nil { t.Fatal("unknown release accepted") }
    if _,err := Prepare(p); err == nil { t.Fatal("unknown release prepared") }
}

func TestNewSwgProfilesKeepImplementationAndRejectDuplicateOrWrongTypes(t *testing.T) {
    for _, version := range []string{"2.5.2","2.5.3"} {
        p := fixture(); p.Implementation = "swg-panel-"+version
        encoded,err := json.Marshal(p); if err != nil { t.Fatal(err) }
        decoded,err := ReadProfile(strings.NewReader(string(encoded)))
        if err != nil || decoded.Implementation != p.Implementation { t.Fatal("version lost during decode") }
        base := `{"csqtt_generation":"2.5","csqtt_implementation":"swg-panel-`+version+`"}`
        if _,err := ReadProfile(strings.NewReader(base)); err == nil { t.Fatal("incomplete profile accepted") }
    }
    for _, suffix := range []string{`,"csqtt_implementation":253`,`,"csqtt_implementation":"swg-panel-2.5.2","csqtt_implementation":"swg-panel-2.5.3"`} {
        if _,err := ReadProfile(strings.NewReader(`{"csqtt_generation":"2.5"`+suffix+`}`)); err == nil { t.Fatal("malformed implementation accepted") }
    }
}

func TestNewSwgNativePinsAreDistinctFrom251ForEveryAbi(t *testing.T) {
    for _, abi := range []string{"arm64-v8a","armeabi-v7a","x86_64"} {
        old, _ := nativeForABI(abi); next, ok := newNativeForABI(abi)
        if !ok || next.sha256 == old.sha256 || next.bytes == old.bytes || next.class != old.class || next.machine != old.machine { t.Fatal("native version/ABI pin error") }
    }
    if _,ok := newNativeForABI("x86"); ok { t.Fatal("nonexistent upstream ABI accepted") }
}

func TestNewSwgGateRejectsOldOrUnknownContentUnderNewFilename(t *testing.T) {
    root := t.TempDir()
    for _, name := range []string{"libcsqtt252_transport.so","libcsqtt253_transport.so"} {
        file := filepath.Join(root,name)
        if err := os.WriteFile(file,[]byte("not an ELF and never executed"),0600); err != nil { t.Fatal(err) }
        if _,err := LoadRuntime(file); err == nil { t.Fatal("unverified native accepted") }
    }
}
