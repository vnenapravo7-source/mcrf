// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"io"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type mcrfOwnedBlockedInput struct {
	first sync.Once
	entered,release chan struct{}
	data bytes.Buffer
	closes atomic.Int32
}
func (p *mcrfOwnedBlockedInput) Write(data []byte)(int,error) {
	p.first.Do(func(){close(p.entered);<-p.release})
	return p.data.Write(data)
}
func (p *mcrfOwnedBlockedInput) Close()error {p.closes.Add(1);return nil}

func TestMcrfChildInputBlockedPipeCannotBlockReservedStopOrReorderProofs(t *testing.T) {
	pipe:=&mcrfOwnedBlockedInput{entered:make(chan struct{}),release:make(chan struct{})}
	var failures atomic.Int32
	w:=newMcrfChildInput(pipe,func(){failures.Add(1)})
	var releaseOnce sync.Once
	release:=func(){releaseOnce.Do(func(){close(pipe.release)})}
	defer func(){release();w.stop();select {case <-w.done:case <-time.After(3*time.Second):t.Error("owned writer cleanup pending")}}()
	expected:=make([]byte,0,131077)
	for i:=0;i<8;i++ {
		frame:=bytes.Repeat([]byte{byte('A'+i)},16384);frame[len(frame)-1]='\n'
		expected=append(expected,frame...)
		if !w.offer(frame){t.Fatal("bounded frame rejected")}
		clear(frame) // Defensive copy belongs to the writer, not the caller.
	}
	select {case <-pipe.entered:case <-time.After(3*time.Second):t.Fatal("owned writer did not enter pipe")}
	stopped:=make(chan struct{});go func(){w.stop();close(stopped)}()
	select {case <-stopped:case <-time.After(3*time.Second):t.Fatal("STOP admission blocked on stdin")}
	if w.offer([]byte("LATE\n")){t.Fatal("retired stdin admitted a proof")}
	select {case <-w.done:t.Fatal("blocked writer reported completion");default:}
	release()
	select {case <-w.done:case <-time.After(3*time.Second):t.Fatal("owned writer did not finish")}
	expected=append(expected,[]byte("STOP\n")...)
	if !bytes.Equal(expected,pipe.data.Bytes())||failures.Load()!=0||pipe.closes.Load()!=1 {t.Fatal("FIFO/STOP/close mismatch")}
}

type mcrfOwnedBrokenInput struct {held []byte;writes,closes int}
func (p *mcrfOwnedBrokenInput) Write(data []byte)(int,error){p.held=data;p.writes++;return 0,io.ErrClosedPipe}
func (p *mcrfOwnedBrokenInput) Close()error{p.closes++;return nil}

func TestMcrfChildInputFailureWipesAndNeverRetriesOrReopens(t *testing.T) {
	pipe:=&mcrfOwnedBrokenInput{};var failures atomic.Int32
	w:=newMcrfChildInput(pipe,func(){failures.Add(1)})
	if !w.offer([]byte("OWNED_PRIVATE_PROOF\n")){t.Fatal("initial offer rejected")}
	select {case <-w.done:case <-time.After(3*time.Second):t.Fatal("failed writer retained")}
	if pipe.writes!=1||pipe.closes!=1||failures.Load()!=1 {t.Fatal("failed write retried or unreported")}
	for _,value:=range pipe.held {if value!=0 {t.Fatal("proof retained after failure")}}
	w.stop()
	if w.offer([]byte("RETRY\n")){t.Fatal("failed stdin reopened")}
	w.mu.Lock();defer w.mu.Unlock()
	if len(w.queue)!=0||w.commands!=0||w.bytes!=0 {t.Fatal("failed queue retained private frames")}
}
