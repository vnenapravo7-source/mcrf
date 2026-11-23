// SPDX-License-Identifier: GPL-3.0-or-later
package main

import mobile "openflux-mobile"

// A finite diagnostic reaches the connection card without leaking a page,
// cookies or account values. Unreviewed UI contracts are never advertised.
func init() {
	stagedProviderCheck = func() string {
		if mobile.PendingPromptURL() == "" && mobile.PendingPromptHTML() == "" {
			return ""
		}
		if mobile.PendingPromptOwn() {
			return "setup_page_unsupported"
		}
		if mobile.PendingPromptProxy() != "" {
			return "exit_verification_proxy_unsupported"
		}
		return "provider_verification_required"
	}
}
