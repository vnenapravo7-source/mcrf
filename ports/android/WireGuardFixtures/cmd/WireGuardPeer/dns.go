package main

import "bytes"

// Answers only the fresh fixture nonce under .invalid; no recursion or public DNS.
func fixtureDNS(query []byte, nonce string) []byte {
	if len(nonce) != 32 || len(query) != 58 {
		return nil
	}
	question := append([]byte{32}, []byte(nonce)...)
	question = append(question, 7)
	question = append(question, []byte("invalid")...)
	question = append(question, 0)
	if !bytes.Equal(query[2:12], []byte{1, 0, 0, 1, 0, 0, 0, 0, 0, 0}) || !bytes.Equal(query[12:54], question) || query[54] != 0 || (query[55] != 1 && query[55] != 28) || !bytes.Equal(query[56:], []byte{0, 1}) {
		return nil
	}
	address := []byte{10, 77, 0, 1}
	if query[55] == 28 {
		address = []byte{0xfd, 0x77, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
	}
	reply := append([]byte(nil), query...)
	reply[2] = 0x81
	reply[3] = 0x80
	reply[7] = 1
	reply = append(reply, 0xc0, 12, 0, query[55], 0, 1, 0, 0, 0, 0, 0, byte(len(address)))
	return append(reply, address...)
}
