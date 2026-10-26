// SPDX-License-Identifier: GPL-3.0-or-later
package main

import "testing"

func TestMcrfFreshNoPendingPageIsNotFailure(t *testing.T) {
    for _, own := range []bool{true, false} {
        if mcrfFreshProviderFailure("", "", own) != "" { t.Fatal("invented a pending check") }
    }
}

func TestMcrfFreshOwnPageIsNotPassedToVkBrowser(t *testing.T) {
    for _, page := range [][2]string{{"http://127.0.0.1:12345/private?token=synthetic", ""}, {"", "<html>synthetic-secret</html>"}} {
        if mcrfFreshProviderFailure(page[0], page[1], true) != "setup_page_unsupported" { t.Fatal("own page was not rejected finitely") }
    }
}

func TestMcrfFreshExternalCaptchaEmitsOnlyFiniteCode(t *testing.T) {
    for _, url := range []string{"https://example.invalid/check?token=synthetic", "untrusted\ntext"} {
        if mcrfFreshProviderFailure(url, "", false) != "provider_verification_required" { t.Fatal("provider contents reached diagnostics") }
    }
}
