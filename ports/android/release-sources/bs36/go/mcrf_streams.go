// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
)

// Each process runs either its Go dispatcher or one owned CSQTT child.
// Hold the lock through the one-line write so concurrent arrivals/departures
// cannot reorder the count on stdout. Never include worker IDs or native text.
type mcrfStreamReporter struct {
	mu    sync.Mutex
	out   io.Writer
	count int
	seen  bool
	child bool
}

// Select the child source before spawning/scanning it. Go session leases may
// still retire, but cannot modify an absolute child measurement.
func (r *mcrfStreamReporter) useChild() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.child = true
	r.reportLocked(0)
}

var mcrfNativeStreams = &mcrfStreamReporter{out: os.Stdout, count: -1}

func (r *mcrfStreamReporter) reportLocked(count int) {
	if count < 0 || (r.seen && count == r.count) {
		return
	}
	r.count, r.seen = count, true
	fmt.Fprintf(r.out, "MCRF_STREAMS %d\n", count)
}

func (r *mcrfStreamReporter) report(count int) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.reportLocked(count)
}

// A lease belongs to a successfully registered TURN data stream. A retry
// receives a new lease; its predecessor can retire only its own lease once.
func (r *mcrfStreamReporter) acquire() func() {
	r.mu.Lock()
	if r.child {
		r.mu.Unlock()
		return func() {}
	}
	if !r.seen {
		r.count = 0
	}
	r.reportLocked(r.count + 1)
	r.mu.Unlock()
	var once sync.Once
	return func() {
		once.Do(func() {
			r.mu.Lock()
			defer r.mu.Unlock()
			if r.child { return }
			r.reportLocked(r.count - 1)
		})
	}
}

// Legacy/Rebirth emit STREAMS at their actual ActiveConnection RAII boundary.
// The pinned 2.5.1 binary exposes only 500ms STATS snapshots: never infer a
// count from READY IDs, configured workers, throughput, or SOCKS connections.
// Return true for recognized events even if malformed, keeping raw child
// payloads away from the public failure/log path.
func mcrfForwardChildStreams(line string, sampled bool, r *mcrfStreamReporter) bool {
	prefix := "__CSQTT_EVENT__|STREAMS|"
	if sampled {
		prefix = "__CSQTT_EVENT__|STATS|"
	}
	if !strings.HasPrefix(line, prefix) {
		return false
	}
	if len(line) > 4096 {
		return true
	}
	var event struct {
		Active *int `json:"active"`
	}
	if json.Unmarshal([]byte(line[len(prefix):]), &event) == nil && event.Active != nil && *event.Active >= 0 && *event.Active <= 65535 {
		r.report(*event.Active)
	}
	return true
}
