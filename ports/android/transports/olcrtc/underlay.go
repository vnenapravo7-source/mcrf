// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"errors"
	"os"
	"strconv"
)

const underlayEnvironment = "MCRF_OLCRTC_UNDERLAY"

var bindUnderlay = func(uint64) error { return errors.New("OLC RTC physical network binding unavailable") }

func configureUnderlay() error {
	value, requested := os.LookupEnv(underlayEnvironment)
	if !requested {
		return nil
	}
	handle, err := strconv.ParseUint(value, 10, 63)
	if err != nil || handle == 0 || strconv.FormatUint(handle, 10) != value {
		return errors.New("invalid OLC RTC physical network")
	}
	return bindUnderlay(handle)
}
