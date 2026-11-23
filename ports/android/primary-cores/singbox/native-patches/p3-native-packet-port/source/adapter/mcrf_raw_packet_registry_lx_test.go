//go:build with_lx_raw_packet && (linux || android)

package adapter

import (
	"fmt"
	"net/netip"
	"testing"
)

type registryEndpointFixture struct {
	OriginalPacketEndpoint
	id string
}

func (e *registryEndpointFixture) Identity() string       { return e.id }
func (*registryEndpointFixture) Generation() uint64       { return 1 }
func (*registryEndpointFixture) LocalAddress() netip.Addr { return netip.MustParseAddr("10.0.0.2") }
func (*registryEndpointFixture) MTU() int                 { return 1400 }
func TestOriginalRegistryDisjointSelectorsAndNoReuse(t *testing.T) {
	r := NewOriginalPacketEndpointRegistry()
	defer r.Close()
	first := &registryEndpointFixture{id: "first"}
	if err := r.Register("one", first); err != nil {
		t.Fatal(err)
	}
	if err := r.Register("alias", first); err == nil {
		t.Fatal("endpoint aliased")
	}
	if err := r.Unregister("one"); err != nil {
		t.Fatal(err)
	}
	if a, b := r.OriginalPacketSelectorRange("one"); a != 0 || b != 0 {
		t.Fatal("retired range exposed")
	}
	for i := 1; i < 8; i++ {
		tag := fmt.Sprint(i)
		if err := r.Register(tag, &registryEndpointFixture{id: tag}); err != nil {
			t.Fatal(err)
		}
		start, count := r.OriginalPacketSelectorRange(tag)
		if start != uint16(1024+i*1872) || count != 1872 || uint32(start)+uint32(count) > 16000 {
			t.Fatal("selector overlap or fallback range collision")
		}
	}
	if err := r.Register("ninth", &registryEndpointFixture{id: "ninth"}); err == nil {
		t.Fatal("reused retired selector")
	}
	r.Close()
	if r.HasOriginalPacketEndpoints() {
		t.Fatal("closed endpoints visible")
	}
	if a, b := r.OriginalPacketSelectorRange("1"); a != 0 || b != 0 {
		t.Fatal("closed range exposed")
	}
}
