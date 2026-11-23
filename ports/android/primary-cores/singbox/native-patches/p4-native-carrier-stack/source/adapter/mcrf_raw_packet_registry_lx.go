//go:build with_lx_raw_packet && (linux || android)

package adapter

import (
	"errors"
	"sync"
)

// OriginalPacketEndpointRegistry is owned by one platform Box service. It
// never starts sessions; callers register only already-authenticated child FDs.
type OriginalPacketEndpointRegistry struct {
	mu                     sync.RWMutex
	endpoints              map[string]OriginalPacketEndpoint
	closed                 bool
	ranges                 map[string]uint16
	nextSlot               uint16
	carrierFailure         func()
	carrierFailureReported bool
}

func NewOriginalPacketEndpointRegistry() *OriginalPacketEndpointRegistry {
	return &OriginalPacketEndpointRegistry{endpoints: make(map[string]OriginalPacketEndpoint), ranges: make(map[string]uint16)}
}

// gVisor's fallback stack starts ephemeral allocation at16000. These fixed,
// non-reused session slots keep eight backend addresses/return tuples distinct,
// including providers that assign the same private IPv4 to multiple servers.
func (r *OriginalPacketEndpointRegistry) OriginalPacketSelectorRange(tag string) (uint16, uint16) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	start, ok := r.ranges[tag]
	if r.closed || !ok {
		return 0, 0
	}
	return start, 1872
}

func (r *OriginalPacketEndpointRegistry) HasOriginalPacketEndpoints() bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return !r.closed && len(r.endpoints) != 0
}

func (r *OriginalPacketEndpointRegistry) OriginalPacketEndpoint(tag string) (OriginalPacketEndpoint, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if r.closed {
		return nil, false
	}
	endpoint, ok := r.endpoints[tag]
	return endpoint, ok
}

func (r *OriginalPacketEndpointRegistry) Register(tag string, endpoint OriginalPacketEndpoint) error {
	if r == nil || tag == "" || len(tag) > 96 || endpoint == nil || endpoint.Identity() == "" || endpoint.Generation() == 0 ||
		!endpoint.LocalAddress().IsValid() || endpoint.MTU() < 20 || endpoint.MTU() > 1984 {
		return errors.New("MCRF_RAW_ENDPOINT_REGISTRATION")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return errors.New("MCRF_RAW_ENDPOINT_REGISTRY_CLOSED")
	}
	for existingTag, existing := range r.endpoints {
		if existingTag != tag && existing.Identity() == endpoint.Identity() {
			return errors.New("MCRF_RAW_ENDPOINT_ALIAS")
		}
	}
	if old := r.endpoints[tag]; old != nil {
		if old.Identity() == endpoint.Identity() && old.Generation() == endpoint.Generation() {
			return errors.New("MCRF_RAW_ENDPOINT_DUPLICATE")
		}
		return errors.New("MCRF_RAW_ENDPOINT_REPLACEMENT_REQUIRES_UNREGISTER")
	}
	if len(r.endpoints) >= 64 {
		return errors.New("MCRF_ORIGINAL_ENDPOINT_LIMIT")
	}
	carrier, isCarrier := endpoint.(interface{ OriginalCarrierStack() bool })
	if !isCarrier || !carrier.OriginalCarrierStack() {
		if r.nextSlot >= 8 {
			return errors.New("MCRF_RAW_ENDPOINT_SLOT_LIMIT")
		}
		r.ranges[tag] = 1024 + r.nextSlot*1872
		r.nextSlot++
	}
	r.endpoints[tag] = endpoint
	return nil
}

func (r *OriginalPacketEndpointRegistry) SetOriginalCarrierFailureHandler(handler func()) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.closed && r.carrierFailure == nil {
		r.carrierFailure = handler
	}
}
func (r *OriginalPacketEndpointRegistry) ReportOriginalCarrierFailure() {
	r.mu.Lock()
	if r.closed || r.carrierFailureReported {
		r.mu.Unlock()
		return
	}
	r.carrierFailureReported = true
	handler := r.carrierFailure
	r.mu.Unlock()
	if handler != nil {
		handler()
	}
}

func (r *OriginalPacketEndpointRegistry) Unregister(tag string) error {
	r.mu.Lock()
	endpoint := r.endpoints[tag]
	delete(r.endpoints, tag)
	delete(r.ranges, tag)
	r.mu.Unlock()
	if endpoint == nil {
		return nil
	}
	if closer, ok := endpoint.(interface{ Close() error }); ok {
		return closer.Close()
	}
	return nil
}

func (r *OriginalPacketEndpointRegistry) Close() {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return
	}
	r.closed = true
	endpoints := r.endpoints
	r.endpoints = make(map[string]OriginalPacketEndpoint)
	r.mu.Unlock()
	for _, endpoint := range endpoints {
		if closer, ok := endpoint.(interface{ Close() error }); ok {
			_ = closer.Close()
		}
	}
}
