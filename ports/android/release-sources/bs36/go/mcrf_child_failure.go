// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"
)

var mcrfFailureWords = regexp.MustCompile(`(?i)error|fatal|panic|failed|failure|invalid|denied|not found|no such|cannot|unavailable|refused|timeout|timed out|address already in use|network is unreachable|broken pipe|unsupported|ошиб|не удалось`)
var mcrfFailureSecret = regexp.MustCompile(`(?i)(password|passwd|pass|token|secret|hashes?|device_id|salt|authorization|cookie|socks_user|credential|private_key)(["']?\s*[:=]\s*["']?)[^\s,;"']+`)
var mcrfFailureURL = regexp.MustCompile(`(?i)[a-z][a-z0-9+.-]*://[^\s]+`)
var mcrfFailureANSI = regexp.MustCompile("\x1b\\[[0-?]*[ -/]*[@-~]")
var mcrfPrivateOutput = regexp.MustCompile(`(?i)(?:^|\s)(?:MCRF_)?CONFIG[:=|]`)
var mcrfVKLimitEvent = regexp.MustCompile(`^MCRF_VK_LIMIT\|(FLOOD|RATE|CAPTCHA)\|([1-9][0-9]{0,3})$`)

var mcrfPrivatePacketOutput = regexp.MustCompile(`(?i)\bMCRF_(?:CSQTT_PRIVATE_UDP_(?:KEY_V1|REQUIRED)|PACKET_BRIDGE_AUTH)(?:["\x27]?\s*=|["\x27]\s*:)`)

func mcrfChildPacketKey(env []string) string {
	for _, value := range env {
		if key, ok := strings.CutPrefix(value, "MCRF_CSQTT_PRIVATE_UDP_KEY_V1="); ok {
			return key
		}
	}
	return ""
}

func mcrfChildHasPrivatePacketData(line, key string) bool {
	line = mcrfFailureANSI.ReplaceAllString(line, "")
	return mcrfPrivatePacketOutput.MatchString(line) ||
		(key != "" && len(line) >= len(key) && strings.Contains(strings.ToLower(line), key))
}

// Errors alone cross the wrapper boundary. This is not a raw native log switch.
func mcrfPublicChildFailure(c *bridgeConfig, raw string) string {
	if len(raw) > 4096 { return "" }
	text := strings.TrimSpace(mcrfFailureANSI.ReplaceAllString(raw, ""))
	if mcrfPrivatePacketOutput.MatchString(text) { return "" }
	// The ONLY allowed MCRF control record in failure output: a fixed kind
	// and bounded duration, never hashes, profiles or provider text.
	if fields := mcrfVKLimitEvent.FindStringSubmatch(text); fields != nil {
		if seconds, err := strconv.Atoi(fields[2]); err == nil && seconds <= 3600 { return text }
		return ""
	}
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
	if mcrfPrivatePacketOutput.MatchString(text) { return "" }
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
