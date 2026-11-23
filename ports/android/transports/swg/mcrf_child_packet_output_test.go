package main

import (
	"context"
	"strings"
	"testing"
)

func TestMcrfChildPrivatePacketKeyNeverCrossesOutputBoundary(t *testing.T) {
	key := strings.Repeat("a9", 32)
	env := []string{"OTHER=value", "MCRF_CSQTT_PRIVATE_UDP_KEY_V1=" + key}
	if mcrfChildPacketKey(env) != key {
		t.Fatal("Private packet key unavailable to the output filter")
	}
	for _, line := range []string{
		"transport error: MCRF_CSQTT_PRIVATE_UDP_KEY_V1=example",
		"transport error: MCRF_PACKET_BRIDGE_AUTH=example",
		"transport error: {\"MCRF_CSQTT_PRIVATE_UDP_KEY_V1\":\"example\"}",
		"transport error: " + key,
		"transport error: [" + strings.ToUpper(key) + "]",
	} {
		if !mcrfChildHasPrivatePacketData(line, key) {
			t.Fatal("Private packet output was not suppressed")
		}
	}
	benign := "transport error: MCRF_CSQTT_PRIVATE_UDP_KEY_V1: capability unavailable"
	if mcrfChildHasPrivatePacketData(benign, key) || mcrfPublicChildFailure(nil, benign) != benign {
		t.Fatal("Public capability or useful error was suppressed")
	}
	lines := strings.Join([]string{
		"__CSQTT_EVENT__|CONFIG|{\"config\":\"" + key + "\"}",
		"__CSQTT_EVENT__|READY|{\"worker\":0}",
		"__CSQTT_EVENT__|CONFIG|{\"config\":\"\\u0061" + key[1:] + "\"}",
		"__CSQTT_EVENT__|CONFIG|{\"config\":\"MCRF_CSQTT_PRIVATE_UDP_KEY_V1 capability enabled\"}",
	}, "\n")
	configs := make(chan string, 1)
	scanCSQTTChildOutput(context.Background(), &bridgeConfig{}, strings.NewReader(lines), configs, func(string) {}, key)
	select {
	case config := <-configs:
		if config != "MCRF_CSQTT_PRIVATE_UDP_KEY_V1 capability enabled" {
			t.Fatal("Unexpected child config was admitted")
		}
	default:
		t.Fatal("Public config did not survive private output rejection")
	}
}
