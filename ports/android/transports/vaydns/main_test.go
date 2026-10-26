package main

import (
	"strings"
	"testing"
)

func TestMcrfVayConfigStrictBoundary(t *testing.T) {
	base := `{"domain":"t.example.test","key":"` + strings.Repeat("01", 32) + `","udp":"1.1.1.1:53","socks":"127.0.0.1:12345","socks_user":"fixture","socks_pass":"fixture-secret"}`
	if _, err := readConfig(strings.NewReader(base)); err != nil {
		t.Fatal(err)
	}
	cases := []string{
		base + ` {}`,
		strings.Replace(base, `"udp":"1.1.1.1:53"`, `"udp":"1.1.1.1:53","udp":"8.8.8.8:53"`, 1),
		strings.Replace(base, `"udp":"1.1.1.1:53"`, `"udp":"resolver.test:53"`, 1),
		strings.Replace(base, `127.0.0.1:12345`, `0.0.0.0:12345`, 1),
		strings.Replace(base, `"udp":"1.1.1.1:53"`, `"udp":"1.1.1.1:53","doh":"https://resolver.test/dns-query"`, 1),
		strings.Replace(base, `"udp":"1.1.1.1:53"`, `"doh":"http://resolver.test/dns-query"`, 1),
		strings.Replace(base, `"udp":"1.1.1.1:53"`, `"doh":"https://user:password@resolver.test/dns-query"`, 1),
		strings.Replace(base, `"domain"`, `"unknown"`, 1),
		strings.Replace(base, `t.example.test`, `-t.example.test`, 1),
		strings.Repeat(" ", 65537),
	}
	for i, value := range cases {
		if _, err := readConfig(strings.NewReader(value)); err == nil {
			t.Fatalf("invalid case %d accepted", i)
		}
	}
}
