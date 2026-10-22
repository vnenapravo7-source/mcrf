// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"

	"wg-turn-client/internal/csqtt25"
)

// Separate entry point, config and native identity. Never reinterpret an old
// CSQTT private file as2.5 or silently select2.5 after a legacy connection error.
func runCSQTT25Bridge(path string) error {
	var profile csqtt25.Profile
	read := func(path string) (*bridgeConfig, error) {
		input, err := os.Open(path)
		if err != nil {
			return nil, errors.New("private CSQTT 2.5 configuration unavailable")
		}
		defer input.Close()
		var config *bridgeConfig
		profile, config, err = readCSQTT25Bridge(input)
		return config, err
	}
	launch := func(ctx context.Context, privateDir string) (*exec.Cmd, string, error) {
		exe, err := os.Executable()
		if err != nil {
			return nil, "", errors.New("CSQTT 2.5 adapter location unavailable")
		}
		runtime, err := csqtt25.LoadRuntime(filepath.Join(filepath.Dir(exe), "libcsqtt25_transport.so"))
		if err != nil {
			return nil, "", err
		}
		spec, err := csqtt25.Prepare(profile)
		if err != nil {
			return nil, "", err
		}
		command, err := runtime.Command(ctx, spec, privateDir)
		if err != nil {
			return nil, "", err
		}
		return command, spec.Bootstrap(), nil
	}
	return runCSQTTBridgePrepared(path, read, launch)
}

func readCSQTT25Bridge(input io.Reader) (csqtt25.Profile, *bridgeConfig, error) {
	profile, err := csqtt25.ReadProfile(input)
	if err != nil || profile.ValidateBridge() != nil {
		return csqtt25.Profile{}, nil, errors.New("invalid private CSQTT 2.5 bridge configuration")
	}
	return profile, &bridgeConfig{Peer: profile.Peer, Password: profile.Password, Hashes: profile.Hashes, Workers: profile.Workers,
		VKRuntimeToken: profile.Token,
		DiagnosticSecrets: []string{profile.Salt},
		NormalizeTunnel: csqtt25.NormalizeTunnel,
		DeviceID: profile.DeviceID, Listen: profile.Listen, SOCKS: profile.SOCKS, SOCKSUser: profile.SOCKSUser, SOCKSPass: profile.SOCKSPass,
		CaptchaMode: profile.CaptchaMode, Obfs: profile.Obfs, VKAnonPath: profile.VKAuthMode, ProbeOnly: profile.ProbeOnly}, nil
}
