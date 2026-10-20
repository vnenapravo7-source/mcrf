package main

import (
	"bytes"
	"strings"
	"testing"
)

func TestFixtureDNS(t *testing.T) {
	nonce := strings.Repeat("a", 32)
	for _, kind := range []byte{1, 28} {
		query := []byte{0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 32}
		query = append(query, []byte(nonce)...)
		query = append(query, 7)
		query = append(query, []byte("invalid")...)
		query = append(query, 0, 0, kind, 0, 1)
		reply := fixtureDNS(query, nonce)
		size := 4
		if kind == 28 {
			size = 16
		}
		if len(reply) != len(query)+12+size || !bytes.Equal(reply[:2], query[:2]) || reply[7] != 1 {
			t.Fatal("bad reply")
		}
		for i := 2; i < len(query); i++ {
			bad := append([]byte(nil), query...)
			bad[i] ^= 0x80
			if fixtureDNS(bad, nonce) != nil {
				t.Fatalf("accepted changed question byte %d", i)
			}
		}
		for i := 0; i < len(query); i++ {
			if fixtureDNS(query[:i], nonce) != nil {
				t.Fatal("accepted truncation")
			}
		}
		if fixtureDNS(append(query, 0), nonce) != nil || fixtureDNS(query, strings.Repeat("b", 32)) != nil {
			t.Fatal("accepted other nonce or trailing bytes")
		}
	}
}
