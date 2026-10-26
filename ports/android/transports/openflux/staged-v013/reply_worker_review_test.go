// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "encoding/base64"
    "strings"
    "sync/atomic"
    "testing"
    "time"
    mobile "openflux-mobile"
)

func mcrfWorkerCommand(id string) string {
    return "OPENFLUX_CAPTCHA_REPLY|"+id+"|"+base64.StdEncoding.EncodeToString([]byte("cookie=synthetic-private"))
}
func mcrfWaitWorker(t *testing.T,state *mcrfVerificationRun) {
    t.Helper()
    deadline:=time.Now().Add(time.Second)
    for state.busy.Load() {
        if time.Now().After(deadline) {t.Fatal("synthetic worker did not drain")}
        time.Sleep(time.Millisecond)
    }
}

func TestMcrfFreshRepliesAreSerializedWithOneBoundedNextSlot(t *testing.T) {
    entered:=make(chan struct{});release:=make(chan struct{})
    var active atomic.Int32;var overlap atomic.Bool
    ids:=[]string{};lines:=[]string{}
    state:=&mcrfVerificationRun{enabled:true}
    state.apply=func(id,cookies string)(string,*mobile.McrfVerificationFailure){
        if active.Add(1)!=1 {overlap.Store(true)}
        defer active.Add(-1)
        ids=append(ids,id)
        if len(ids)==1 {close(entered);<-release}
        return "accepted",nil
    }
    state.emit=func(line string){lines=append(lines,line)}
    first,second:=strings.Repeat("a",32),strings.Repeat("b",32)
    if !state.input(mcrfWorkerCommand(first)) {t.Fatal("first denied")}
    select {case <-entered:case <-time.After(time.Second):close(release);t.Fatal("worker not started")}
    if !state.input(mcrfWorkerCommand(second)) {close(release);t.Fatal("one next reply denied")}
    if state.input(mcrfWorkerCommand(strings.Repeat("c",32))) {close(release);t.Fatal("unbounded queue")}
    close(release);mcrfWaitWorker(t,state)
    if overlap.Load() || len(ids)!=2 || ids[0]!=first || ids[1]!=second || len(lines)!=2 {t.Fatal("serialization/order changed")}
    for _,line:=range lines {if strings.Contains(line,"synthetic-private") {t.Fatal("cookie in output")}}
}

func TestMcrfFreshObsoleteReplyEmitsNoFalseSuccessOrFailureAck(t *testing.T) {
    entered:=make(chan struct{});release:=make(chan struct{})
    lines:=[]string{}
    state:=&mcrfVerificationRun{enabled:true}
    state.apply=func(id,cookies string)(string,*mobile.McrfVerificationFailure){
        if id==strings.Repeat("a",32) {close(entered);<-release;return "stale_or_unsupported",nil}
        return "accepted",nil
    }
    state.emit=func(line string){lines=append(lines,line)}
    if !state.input(mcrfWorkerCommand(strings.Repeat("a",32))) {t.Fatal("first denied")}
    select {case <-entered:case <-time.After(time.Second):close(release);t.Fatal("worker not started")}
    if !state.input(mcrfWorkerCommand(strings.Repeat("b",32))) {close(release);t.Fatal("next denied")}
    close(release);mcrfWaitWorker(t,state)
    if len(lines)!=1 || !strings.Contains(lines[0],strings.Repeat("b",32)) || state.failed.Load()!=nil {t.Fatal("obsolete reply poisoned replacement")}
}

func TestMcrfFreshDrainedWorkerAcceptsLaterReplyWithoutOrphanSlot(t *testing.T) {
    var calls atomic.Int32
    state:=&mcrfVerificationRun{enabled:true,apply:func(string,string)(string,*mobile.McrfVerificationFailure){calls.Add(1);return "accepted",nil},emit:func(string){}}
    for _,id:=range []string{strings.Repeat("a",32),strings.Repeat("b",32),strings.Repeat("c",32)} {
        if !state.input(mcrfWorkerCommand(id)) {t.Fatal("drained worker denied next reply")}
        mcrfWaitWorker(t,state)
    }
    state.mu.Lock();queued:=state.queued;state.mu.Unlock()
    if queued!=nil || state.busy.Load() || calls.Load()!=3 {t.Fatal("orphaned reply")}
}

func TestMcrfFreshStoppedWorkerDiscardsQueuedReplyAndLateResult(t *testing.T) {
    entered:=make(chan struct{});release:=make(chan struct{})
    var calls atomic.Int32;var emitted atomic.Int32
    state:=&mcrfVerificationRun{enabled:true,apply:func(string,string)(string,*mobile.McrfVerificationFailure){
        calls.Add(1);close(entered);<-release;return "accepted",nil
    },emit:func(string){emitted.Add(1)}}
    if !state.input(mcrfWorkerCommand(strings.Repeat("a",32))) {t.Fatal("first denied")}
    select {case <-entered:case <-time.After(time.Second):close(release);t.Fatal("worker not started")}
    if !state.input(mcrfWorkerCommand(strings.Repeat("b",32))) {close(release);t.Fatal("next denied")}
    state.mu.Lock();pending:=state.queued;state.mu.Unlock()
    state.waiting.Store(true);state.stop();state.stop()
    state.mu.Lock();cleared:=state.queued==nil && pending!=nil && pending.cookies=="";state.mu.Unlock()
    close(release);mcrfWaitWorker(t,state)
    if !cleared || state.waiting.Load() || calls.Load()!=1 || emitted.Load()!=0 {t.Fatal("STOP retained/replayed a queued response or emitted a late result")}
}

func TestMcrfFreshClosedQueueRejectsNewRepliesAndCancellation(t *testing.T) {
    var calls atomic.Int32
    state:=&mcrfVerificationRun{enabled:true,apply:func(string,string)(string,*mobile.McrfVerificationFailure){calls.Add(1);return "accepted",nil}}
    state.stop()
    id:=strings.Repeat("a",32)
    if state.input(mcrfWorkerCommand(id)) || state.input("OPENFLUX_CAPTCHA_CANCEL|"+id) {t.Fatal("closed queue admitted input")}
    state.mu.Lock();empty:=state.queued==nil;state.mu.Unlock()
    if !empty || state.busy.Load() || calls.Load()!=0 {t.Fatal("closed queue started work")}
}
