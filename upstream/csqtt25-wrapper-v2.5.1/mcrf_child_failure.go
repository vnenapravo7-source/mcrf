// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"regexp"
	"sort"
	"strings"
	"unicode"
)

var mcrfFailureWords = regexp.MustCompile(`(?i)error|fatal|panic|failed|failure|invalid|denied|not found|no such|cannot|unavailable|refused|timeout|timed out|address already in use|network is unreachable|broken pipe|unsupported|ошиб|не удалось`)
var mcrfFailureSecret = regexp.MustCompile(`(?i)(password|passwd|pass|token|secret|hashes?|device_id|salt|authorization|cookie|socks_user|credential|private_key)(["']?\s*[:=]\s*["']?)[^\s,;"']+`)
var mcrfFailureURL = regexp.MustCompile(`(?i)[a-z][a-z0-9+.-]*://[^\s]+`)
var mcrfFailureANSI = regexp.MustCompile("\x1b\\[[0-?]*[ -/]*[@-~]")
var mcrfPrivateOutput = regexp.MustCompile(`(?i)(?:^|\s)(?:MCRF_)?CONFIG[:=|]`)

// Errors alone cross the wrapper boundary. This is not a raw native log switch.
func mcrfPublicChildFailure(c *bridgeConfig, raw string) string {
	if len(raw) > 4096 { return "" }
	text := strings.TrimSpace(mcrfFailureANSI.ReplaceAllString(raw, ""))
	structuredError := false
	if strings.Contains(text, "__CSQTT_EVENT__|") {
		const marker = "__CSQTT_EVENT__|ERROR|"
		i := strings.Index(text, marker)
		if i < 0 { return "" }
		var event struct { Message string `json:"message"` }
		if json.Unmarshal([]byte(text[i+len(marker):]), &event) != nil { return "" }
		text = event.Message
		structuredError = true
	}
	upper := strings.ToUpper(text)
	if text == "" || mcrfPrivateOutput.MatchString(text) || strings.Contains(upper, "CAPTCHA_") || strings.Contains(upper, "PRIVATE KEY") || strings.HasPrefix(upper, "MCRF_") || strings.HasPrefix(text, "{") || (!structuredError && !mcrfFailureWords.MatchString(text)) { return "" }
	if c != nil {
		secrets := append([]string{c.Password, c.DeviceID, c.SOCKSUser, c.SOCKSPass, c.VKRuntimeToken}, c.Hashes...)
		secrets = append(secrets, c.DiagnosticSecrets...)
		sort.Slice(secrets, func(i,j int)bool{return len(secrets[i])>len(secrets[j])})
		for _, secret := range secrets { if secret != "" { text = strings.ReplaceAll(text, secret, "[redacted]") } }
	}
	text = mcrfFailureURL.ReplaceAllString(text, "[URL redacted]")
	text = mcrfFailureSecret.ReplaceAllString(text, "${1}${2}[redacted]")
	text = strings.Map(func(r rune)rune{if unicode.IsControl(r)||unicode.In(r,unicode.Cf){return ' '};return r},text)
	characters := []rune(text)
	if len(characters)>400 { text=string(characters[:400]) }
	return text
}
