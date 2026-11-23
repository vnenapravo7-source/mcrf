// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"io"
	"sync"
)

// One bounded FIFO per exact owned child. STOP has reserved admission even when
// its stdin is full. Only this writer touches the pipe; the parent still waits
// for real child death separately. A write failure is never retried/replayed.
type mcrfChildInput struct {
	mu sync.Mutex
	changed *sync.Cond
	output io.WriteCloser
	failure func()
	queue [][]byte
	commands, bytes int
	sealed bool
	done chan struct{}
}

func newMcrfChildInput(output io.WriteCloser, failure func()) *mcrfChildInput {
	w:=&mcrfChildInput{output:output,failure:failure,done:make(chan struct{})}
	w.changed=sync.NewCond(&w.mu)
	go w.run()
	return w
}

func (w *mcrfChildInput) offer(data []byte) bool {
	if len(data)<1 || len(data)>16384 || data[len(data)-1]!='\n' { return false }
	w.mu.Lock();defer w.mu.Unlock()
	if w.sealed || w.commands>=32 || w.bytes+len(data)>131072 { return false }
	w.queue=append(w.queue,append([]byte(nil),data...));w.commands++;w.bytes+=len(data)
	w.changed.Signal()
	return true
}

func (w *mcrfChildInput) stop() {
	w.mu.Lock();w.sealed=true;w.changed.Broadcast();w.mu.Unlock()
}

func (w *mcrfChildInput) run() {
	defer func(){
		w.mu.Lock();w.sealed=true
		for _,data:=range w.queue {clear(data)}
		w.queue=nil;w.commands=0;w.bytes=0;w.changed.Broadcast();w.mu.Unlock()
		_ = w.output.Close()
		close(w.done)
	}()
	write:=func(data []byte) bool {
		n,err:=w.output.Write(data)
		if err!=nil || n!=len(data) { if w.failure!=nil {w.failure()};return false }
		return true
	}
	for {
		w.mu.Lock()
		for len(w.queue)==0&&!w.sealed {w.changed.Wait()}
		if len(w.queue)==0 {
			w.mu.Unlock();write([]byte("STOP\n"));return
		}
		data:=w.queue[0];w.queue[0]=nil;w.queue=w.queue[1:];w.mu.Unlock()
		ok:=write(data)
		w.mu.Lock();w.commands--;w.bytes-=len(data);w.mu.Unlock()
		clear(data)
		if !ok {return}
	}
}
