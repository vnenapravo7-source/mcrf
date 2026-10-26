// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "encoding/json"
    "strings"
    "testing"
)

func TestMcrfFreshObservationEmitsOnlyAfterStopReturns(t *testing.T) {
    order:=""
    mcrfFinishObservation(func()map[string]bool{order+="capture;";return map[string]bool{}},func(){order+="stop;"},true,func(line string){
        order+="emit;"
        var flags map[string]bool
        if json.Unmarshal([]byte(strings.TrimPrefix(line,"MCRF_OPENFLUX_OBSERVATION_V1 ")),&flags)!=nil || !flags["stop_returned"] || !flags["proxy_ready_seen"] {t.Fatal("invalid finite final record")}
    })
    if order!="capture;stop;emit;" {t.Fatal("wrong observation order")}
}

func TestMcrfFreshObservationDoesNotEmitIfStopFailsToReturn(t *testing.T) {
    emitted:=false
    func(){defer func(){if recover()==nil {t.Fatal("expected synthetic stop failure")}}();mcrfFinishObservation(func()map[string]bool{return map[string]bool{}},func(){panic("synthetic stop failure")},false,func(string){emitted=true})}()
    if emitted {t.Fatal("false completed-stop record")}
}
