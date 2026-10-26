//go:build linux || android

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
	"strconv"
	"strings"
	"testing"
	"time"
)

// Read identity only for a PID discovered as a direct child of our own wrapper.
// Never search process names or kill arbitrary matching executables.
func procIdentity(pid int) (state, start string, err error) {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return "", "", err
	}
	end := strings.LastIndex(string(data), ") ")
	if end < 0 {
		return "", "", io.ErrUnexpectedEOF
	}
	fields := strings.Fields(string(data)[end+2:])
	if len(fields) < 20 {
		return "", "", io.ErrUnexpectedEOF
	}
	return fields[0], fields[19], nil
}

func ownedCore(t *testing.T, parent int, core string) (int, string) {
	t.Helper()
	target, err := filepath.EvalSymlinks(core)
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := os.ReadDir(fmt.Sprintf("/proc/%d/task", parent))
	if err != nil {
		t.Fatal(err)
	}
	var child int
	for _, task := range tasks {
		data, e := os.ReadFile(fmt.Sprintf("/proc/%d/task/%s/children", parent, task.Name()))
		if e != nil {
			continue
		}
		for _, text := range strings.Fields(string(data)) {
			pid, e := strconv.Atoi(text)
			if e != nil {
				t.Fatal("unexpected owned child PID")
			}
			actual, e := filepath.EvalSymlinks(fmt.Sprintf("/proc/%d/exe", pid))
			if e == nil && actual == target {
				if child != 0 && child != pid {
					t.Fatal("more than one core child")
				}
				child = pid
			}
		}
	}
	if child == 0 {
		// Android kernels can omit task/*/children (CONFIG_CHECKPOINT_RESTORE).
		// Only consider a stat-verified direct child of our recorded wrapper;
		// executable identity is checked after parenthood, never by name alone.
		entries, e := os.ReadDir("/proc")
		if e != nil { t.Fatal(e) }
		for _, entry := range entries {
			pid, e := strconv.Atoi(entry.Name()); if e != nil { continue }
			data, e := os.ReadFile(fmt.Sprintf("/proc/%d/stat",pid)); if e != nil { continue }
			end := strings.LastIndex(string(data), ") "); if end < 0 { continue }
			fields := strings.Fields(string(data)[end+2:]); if len(fields)<2 { continue }
			ppid, e := strconv.Atoi(fields[1]); if e != nil || ppid!=parent { continue }
			actual, e := filepath.EvalSymlinks(fmt.Sprintf("/proc/%d/exe",pid))
			if e==nil && actual==target {
				if child!=0 && child!=pid {t.Fatal("more than one verified core child")}
				child=pid
			}
		}
		if child==0 {t.Fatal("owned wrapper core was not found")}
	}
	_, start, err := procIdentity(child)
	if err != nil {
		t.Fatal(err)
	}
	return child, start
}

func waitCoreStopped(t *testing.T, pid int, start string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		state, current, err := procIdentity(pid)
		if os.IsNotExist(err) || err == nil && (current != start || state == "Z" || state == "X") {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("owned Slipstream core remained live")
}

func ownedWrapper(t *testing.T) (*exec.Cmd, io.WriteCloser, <-chan error, int, string) {
	t.Helper()
	c, core, dir, _ := wireFixture(t)
	wrapper := wireBinary(t, "MCRF_SLIPSTREAM_TEST_WRAPPER")
	expected, e := filepath.EvalSymlinks(filepath.Join(filepath.Dir(wrapper), "libslipstream_core.so"))
	actual, ae := filepath.EvalSymlinks(core)
	if e != nil || ae != nil || expected != actual {
		t.Fatal("wrapper fixture must use the exact owned sibling core")
	}
	data, e := json.Marshal(c)
	if e != nil {
		t.Fatal(e)
	}
	configPath := filepath.Join(dir, "config.json")
	if e = os.WriteFile(configPath, data, 0600); e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	command := exec.CommandContext(ctx, wrapper, "--mcrf-config", configPath)
	stdin, e := command.StdinPipe()
	if e != nil {
		t.Fatal(e)
	}
	stdout, e := command.StdoutPipe()
	if e != nil {
		t.Fatal(e)
	}
	command.Stderr = io.Discard
	command.Env = []string{}
	readDone := make(chan struct{})
	done, e := fixtureStart(command, readDone)
	if e != nil {
		cancel()
		t.Fatal(e)
	}
	ready := make(chan struct{})
	go func() {
		defer close(readDone)
		scanner := bufio.NewScanner(stdout)
		reported := false
		for scanner.Scan() {
			if scanner.Text() == "MCRF_READY" && !reported {
				close(ready)
				reported = true
			}
		}
	}()
	t.Cleanup(func() {
		stdin.Close()
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("owned wrapper did not stop")
		}
	})
	select {
	case <-ready:
	case <-done:
		t.Fatal("owned wrapper failed before ready")
	case <-time.After(40 * time.Second):
		t.Fatal("owned wrapper readiness timed out")
	}
	pid, start := ownedCore(t, command.Process.Pid, core)
	t.Cleanup(func() {
		// Backstop only the recorded child with unchanged kernel start identity.
		state, current, err := procIdentity(pid)
		if err == nil && current == start && state != "Z" && state != "X" {
			if process, e := os.FindProcess(pid); e == nil {
				process.Kill()
			}
		}
	})
	return command, stdin, done, pid, start
}

func TestMcrfSlipstreamParentDeathStopsCore(t *testing.T) {
	command, _, done, pid, start := ownedWrapper(t)
	if err := command.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("owned wrapper kill did not finish")
	}
	waitCoreStopped(t, pid, start)
}

func TestMcrfSlipstreamStdinEOFStopsCore(t *testing.T) {
	_, stdin, done, pid, start := ownedWrapper(t)
	if err := stdin.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("EOF did not retire cleanly: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("stdin EOF did not stop wrapper")
	}
	waitCoreStopped(t, pid, start)
}
