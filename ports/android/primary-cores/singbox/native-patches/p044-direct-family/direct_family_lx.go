//go:build with_mcrf_direct_family

package route

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"reflect"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// A TUN can acknowledge TCP before Direct has reached its destination. In that
// case a TLS close is too late for some clients' Happy Eyeballs fallback. Retry
// inside the already-selected Direct path, only after an actual address-family
// reachability error, and only if the IPv4 candidate selects the SAME rule and
// outbound. DNS answers, TUN coverage and VPN egress families stay unchanged.
// Removal condition: upstream supplies equivalent same-policy Direct fallback.
func (r *Router) mcrfDirectFamilyDialer(outbound adapter.Outbound, rule adapter.Rule, metadata adapter.InboundContext) N.Dialer {
	if outbound.Type() != C.TypeDirect || metadata.InboundType != C.TypeTun ||
		metadata.Network != N.NetworkTCP || !metadata.Destination.IsIPv6() ||
		(metadata.Protocol != C.ProtocolTLS && metadata.Protocol != C.ProtocolHTTP) ||
		metadata.SniffError != nil || !M.IsDomainName(metadata.Domain) {
		return outbound
	}
	return &mcrfDirectFamilyRetry{
		Dialer: outbound,
		lookup: func(ctx context.Context) ([]netip.Addr, error) {
			query := metadata
			query.Destination = M.Socksaddr{Fqdn: metadata.Domain, Port: metadata.Destination.Port}
			query.DestinationAddresses = nil
			query.DNSResponse = nil
			query.NamedDNSResponses = nil
			query.DestinationAddressMatchFromResponse = false
			query.GeoIPCode = ""
			query.ResetRuleCache()
			return r.dns.Lookup(adapter.WithContext(ctx, &query), metadata.Domain,
				adapter.DNSQueryOptions{Strategy: C.DomainStrategyIPv4Only})
		},
		allowed: func(ctx context.Context, address netip.Addr) bool {
			candidate := metadata
			candidate.Destination = M.Socksaddr{Addr: address, Port: metadata.Destination.Port}
			candidate.DestinationAddresses = nil
			candidate.DNSResponse = nil
			candidate.NamedDNSResponses = nil
			candidate.DestinationAddressMatchFromResponse = false
			candidate.GeoIPCode = ""
			candidate.ResetRuleCache()
			// Protocol/SNI and the original socket owner are retained. No packet is
			// read or sent during this policy recheck; buffers are never replayed twice.
			matched, _, buffers, packets, err := r.matchRule(ctx, &candidate, nil, nil)
			buf.ReleaseMulti(buffers)
			N.ReleaseMultiPacketBuffer(packets)
			if err != nil || matched != rule || candidate.Destination != (M.Socksaddr{Addr: address, Port: metadata.Destination.Port}) {
				return false
			}
			// Reaching the identical terminal rule (or identical default) preserves
			// VPN/BLOCK priority. Do not apply a different route's TLS/NAT options.
			return candidate.TLSFragment == metadata.TLSFragment &&
				candidate.TLSRecordFragment == metadata.TLSRecordFragment &&
				candidate.TLSSpoof == metadata.TLSSpoof &&
				candidate.TLSSpoofMethod == metadata.TLSSpoofMethod &&
				candidate.TLSFragmentFallbackDelay == metadata.TLSFragmentFallbackDelay &&
				candidate.UDPConnect == metadata.UDPConnect &&
				candidate.UDPTimeout == metadata.UDPTimeout &&
				candidate.UDPDisableDomainUnmapping == metadata.UDPDisableDomainUnmapping &&
				reflect.DeepEqual(candidate.NetworkStrategy, metadata.NetworkStrategy) &&
				reflect.DeepEqual(candidate.NetworkType, metadata.NetworkType) &&
				reflect.DeepEqual(candidate.FallbackNetworkType, metadata.FallbackNetworkType) &&
				candidate.FallbackDelay == metadata.FallbackDelay
		},
	}
}

type mcrfDirectFamilyRetry struct {
	N.Dialer
	lookup  func(context.Context) ([]netip.Addr, error)
	allowed func(context.Context, netip.Addr) bool
}

func (d *mcrfDirectFamilyRetry) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	conn, originalErr := d.Dialer.DialContext(ctx, network, destination)
	if originalErr == nil || conn != nil || ctx.Err() != nil || N.NetworkName(network) != N.NetworkTCP || !destination.IsIPv6() ||
		(!errors.Is(originalErr, syscall.ENETUNREACH) && !errors.Is(originalErr, syscall.EHOSTUNREACH) && !errors.Is(originalErr, syscall.EADDRNOTAVAIL)) {
		return conn, originalErr
	}
	// This operation is synchronous, cancellable and bounded. No global DNS
	// filtering, cache flush, background retry, altered profile or proxy fallback.
	retryCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	addresses, err := d.lookup(retryCtx)
	if err != nil {
		return nil, originalErr
	}
	seen := make(map[netip.Addr]struct{})
	for _, address := range addresses {
		address = address.Unmap()
		if !address.Is4() || retryCtx.Err() != nil {
			continue
		}
		if _, exists := seen[address]; exists {
			continue
		}
		seen[address] = struct{}{}
		if len(seen) > 4 {
			break
		}
		if !d.allowed(retryCtx, address) {
			continue
		}
		if retryCtx.Err() != nil {
			return nil, originalErr
		}
		conn, err = d.Dialer.DialContext(retryCtx, network, M.Socksaddr{Addr: address, Port: destination.Port})
		if err == nil {
			return conn, nil
		}
	}
	return nil, originalErr
}
