// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"wg-turn-client/internal/csqtt25"
)

func Test25BridgePreservesExactWorkersAndNewSettingsWithoutLegacyRounding(t *testing.T) {
	p := csqtt25.Profile{Version: "2.5", Peer: "fixture.invalid:46000", Password: "fixture-password", Hashes: []string{"fixture-call-0123456789abcdef"},
		HashMode: "manual", Workers: 7, ExactWorkers: true, DeviceID: "fixture-binding", Listen: "127.0.0.1:22001", SOCKS: "127.0.0.1:22002", SOCKSUser: "fixture-user", SOCKSPass: "fixture-pass", CaptchaMode: "wv", VKAuthMode: "vkcalls", Obfs: "unified", TurnTransport: "tcp"}
	data, _ := json.Marshal(p)
	parsed, c, err := readCSQTT25Bridge(strings.NewReader(string(data)))
	if err != nil || c.Workers != 7 || c.Obfs != "unified" || c.CaptchaMode != "wv" || parsed.TurnTransport != "tcp" {
		t.Fatal("new profile went through legacy normalization")
	}
	p.Version = ""
	data, _ = json.Marshal(p)
	if _, _, err = readCSQTT25Bridge(strings.NewReader(string(data))); err == nil {
		t.Fatal("legacy profile accepted by2.5")
	}
}

// Owned OS processes only: the real shared bridge must not confuse delivery of
// STOP with completion of its transport's still-running grandchild cleanup.
func Test25OwnedRetirementChild(t *testing.T) {
	role := os.Getenv("MCRF_OWNED_RETIREMENT_ROLE")
	if role == "" {
		return
	}
	root := os.Getenv("MCRF_OWNED_RETIREMENT_ROOT")
	scanner := bufio.NewScanner(os.Stdin)
	if role == "grandchild" {
		if !scanner.Scan() || scanner.Text() != "STOP" {
			os.Exit(41)
		}
		if os.WriteFile(filepath.Join(root, "stop-held"), []byte("OWNED_STOP_RECEIVED"), 0600) != nil {
			os.Exit(42)
		}
		deadline := time.Now().Add(10 * time.Second)
		for {
			if _, err := os.Stat(filepath.Join(root, "release")); err == nil {
				break
			}
			if time.Now().After(deadline) {
				os.Exit(43)
			}
			time.Sleep(10 * time.Millisecond)
		}
		if os.WriteFile(filepath.Join(root, "grandchild-exited"), []byte("OWNED_CLEANUP_COMPLETE"), 0600) != nil {
			os.Exit(44)
		}
		os.Exit(0)
	}
	if role != "transport" || !scanner.Scan() || scanner.Text() != "VK_JS_BOOTSTRAP:owned-retirement" {
		os.Exit(45)
	}
	grandchild := exec.Command(os.Args[0], "-test.run=^Test25OwnedRetirementChild$")
	grandchild.Env = append(os.Environ(), "MCRF_OWNED_RETIREMENT_ROLE=grandchild")
	grandchild.Stdout = io.Discard
	grandchild.Stderr = io.Discard
	input, err := grandchild.StdinPipe()
	if err != nil || grandchild.Start() != nil {
		os.Exit(46)
	}
	// Fatal protocol event is owned and stops the bridge before any tunnel,
	// account, DNS, provider request or network listener can be created.
	fmt.Println("__CSQTT_EVENT__|ERROR|{\"fatal\":true,\"message\":\"FATAL_PROTOCOL: owned retirement\"}")
	if !scanner.Scan() || scanner.Text() != "STOP" {
		_ = grandchild.Process.Kill()
		_ = grandchild.Wait()
		os.Exit(47)
	}
	_, err = io.WriteString(input, "STOP\n")
	_ = input.Close()
	if err != nil {
		_ = grandchild.Process.Kill()
		_ = grandchild.Wait()
		os.Exit(48)
	}
	if grandchild.Wait() != nil {
		os.Exit(49)
	}
	if os.WriteFile(filepath.Join(root, "transport-exited"), []byte("OWNED_GRANDCHILD_JOINED"), 0600) != nil {
		os.Exit(50)
	}
	os.Exit(0)
}

