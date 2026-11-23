//go:build linux || android

// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// These are OWNED executable children, never discovered external PIDs. No
// signal is sent: signal0 only observes the previously captured private PGID.
// Closing the root's stdin makes it close/join its leaf even on an assertion
// failure. No network, account, browser, Android VPN or daemon is involved.
func TestMcrfProcessGroupLinuxChild(t *testing.T) {
	role := os.Getenv("MCRF_P2_GROUP_FIXTURE")
	if role == "" { return }
	code := func() (result int) {
		scanner := bufio.NewScanner(os.Stdin)
		if role == "leaf" {
			if os.Getenv("MCRF_PROCESS_OWNER") != "" { return 71 }
			fmt.Printf("OWNED_LEAF %d\n", os.Getpid())
			if !scanner.Scan() || scanner.Text() != "STOP" { return 0 }
			fmt.Println("OWNED_LEAF_STOP_HELD")
			// Deliberately hold cleanup after STOP until exact parent's release.
			if scanner.Scan() && scanner.Text() != "RELEASE" { return 72 }
			return 0
		}
		if role != "root" { return 73 }
		owner, err := mcrfPrepareProcessGroup()
		if err != nil { return 74 }
		// Android need not expose /proc/PID/task/PID/children. Wait4 from the
		// exact root proves the kernel has NO child, including a live child
		// (which would return 0,nil with WNOHANG), before any private ACK.
		var status syscall.WaitStatus
		if _,err=syscall.Wait4(-1,&status,syscall.WNOHANG,nil);err!=syscall.ECHILD {return 82}
		fmt.Println("OWNED_NO_CHILDREN")
		acked := false
		for scanner.Scan() {
			if owner.accept(scanner.Text()) { acked = true; break }
		}
		// EOF before ACK must not strand a helper if its exact parent exits.
		if !acked { return 75 }
		if err = owner.await(context.Background()); err != nil { return 75 }
		leaf := exec.Command(os.Args[0], "-test.run=^TestMcrfProcessGroupLinuxChild$")
		leaf.Env = append(mcrfProcessChildEnvironment(os.Environ()), "MCRF_P2_GROUP_FIXTURE=leaf")
		leaf.Stdout, leaf.Stderr = os.Stdout, os.Stderr
		input, err := leaf.StdinPipe()
		if err != nil { return 76 }
		if err = leaf.Start(); err != nil { input.Close(); return 77 }
		defer func() { input.Close(); if leaf.Wait()!=nil {result=81} }()
		if !scanner.Scan() || scanner.Text() != "STOP" { return 0 }
		if _, err = io.WriteString(input, "STOP\n"); err != nil { return 78 }
		if scanner.Scan() {
			if scanner.Text() != "RELEASE" { return 79 }
			if _, err = io.WriteString(input, "RELEASE\n"); err != nil { return 80 }
		}
		return 0
	}()
	os.Exit(code)
}

