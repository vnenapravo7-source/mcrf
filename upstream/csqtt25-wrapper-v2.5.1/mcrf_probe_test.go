// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"reflect"
	"testing"
)

func TestMcrfCaptchaProbeCancelsOnlyNoninteractiveProbe(t *testing.T) {
	for _, probe := range []bool{false, true} {
		for _, interactive := range []bool{false, true} {
			ctx, cancel := context.WithCancel(context.Background())
			var output bytes.Buffer
			rejected := mcrfRejectCaptchaProbe(probe, interactive, cancel, &output)
			want := probe && !interactive
			if rejected != want || (ctx.Err() != nil) != want {
				t.Fatal("probe cancellation did not respect interactive ownership")
			}
			expected := ""
			if want {
				expected = "MCRF_CAPTCHA_REQUIRED\n"
			}
			if output.String() != expected {
				t.Fatal("probe emitted data beyond its fixed signal")
			}
			cancel()
		}
	}
}

func TestMcrfCaptchaLegacyCapabilityHasSingleOwnedValue(t *testing.T) {
	input := []string{"OTHER=value", "MCRF_CAPTCHA_IDS=0", "mcrf_captcha_ids=unexpected"}
	before := append([]string(nil), input...)
	got := mcrfLegacyCaptchaEnv(input)
	if !reflect.DeepEqual(got, []string{"OTHER=value", "MCRF_CAPTCHA_IDS=1"}) || !reflect.DeepEqual(input, before) {
		t.Fatal("legacy capability was ambiguous or mutated caller environment")
	}
}
