package csqtt25

import (
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
)

func abiHeader(class byte, machine uint16) []byte {
	header := make([]byte, 20)
	copy(header, "\x7fELF")
	header[4], header[5] = class, 1
	binary.LittleEndian.PutUint16(header[16:], 3)
	binary.LittleEndian.PutUint16(header[18:], machine)
	return header
}

func Test25AdditionalAbiDescriptorsAreExactAndDistinct(t *testing.T) {
	seen := make(map[string]bool)
	for _, abi := range []string{"arm64-v8a", "armeabi-v7a", "x86_64"} {
		spec, ok := nativeForABI(abi)
		if !ok || spec.abi != abi || spec.bytes < 1000000 || len(spec.sha256) != 64 || seen[spec.sha256] {
			t.Fatal("missing or duplicated pinned ABI identity", abi)
		}
		seen[spec.sha256] = true
	}
	if _, ok := nativeForABI("universal"); ok {
		t.Fatal("a universal APK is not a native execution ABI")
	}
	if spec, _ := nativeForABI("arm64-v8a"); spec.sha256 != NativeSHA256 || spec.bytes != NativeBytes {
		t.Fatal("existing arm64 identity changed")
	}
}

func Test25AdditionalAbiAndroidMustMatchWrapperArchitecture(t *testing.T) {
	for _, abi := range []string{"arm64-v8a", "armeabi-v7a", "x86_64"} {
		spec, _ := nativeForABI(abi)
		header := abiHeader(spec.class, spec.machine)
		if got, ok := nativeForHeader(header, "android", spec.goarch); !ok || got.abi != abi {
			t.Fatal("matching Android ABI rejected", abi)
		}
		for _, other := range []string{"arm64", "arm", "amd64"} {
			if other != spec.goarch {
				if _, ok := nativeForHeader(header, "android", other); ok {
					t.Fatal("foreign ABI silently substituted", abi, other)
				}
			}
		}
	}
}

func Test25AdditionalAbiHeaderInspectionRejectsMalformedOrOtherGenerations(t *testing.T) {
	for _, header := range [][]byte{nil, abiHeader(1, 62), abiHeader(2, 40), abiHeader(2, 3)} {
		if _, ok := nativeForHeader(header, "windows", "amd64"); ok {
			t.Fatal("invalid ELF admitted")
		}
	}
	header := abiHeader(2, 62)
	header[16] = 2
	if _, ok := nativeForHeader(header, "windows", "amd64"); ok {
		t.Fatal("non-shared executable admitted")
	}
}

func Test25AdditionalAbiRejectsCorrectHeaderWithWrongContent(t *testing.T) {
	for _, abi := range []string{"armeabi-v7a", "x86_64"} {
		spec, _ := nativeForABI(abi)
		path := filepath.Join(t.TempDir(), "libcsqtt25_transport.so")
		file, err := os.Create(path)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = file.Write(abiHeader(spec.class, spec.machine)); err != nil {
			file.Close()
			t.Fatal(err)
		}
		if err = file.Truncate(spec.bytes); err != nil {
			file.Close()
			t.Fatal(err)
		}
		if err = file.Close(); err != nil {
			t.Fatal(err)
		}
		if _, err = LoadRuntime(path); err == nil {
			t.Fatal("size/header alone must not authenticate native code", abi)
		}
	}
}
