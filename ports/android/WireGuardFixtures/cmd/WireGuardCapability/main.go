// Read-only artifact inspection. Never loads/executes Android native code.
package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"debug/buildinfo"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
)

func main() {
	if len(os.Args) != 2 {
		panic("provide libbox.aar path")
	}
	file, err := os.ReadFile(os.Args[1])
	if err != nil {
		panic(err)
	}
	hash := sha256.Sum256(file)
	archive, err := zip.NewReader(bytes.NewReader(file), int64(len(file)))
	if err != nil {
		panic(err)
	}
	values := []map[string]any{}
	for _, entry := range archive.File {
		if !strings.HasPrefix(entry.Name, "jni/") || !strings.HasSuffix(entry.Name, ".so") {
			continue
		}
		reader, err := entry.Open()
		if err != nil {
			panic(err)
		}
		data, err := io.ReadAll(reader)
		reader.Close()
		if err != nil {
			panic(err)
		}
		sum := sha256.Sum256(data)
		value := map[string]any{"path": entry.Name, "sha256": hex.EncodeToString(sum[:]), "size": len(data)}
		info, err := buildinfo.Read(bytes.NewReader(data))
		if err != nil {
			value["buildinfo_error"] = err.Error()
		} else {
			value["go_version"] = info.GoVersion
			settings := map[string]string{}
			for _, setting := range info.Settings {
				if setting.Key == "-tags" || setting.Key == "GOOS" || setting.Key == "GOARCH" || setting.Key == "CGO_ENABLED" || setting.Key == "vcs.revision" {
					settings[setting.Key] = setting.Value
				}
			}
			value["build_settings"] = settings
			modules := []map[string]string{}
			for _, mod := range info.Deps {
				if strings.Contains(mod.Path, "wireguard") || strings.Contains(mod.Path, "amnezia") || strings.Contains(mod.Path, "sing-box") {
					m := map[string]string{"path": mod.Path, "version": mod.Version, "sum": mod.Sum}
					if mod.Replace != nil {
						m["replace_path"] = mod.Replace.Path
						m["replace_version"] = mod.Replace.Version
					}
					modules = append(modules, m)
				}
			}
			value["related_modules"] = modules
		}
		fields := map[string]bool{}
		for _, field := range []string{"header_protection_key", "content_padding_addition", "random_trailers", "disable_cookies", "rekey_after_time", "keepalive_timeout", "max_handshake_attempts", "amneziawg: header_protection_key must decode", "awg support not built"} {
			fields[field] = bytes.Contains(data, []byte(field))
		}
		value["native_literals_present"] = fields
		values = append(values, value)
	}
	out := map[string]any{"aar_path": os.Args[1], "aar_sha256": hex.EncodeToString(hash[:]), "inspection": "Static Go buildinfo and native payload strings; not runtime config/handshake proof", "native_payloads": values}
	encoded, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		panic(err)
	}
	fmt.Println(string(encoded))
}