func TestMcrfProcessGroupLinuxRealAckAndWholeTreeRetirement(t *testing.T) {
	var fresh [16]byte
	if _, err := rand.Read(fresh[:]); err != nil { t.Fatal(err) }
	nonce := hex.EncodeToString(fresh[:])
	root := exec.Command(os.Args[0], "-test.run=^TestMcrfProcessGroupLinuxChild$")
	root.Env = append(mcrfProcessChildEnvironment(os.Environ()), "MCRF_P2_GROUP_FIXTURE=root", "MCRF_PROCESS_OWNER="+nonce)
	root.Stderr = os.Stderr
	input, err := root.StdinPipe()
	if err != nil { t.Fatal(err) }
	output, err := root.StdoutPipe()
	if err != nil { input.Close(); t.Fatal(err) }
	if err = root.Start(); err != nil { input.Close(); output.Close(); t.Fatal(err) }
	lines := make(chan string, 8)
	drained := make(chan struct{})
	go func() {
		defer close(drained); defer close(lines)
		scanner := bufio.NewScanner(output)
		for scanner.Scan() { lines <- scanner.Text() }
	}()
	waited := make(chan error, 1)
	// Wait AFTER stdout is drained: exec.Wait must not close a still-owned
	// StdoutPipe early. A STOP receipt is not the process-exit barrier.
	go func() { <-drained; waited <- root.Wait() }()
	joined := false
	defer func() {
		input.Close()
		if !joined {
			select { case <-waited: case <-time.After(12*time.Second): t.Error("exact owned root retirement pending") }
		}
	}()
	line := func() string {
		select {
		case value, ok := <-lines: if !ok { t.Fatal("owned child exited before exact receipt") }; return value
		case <-time.After(5*time.Second): t.Fatal("owned child receipt deadline"); return ""
		}
	}
	announcement := strings.Split(line(), "|")
	if len(announcement)!=4 || announcement[0]!="MCRF_PROCESS_GROUP_V1" || announcement[1]!=nonce || announcement[2]!=strconv.Itoa(root.Process.Pid) {
		t.Fatal("exact private root announcement missing")
	}
	readStat := func(pid int) []string {
		data, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat",pid)); if err!=nil { t.Fatal(err) }
		end := strings.LastIndex(string(data), ") "); if end<0 { t.Fatal("owned stat format") }
		fields := strings.Fields(string(data)[end+2:]); if len(fields)<20 { t.Fatal("owned stat bound") }; return fields
	}
	pid := root.Process.Pid
	stat := readStat(pid)
	t.Logf("OWNED_GROUP_ROOT pid=%d birth=%s",pid,stat[19]) // Diagnostic identity, never signal authority.
	if stat[1]!=strconv.Itoa(os.Getpid()) || stat[2]!=strconv.Itoa(pid) || stat[19]!=announcement[3] { t.Fatal("owned root parent/PGID/birth mismatch") }
	info, err := os.Stat(fmt.Sprintf("/proc/%d",pid)); if err!=nil { t.Fatal(err) }
	if info.Sys().(*syscall.Stat_t).Uid!=uint32(os.Getuid()) { t.Fatal("owned root UID mismatch") }
	if line()!="OWNED_NO_CHILDREN" {t.Fatal("kernel did not prove zero descendants before private ACK")}
	if _, err=io.WriteString(input,"MCRF_OWNER_ACK|"+nonce+"\n"); err!=nil { t.Fatal(err) }
	leafLine:=strings.Fields(line())
	if len(leafLine)!=2 || leafLine[0]!="OWNED_LEAF" { t.Fatal("owned descendant receipt missing") }
	leafPid,err:=strconv.Atoi(leafLine[1]); if err!=nil || leafPid<=1 || leafPid==pid { t.Fatal("owned descendant PID invalid") }
	leafStat:=readStat(leafPid)
	if leafStat[1]!=strconv.Itoa(pid) || leafStat[2]!=strconv.Itoa(pid) { t.Fatal("descendant escaped the captured private group") }
	if _,err=io.WriteString(input,"STOP\n"); err!=nil { t.Fatal(err) }
	if line()!="OWNED_LEAF_STOP_HELD" { t.Fatal("owned STOP receipt missing") }
	if err=syscall.Kill(-pid,0); err!=nil { t.Fatal("held descendant group incorrectly declared gone") }
	select {case <-waited: joined=true; t.Fatal("STOP acknowledged as completed tree cleanup");default:}
	if _,err=io.WriteString(input,"RELEASE\n"); err!=nil { t.Fatal(err) }
	input.Close()
	select {case err=<-waited: joined=true; if err!=nil {t.Fatal(err)};case <-time.After(8*time.Second): t.Fatal("owned group join deadline")}
	if err=syscall.Kill(-pid,0); err!=syscall.ESRCH { t.Fatal("whole captured group death not proven") }
}
