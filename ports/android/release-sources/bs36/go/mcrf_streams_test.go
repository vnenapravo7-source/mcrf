package main

import (
	"bytes"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"testing"
)

func TestMcrfStreamsOwnersRetryAndDuplicateClose(t *testing.T) {
	var out bytes.Buffer
	r := &mcrfStreamReporter{out: &out}
	r.report(0)
	r.report(0)
	old := r.acquire()
	replacement := r.acquire()
	old()
	old()
	replacement()
	r.report(0)
	if got, want := out.String(), "MCRF_STREAMS 0\nMCRF_STREAMS 1\nMCRF_STREAMS 2\nMCRF_STREAMS 1\nMCRF_STREAMS 0\n"; got != want {
		t.Fatalf("owner transitions %q, want %q", got, want)
	}
}

func TestMcrfStreamsConcurrentOwners(t *testing.T) {
	var out bytes.Buffer
	r := &mcrfStreamReporter{out: &out}
	r.report(0)
	var entered, done sync.WaitGroup
	entered.Add(64)
	done.Add(64)
	retire := make(chan struct{})
	for i := 0; i < 64; i++ {
		go func() {
			defer done.Done()
			release := r.acquire()
			entered.Done()
			<-retire
			release()
			release()
		}()
	}
	entered.Wait()
	close(retire)
	done.Wait()
	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	if len(lines) != 129 {
		t.Fatalf("lost or duplicate transitions: %d", len(lines))
	}
	for i, line := range lines {
		want := i
		if i > 64 {
			want = 128 - i
		}
		if line != fmt.Sprintf("MCRF_STREAMS %d", want) {
			t.Fatalf("out-of-order transition at %d: %q", i, line)
		}
	}
}

func TestMcrfStreamsChildSourceExcludesGoLeases(t *testing.T) {
	var out bytes.Buffer
	r := &mcrfStreamReporter{out: &out}
	old := r.acquire()
	r.useChild()
	mcrfForwardChildStreams(`__CSQTT_EVENT__|STREAMS|{"active":12}`, false, r)
	var done sync.WaitGroup
	for i := 0; i < 32; i++ {
		done.Add(1)
		go func() { defer done.Done(); release := r.acquire(); release(); old() }()
	}
	done.Wait()
	if r.count != 12 { t.Fatalf("Go lease corrupted absolute child count: %d", r.count) }
	if strings.Count(out.String(), "MCRF_STREAMS 12\n") != 1 { t.Fatal(out.String()) }
}

func TestMcrfStreamsChildEventsAndPrivacy(t *testing.T) {
	for _, sampled := range []bool{false, true} {
		var out bytes.Buffer
		r := &mcrfStreamReporter{out: &out}
		r.report(0)
		kind := "STREAMS"
		if sampled {
			kind = "STATS"
		}
		prefix := "__CSQTT_EVENT__|" + kind + "|"
		for _, payload := range []string{`{}`, `{"active":null}`, `{"active":-1}`, `{"active":65536}`, `{"active":1.5}`, `{"active":"2"}`, `{"active":2} trailing`, `{"active":999999999999999999999999}`} {
			if !mcrfForwardChildStreams(prefix+payload, sampled, r) {
				t.Fatal("recognized invalid event fell through")
			}
		}
		for _, line := range []string{
			`__CSQTT_EVENT__|READY|{"worker":7}`,
			"secret " + prefix + `{"active":80}`,
			"MCRF_STREAMS 90",
		} {
			if mcrfForwardChildStreams(line, sampled, r) {
				t.Fatal("non-count lifecycle or embedded marker accepted")
			}
		}
		for _, n := range []int{2, 2, 1, 0, 0} {
			mcrfForwardChildStreams(prefix+`{"active":`+strconv.Itoa(n)+`,"address":"private","token":"private"}`, sampled, r)
		}
		r.report(0) // child has been reaped and scanner drained
		if got := out.String(); got != "MCRF_STREAMS 0\nMCRF_STREAMS 2\nMCRF_STREAMS 1\nMCRF_STREAMS 0\n" {
			t.Fatalf("unexpected or private stdout: %q", got)
		}
	}
}
