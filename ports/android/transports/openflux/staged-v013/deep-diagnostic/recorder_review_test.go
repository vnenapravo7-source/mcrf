// SPDX-License-Identifier: GPL-3.0-or-later
// PREPARED NOTRUN; recorder models only, no actual provider/session hooks.
package mcrfdiag

import (
    "encoding/json"
    "strings"
    "sync"
    "sync/atomic"
    "testing"
)

func flags(t *testing.T, line string) map[string]bool {
    t.Helper()
    if !strings.HasPrefix(line, PrefixV3) || len(line)+1 > 1024 { t.Fatal("prefix/line bound including newline") }
    for _, value := range line { if value > 127 { t.Fatal("non-ASCII record") } }
    var result map[string]bool
    if json.Unmarshal([]byte(strings.TrimPrefix(line, PrefixV3)), &result) != nil || len(result) != 37 { t.Fatal("fixed Boolean schema") }
    for _, name := range names { if _, present := result[name]; !present { t.Fatal("missing fixed field") } }
    return result
}

func TestMcrfFreshDeepRecorderExactFalseAndTrueSchema(t *testing.T) {
    for _, marked := range []bool{false, true} {
        run := NewV3()
        if marked { for event := Event(0); event < eventCount; event++ { run.Mark(event) } }
        for _, value := range flags(t, run.Finish()) { if value != marked { t.Fatal("wrong fixed event value") } }
        if run.Finish() != "" || run.String() != "OpenFluxDiagnosticRun(redacted)" { t.Fatal("freeze/redaction") }
    }
}

func TestMcrfFreshDeepRecorderRetainedOldOwnerCannotAffectNewRun(t *testing.T) {
    old := NewV3(); old.Mark(SessDecryptFailed); before := flags(t, old.Finish())
    next := NewV3(); old.Mark(SessHelloAccepted); next.Mark(SessHelloAccepted)
    after := flags(t, next.Finish())
    if !before["sess_dec_fail"] || before["sess_hello_ok"] || after["sess_dec_fail"] || !after["sess_hello_ok"] || old.Finish() != "" {
        t.Fatal("lifetime contamination")
    }
}

func TestMcrfFreshDeepRecorderNilAndOutOfRangeAreNoops(t *testing.T) {
    var absent *Run; absent.Mark(SessDecryptOK)
    if absent.Finish() != "" { t.Fatal("invented report") }
    run := NewV3()
    for _, event := range []Event{eventCount, 63, 64, ^Event(0)} { run.Mark(event) }
    for _, value := range flags(t, run.Finish()) { if value { t.Fatal("invalid event admitted") } }
}

func TestMcrfFreshDeepRecorderConcurrentMarksAndOneFinishWinner(t *testing.T) {
    run := NewV3(); var marking sync.WaitGroup
    for event := Event(0); event < eventCount; event++ {
        marking.Add(1); go func(event Event) { defer marking.Done(); for i := 0; i < 10; i++ { run.Mark(event) } }(event)
    }
    marking.Wait()
    var finishing sync.WaitGroup; var winners atomic.Int32; var record string; var lock sync.Mutex
    for i := 0; i < 32; i++ {
        finishing.Add(1); go func() { defer finishing.Done(); if line := run.Finish(); line != "" {
            winners.Add(1); lock.Lock(); record = line; lock.Unlock()
        } }()
    }
    finishing.Wait()
    if winners.Load() != 1 { t.Fatal("more than one report") }
    for _, value := range flags(t, record) { if !value { t.Fatal("joined mark lost") } }
}

func TestMcrfFreshDeepRecorderIndependentLiveOwnersDoNotShareBits(t *testing.T) {
    one, two := NewV3(), NewV3(); one.Mark(DemuxSession); two.Mark(DemuxClassic)
    a, b := flags(t, one.Finish()), flags(t, two.Finish())
    if !a["demux_session"] || a["demux_classic"] || b["demux_session"] || !b["demux_classic"] { t.Fatal("global owner state") }
}
