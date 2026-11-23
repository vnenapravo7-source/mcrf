//go:build with_mcrf_direct_family

package route

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"syscall"
	"testing"

	M "github.com/sagernet/sing/common/metadata"
)

type mcrfFamilyTestDialer struct {
	destinations []M.Socksaddr
	dial         func(context.Context, string, M.Socksaddr) (net.Conn, error)
}

func (d *mcrfFamilyTestDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	d.destinations = append(d.destinations, destination)
	return d.dial(ctx, network, destination)
}

func (*mcrfFamilyTestDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unexpected packet operation")
}

func TestMcrfDirectFamilyReachabilityRetry(t *testing.T) {
	initial := M.Socksaddr{Addr: netip.MustParseAddr("fdfe:dcba:9876::c612:1"), Port: 443}
	v4 := netip.MustParseAddr("198.18.0.1")
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	dialer := &mcrfFamilyTestDialer{dial: func(_ context.Context, _ string, dest M.Socksaddr) (net.Conn, error) {
		if dest == initial {
			return nil, &net.OpError{Op: "dial", Net: "tcp", Err: syscall.ENETUNREACH}
		}
		if dest.Addr != v4 || dest.Port != 443 {
			t.Fatalf("unexpected candidate %v", dest)
		}
		return left, nil
	}}
	lookups, checks := 0, 0
	retry := &mcrfDirectFamilyRetry{Dialer: dialer,
		lookup:  func(context.Context) ([]netip.Addr, error) { lookups++; return []netip.Addr{v4}, nil },
		allowed: func(_ context.Context, address netip.Addr) bool { checks++; return address == v4 },
	}
	got, err := retry.DialContext(context.Background(), "tcp", initial)
	if err != nil || got != left || lookups != 1 || checks != 1 || len(dialer.destinations) != 2 {
		t.Fatalf("retry conn=%v error=%v lookup=%d checks=%d calls=%d", got, err, lookups, checks, len(dialer.destinations))
	}
}

func TestMcrfDirectFamilyDoesNotReplayOtherErrorsOrNetworks(t *testing.T) {
	v6 := M.Socksaddr{Addr: netip.MustParseAddr("2001:db8::1"), Port: 443}
	v4 := M.Socksaddr{Addr: netip.MustParseAddr("192.0.2.1"), Port: 443}
	for _, tc := range []struct {
		name, network string
		destination   M.Socksaddr
		err           error
		cancelled     bool
	}{
		{"success-ipv6", "tcp", v6, nil, false},
		{"refused", "tcp", v6, syscall.ECONNREFUSED, false},
		{"tls-close", "tcp", v6, errors.New("TLS connection closed"), false},
		{"timeout", "tcp", v6, context.DeadlineExceeded, false},
		{"cancelled", "tcp", v6, syscall.ENETUNREACH, true},
		{"ipv4", "tcp", v4, syscall.ENETUNREACH, false},
		{"udp", "udp", v6, syscall.ENETUNREACH, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if tc.cancelled {
				cancel()
			}
			dialer := &mcrfFamilyTestDialer{dial: func(context.Context, string, M.Socksaddr) (net.Conn, error) { return nil, tc.err }}
			retry := &mcrfDirectFamilyRetry{Dialer: dialer,
				lookup:  func(context.Context) ([]netip.Addr, error) { t.Fatal("unexpected DNS lookup"); return nil, nil },
				allowed: func(context.Context, netip.Addr) bool { t.Fatal("unexpected policy check"); return false },
			}
			_, err := retry.DialContext(ctx, tc.network, tc.destination)
			if err != tc.err || len(dialer.destinations) != 1 {
				t.Fatalf("changed original result: %v", err)
			}
		})
	}
}

func TestMcrfDirectFamilyPolicyDenialCannotDialOtherRoute(t *testing.T) {
	for _, reason := range []string{"vpn-candidate", "blocked-candidate", "different-direct-rule", "changed-options"} {
		t.Run(reason, func(t *testing.T) {
			dialer := &mcrfFamilyTestDialer{dial: func(context.Context, string, M.Socksaddr) (net.Conn, error) { return nil, syscall.ENETUNREACH }}
			retry := &mcrfDirectFamilyRetry{Dialer: dialer,
				lookup: func(context.Context) ([]netip.Addr, error) {
					return []netip.Addr{netip.MustParseAddr("192.0.2.1")}, nil
				},
				allowed: func(context.Context, netip.Addr) bool { return false },
			}
			_, err := retry.DialContext(context.Background(), "tcp", M.Socksaddr{Addr: netip.MustParseAddr("2001:db8::1"), Port: 443})
			if !errors.Is(err, syscall.ENETUNREACH) || len(dialer.destinations) != 1 {
				t.Fatal("policy denial dialed candidate")
			}
		})
	}
}

func TestMcrfDirectFamilyLookupFailureKeepsOriginalError(t *testing.T) {
	dialer := &mcrfFamilyTestDialer{dial: func(context.Context, string, M.Socksaddr) (net.Conn, error) { return nil, syscall.EHOSTUNREACH }}
	retry := &mcrfDirectFamilyRetry{Dialer: dialer,
		lookup:  func(context.Context) ([]netip.Addr, error) { return nil, errors.New("DNS unavailable") },
		allowed: func(context.Context, netip.Addr) bool { t.Fatal("unexpected candidate"); return false },
	}
	_, err := retry.DialContext(context.Background(), "tcp", M.Socksaddr{Addr: netip.MustParseAddr("2001:db8::1"), Port: 443})
	if !errors.Is(err, syscall.EHOSTUNREACH) || len(dialer.destinations) != 1 {
		t.Fatal("lookup failure changed outcome")
	}
}

func TestMcrfDirectFamilyCandidateBoundAndDeduplication(t *testing.T) {
	dialer := &mcrfFamilyTestDialer{dial: func(context.Context, string, M.Socksaddr) (net.Conn, error) { return nil, syscall.EADDRNOTAVAIL }}
	retry := &mcrfDirectFamilyRetry{Dialer: dialer,
		lookup: func(context.Context) ([]netip.Addr, error) {
			return []netip.Addr{netip.MustParseAddr("2001:db8::2"), netip.MustParseAddr("192.0.2.1"), netip.MustParseAddr("192.0.2.1"), netip.MustParseAddr("192.0.2.2"), netip.MustParseAddr("192.0.2.3"), netip.MustParseAddr("192.0.2.4"), netip.MustParseAddr("192.0.2.5")}, nil
		},
		allowed: func(context.Context, netip.Addr) bool { return true },
	}
	_, err := retry.DialContext(context.Background(), "tcp", M.Socksaddr{Addr: netip.MustParseAddr("2001:db8::1"), Port: 443})
	if !errors.Is(err, syscall.EADDRNOTAVAIL) || len(dialer.destinations) != 5 {
		t.Fatal("candidate retry limit/deduplication broken")
	}
}