func Test25SharedBridgeWaitsForOwnedTransportAndGrandchildCleanupAfterStop(t *testing.T) {
	root := t.TempDir()
	t.Setenv("LOCALAPPDATA", root)
	t.Setenv("XDG_CACHE_HOME", root)
	launched := make(chan *exec.Cmd, 1)
	result := make(chan error, 1)
	read := func(string) (*bridgeConfig, error) {
		return &bridgeConfig{DeviceID: "owned-retirement", CaptchaMode: "auto"}, nil
	}
	launch := func(_ context.Context, dir string) (*exec.Cmd, string, error) {
		command := exec.Command(os.Args[0], "-test.run=^Test25OwnedRetirementChild$")
		command.Dir = dir
		command.Env = append(os.Environ(), "MCRF_OWNED_RETIREMENT_ROLE=transport", "MCRF_OWNED_RETIREMENT_ROOT="+root)
		launched <- command
		return command, "VK_JS_BOOTSTRAP:owned-retirement\n", nil
	}
	finished := false
	defer func() {
		_ = os.WriteFile(filepath.Join(root, "release"), []byte("RELEASE_OWNED_CLEANUP"), 0600)
		if !finished {
			select {
			case <-result:
			case <-time.After(12 * time.Second):
				t.Error("owned bridge did not drain after release")
			}
		}
	}()
	go func() { result <- runCSQTTBridgePrepared(filepath.Join(root, "unused-owned-profile"), read, launch) }()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, err := os.Stat(filepath.Join(root, "stop-held")); err == nil {
			break
		}
		select {
		case <-result:
			finished = true
			t.Fatal("bridge returned before owned transport received STOP")
		default:
		}
		if time.Now().After(deadline) {
			t.Fatal("owned grandchild did not acknowledge STOP")
		}
		time.Sleep(10 * time.Millisecond)
	}
	command := <-launched
	select {
	case <-result:
		finished = true
		t.Fatal("STOP acknowledgment incorrectly treated as joined child cleanup")
	case <-time.After(200 * time.Millisecond):
	}
	for _, name := range []string{"grandchild-exited", "transport-exited"} {
		if _, err := os.Stat(filepath.Join(root, name)); err == nil {
			t.Fatal("owned child ignored its cleanup gate")
		}
	}
	if err := os.WriteFile(filepath.Join(root, "release"), []byte("RELEASE_OWNED_CLEANUP"), 0600); err != nil {
		t.Fatal("cannot release owned cleanup")
	}
	select {
	case err := <-result:
		finished = true
		if err != nil {
			t.Fatal("owned bridge retirement failed")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("bridge did not finish after actual owned cleanup")
	}
	for _, name := range []string{"grandchild-exited", "transport-exited"} {
		if _, err := os.Stat(filepath.Join(root, name)); err != nil {
			t.Fatal("owned process cleanup receipt missing")
		}
	}
	if command.ProcessState == nil || !command.ProcessState.Exited() || !command.ProcessState.Success() {
		t.Fatal("actual transport process exit not observed")
	}
}

func Test25OwnedBootstrapChild(t *testing.T) {
	if os.Getenv("MCRF_OWNED_BOOTSTRAP") != "1" {
		return
	}
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() || scanner.Text() != "VK_JS_BOOTSTRAP:owned-fixture" {
		os.Exit(31)
	}
	// Owned fake transport: deliberate rejection, never provider traffic or READY.
	_, _ = os.Stdout.WriteString("__CSQTT_EVENT__|ERROR|{\"fatal\":true,\"code\":\"handshake_rejected\",\"message\":\"FATAL_PROTOCOL: owned fixture\"}\n")
	if !scanner.Scan() || scanner.Text() != "STOP" {
		os.Exit(32)
	}
	if os.WriteFile(os.Getenv("MCRF_OWNED_RECEIPT"), []byte("BOOTSTRAP_THEN_STOP"), 0600) != nil {
		os.Exit(33)
	}
	os.Exit(0)
}

func Test25SharedBridgeSendsPrivateBootstrapAndHonorsOwnedStopCleanup(t *testing.T) {
	root := t.TempDir()
	t.Setenv("LOCALAPPDATA", root)
	t.Setenv("XDG_CACHE_HOME", root)
	marker := filepath.Join(root, "owned-bootstrap-receipt")
	read := func(string) (*bridgeConfig, error) {
		return &bridgeConfig{DeviceID: "owned-fixture", CaptchaMode: "auto"}, nil
	}
	launch := func(ctx context.Context, dir string) (*exec.Cmd, string, error) {
		command := exec.Command(os.Args[0], "-test.run=^Test25OwnedBootstrapChild$")
		command.Dir = dir
		command.Env = append(os.Environ(), "MCRF_OWNED_BOOTSTRAP=1", "MCRF_OWNED_RECEIPT="+marker)
		return command, "VK_JS_BOOTSTRAP:owned-fixture\n", nil
	}
	if err := runCSQTTBridgePrepared(filepath.Join(root, "unused-owned-profile"), read, launch); err != nil {
		t.Fatal("owned adapter lifecycle failed")
	}
	got, err := os.ReadFile(marker)
	if err != nil || string(got) != "BOOTSTRAP_THEN_STOP" {
		t.Fatal("bootstrap/STOP not received by exact owned child")
	}
}
