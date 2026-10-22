// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
    "errors"
    "github.com/bogdanfinn/tls-client/profiles"
)

// Set once during private bridge configuration, before native worker creation.
var mcrfQwdttFingerprint="chrome"
var mcrfQwdttChromeCalls=vkCallsProfile
func mcrfQwdttFingerprintValid(value string) bool {return value==""||value=="chrome"||value=="firefox"||value=="safari"}
func mcrfQwdttSetFingerprint(value string) error {
    if !mcrfQwdttFingerprintValid(value) {return errors.New("unsupported QWDTT fingerprint")}
    if value=="" {value="chrome"}
    mcrfQwdttFingerprint=value
    if value!="chrome" {vkCallsProfile=mcrfQwdttBrowserProfile()}else{vkCallsProfile=mcrfQwdttChromeCalls}
    return nil
}
func mcrfQwdttTLSProfile() profiles.ClientProfile {
    switch mcrfQwdttFingerprint {
    case "firefox":return profiles.Firefox_148
    case "safari":return profiles.Safari_16_0
    default:return profiles.Chrome_146
    }
}
func mcrfQwdttFingerprintLabel() string {
    switch mcrfQwdttFingerprint {
    case "firefox":return "Firefox 148"
    case "safari":return "Safari 16.0"
    default:return "Chrome 146"
    }
}
func mcrfQwdttBrowserProfile() Profile {
    if mcrfQwdttFingerprint=="firefox" {
        return Profile{UserAgent:"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0"}
    }
    return Profile{UserAgent:"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15"}
}
