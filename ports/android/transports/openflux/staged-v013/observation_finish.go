// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "encoding/json"
    "fmt"
    mobile "openflux-mobile"
)

func init() {
    stagedObservationFinish = func(stop func(), ready bool) {
        mcrfFinishObservation(mobile.McrfBeforeStopObservation, stop, ready, func(line string){fmt.Println(line)})
    }
}

func mcrfFinishObservation(capture func()map[string]bool, stop func(), ready bool, emit func(string)) {
    flags := capture() // Before StopProxy clears the manager/transport references.
    stop() // No final record if StopProxy does not return.
    flags["stop_returned"], flags["proxy_ready_seen"] = true, ready
    data, err := json.Marshal(flags)
    if err == nil && len(data)<=2048 { emit("MCRF_OPENFLUX_OBSERVATION_V1 "+string(data)) }
}
