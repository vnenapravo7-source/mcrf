//go:build android && cgo

// SPDX-License-Identifier: GPL-3.0-or-later
package main

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
#include <dlfcn.h>
#include <netdb.h>
#include <arpa/inet.h>
#include <string.h>
#include <stdlib.h>
static int mcrf_lookup(net_handle_t network, const char *host, int family, char *output, size_t size) {
    struct addrinfo hints, *answer=0;
    memset(&hints,0,sizeof(hints));hints.ai_family=family;hints.ai_socktype=SOCK_STREAM;
    int result=android_getaddrinfofornetwork(network,host,0,&hints,&answer);
    if(result!=0)return result;
    size_t offset=0;int count=0;
    for(struct addrinfo *item=answer;item!=0 && count<64;item=item->ai_next){
        const void *ip=0;
        if(item->ai_family==AF_INET)ip=&((struct sockaddr_in *)item->ai_addr)->sin_addr;
        else if(item->ai_family==AF_INET6)ip=&((struct sockaddr_in6 *)item->ai_addr)->sin6_addr;
        if(ip!=0 && size-offset>INET6_ADDRSTRLEN && inet_ntop(item->ai_family,ip,output+offset,size-offset)!=0){offset+=strlen(output+offset);output[offset++]='\n';count++;}
    }
    output[offset]=0;freeaddrinfo(answer);return 0;
}
static int mcrf_bound_network(net_handle_t *network) {
    int (*get_network)(net_handle_t *) = (int (*)(net_handle_t *))dlsym(RTLD_DEFAULT, "android_getprocnetwork");
    return get_network == 0 ? -1 : get_network(network);
}
*/
import "C"
import (
	"context"
	"errors"
	"net"
	"strings"
	"unsafe"
)

// The child alone binds its newly created Go/QUIC/Cronet sockets and server
// bootstrap DNS to the captured physical network. Never bind the host process:
// the host must stay inside Steer's VPN for its actual tunnel-health probe.
// A missing/stale/denied network is a failure, not a default-network fallback.
func bindPhysical(handle uint64) error {
	if handle == 0 {
		return errors.New("PROTOCOL_PHYSICAL_NETWORK_REQUIRED")
	}
	if C.android_setprocnetwork(C.net_handle_t(handle)) != 0 {
		return errors.New("PROTOCOL_PHYSICAL_NETWORK_BIND_FAILED")
	}
	return nil
}
func boundPhysical() (uint64, error) {
	var result C.net_handle_t
	if C.mcrf_bound_network(&result) != 0 {
		return 0, errors.New("Android31+ physical-network receipt unavailable")
	}
	return uint64(result), nil
}
func bindSocket(handle uint64, fd int) error {
	if handle == 0 || fd < 0 || C.android_setsocknetwork(C.net_handle_t(handle), C.int(fd)) != 0 {
		return errors.New("PROTOCOL_PHYSICAL_NETWORK_BIND_FAILED")
	}
	return nil
}

func physicalLookup(owner context.Context) func(context.Context, string, string) ([]net.IP, error) {
	handle, _ := owner.Value(physicalNetworkKey{}).(uint64)
	pending := make(chan struct{}, 16)
	return func(ctx context.Context, network, host string) ([]net.IP, error) {
		if handle == 0 {
			return nil, errors.New("PROTOCOL_PHYSICAL_NETWORK_REQUIRED")
		}
		select {
		case pending <- struct{}{}:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		type answer struct {
			ips []net.IP
			err error
		}
		done := make(chan answer, 1)
		go func() {
			defer func() { <-pending }()
			name := C.CString(host)
			defer C.free(unsafe.Pointer(name))
			family := C.int(C.AF_UNSPEC)
			if network == "ip4" {
				family = C.AF_INET
			} else if network == "ip6" {
				family = C.AF_INET6
			}
			buffer := make([]byte, 8192)
			result := C.mcrf_lookup(C.net_handle_t(handle), name, family, (*C.char)(unsafe.Pointer(&buffer[0])), C.size_t(len(buffer)))
			if result != 0 {
				done <- answer{err: &net.DNSError{Err: "Android physical resolver rejected query", Name: host, IsNotFound: result == C.EAI_NONAME, IsTemporary: result == C.EAI_AGAIN}}
				return
			}
			var ips []net.IP
			for _, line := range strings.Split(strings.TrimRight(string(buffer), "\x00"), "\n") {
				if ip := net.ParseIP(line); ip != nil {
					ips = append(ips, ip)
				}
			}
			done <- answer{ips: ips}
		}()
		select {
		case result := <-done:
			return result.ips, result.err
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-owner.Done():
			return nil, owner.Err()
		}
	}
}
