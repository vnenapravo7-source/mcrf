//go:build with_lx_carrier_stack && with_lx_raw_packet && (linux || android)

package adapter

import (
	"fmt"
	"sync/atomic"
	"testing"
)

type carrierRegistryFixture struct{ registryEndpointFixture }

func (*carrierRegistryFixture) OriginalCarrierStack() bool { return true }

func TestOriginalCarrierRegistryDoesNotConsumeRawSelectors(t *testing.T) {
	r := NewOriginalPacketEndpointRegistry()
	defer r.Close()
	for i := 0; i < 16; i++ {
		tag := fmt.Sprint("stack", i)
		if err := r.Register(tag, &carrierRegistryFixture{registryEndpointFixture{id: tag}}); err != nil {
			t.Fatal(err)
		}
		if start, count := r.OriginalPacketSelectorRange(tag); start != 0 || count != 0 {
			t.Fatal("virtual stack consumed raw selectors")
		}
	}
	if err := r.Register("raw", &registryEndpointFixture{id: "raw"}); err != nil {
		t.Fatal(err)
	}
	if start, count := r.OriginalPacketSelectorRange("raw"); start != 1024 || count != 1872 {
		t.Fatal("raw range changed")
	}
}

func TestOriginalCarrierRegistryReportsFailureOnceAndRetiresReporter(t *testing.T) {
	r := NewOriginalPacketEndpointRegistry()
	var calls atomic.Uint32
	r.SetOriginalCarrierFailureHandler(func() { calls.Add(1) })
	r.ReportOriginalCarrierFailure()
	r.ReportOriginalCarrierFailure()
	if calls.Load() != 1 {
		t.Fatal("duplicate session failure")
	}
	r.Close()
	r.ReportOriginalCarrierFailure()
	if calls.Load() != 1 {
		t.Fatal("retired session reported failure")
	}
}
