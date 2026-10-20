#ifndef MCRF_IP6_PACKET_H
#define MCRF_IP6_PACKET_H
#include <stddef.h>
#include <stdint.h>

/* Android dual-family packet codec; admission remains a separate host gate.
 * No socket, TUN, policy, allocation or physical-network fallback. */
struct mcrf_ip6_packet {
    uint8_t src[16], dst[16], protocol, flags, wscale, ws_seen;
    uint16_t sport, dport, window;
    uint32_t seq, ack;
    size_t payload_offset, payload_length;
};
/* Exact complete datagram only. Fragments require the separate bounded owner
 * below first. Routing/AH/ESP, jumbograms and unknown options are rejected. */
int mcrf_ip6_parse(const uint8_t *packet,size_t length,struct mcrf_ip6_packet *result);
size_t mcrf_ip6_tcp_build(uint8_t *out,size_t capacity,const uint8_t src[16],const uint8_t dst[16],
    uint16_t sport,uint16_t dport,uint32_t seq,uint32_t ack,uint8_t flags,uint16_t window,
    unsigned mss,int wscale,const uint8_t *payload,size_t payload_length);
size_t mcrf_ip6_udp_build(uint8_t *out,size_t capacity,const uint8_t src[16],const uint8_t dst[16],
    uint16_t sport,uint16_t dport,const uint8_t *payload,size_t payload_length);
/* Bounded per-worker owner: 4 incomplete datagrams/20s, 128 fragments each.
 * Only base IPv6 +Fragment +TCP/UDP admitted; overlaps poison the entire ID.
 * 1 assembled,0 incomplete,-1 rejected. Output untouched except on success. */
struct mcrf_ip6_assembly;
struct mcrf_ip6_assembly *mcrf_ip6_assembly_new(void);
void mcrf_ip6_assembly_free(struct mcrf_ip6_assembly *owner);
int mcrf_ip6_assemble(struct mcrf_ip6_assembly *owner,const uint8_t *packet,size_t length,
    uint64_t now_ms,uint8_t *out,size_t capacity,size_t *written);
#endif
