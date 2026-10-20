#pragma once
#include "dialer.h"
#include <arpa/inet.h>
#include <string.h>
/* Mechanical v4/v6 DNS-port rewrite namespace only. Host's ORIGINAL tuple +
 * actual kernel UID decision is still mandatory; an alias is not authority. */
static inline int host_dns_control_rewrite(const struct flow_key *key){
    if(key->dport!=5353||(key->proto!=6&&key->proto!=17))return 0;
    if(!flow_is6(key))return (ntohl(key->dst)&0xfffffc00u)==0x0a590400u;
    static const uint8_t prefix[14]={0xfd,0xfe,0xdc,0xba,0x98,0x76,0xdd,0x53,0,0,0,0,0,0};
    return memcmp(key->dst6,prefix,sizeof(prefix))==0;
}
/* Android-only routing adapter; no changes to upstream protocol dialers. */
struct host_router {
    struct dialer inner;
    void *host;
    /* Host-order IPv4. High word: 1 proxy, 2 direct, 3 deny; low word: real IP. */
    uint64_t (*route)(void *host, uint32_t ip);
    /* Optional exact destination port/protocol. Codes4/5 proxy/direct upstream
     * DNS port53 through owned virtual5353; never accepted from legacy route. */
    uint64_t (*route_port)(void *host,uint32_t ip,unsigned port,unsigned protocol);
    /* Original full tuple -> original-family real destination. Unknown owner
     * explicitly denies; no destination-only fallback. target starts as key. */
    int (*route_owned)(void *host,const struct flow_key *original,struct flow_key *target);
    /* 1 proxy, 2 direct; anything else denied. Output numeric target, never an
     * alias or a truncated address. NULL explicitly keeps legacy IPv4-only. */
    int (*route6)(void *host,const uint8_t ip[16],uint8_t destination[16]);
    void (*flow_result)(void *host,const struct flow_key *original,int ready);
    uint32_t upstream_dns;
    unsigned dns_port;
};
extern const struct dialer_ops host_dialer;
int host_route_filter(void *ctx,const struct flow_key *key,int udp);
