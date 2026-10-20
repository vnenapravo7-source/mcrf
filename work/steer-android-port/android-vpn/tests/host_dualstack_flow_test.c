/* Queued whole-P1 assertion fixture, no Internet or physical TUN required.
 * A local pipe observes exact packet output; this is NOT device acceptance. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include "tun.h"
#include "ip6_packet.h"

int main(void) {
    _Static_assert(sizeof(struct flow_key) <= 64, "dual-family metadata must stay bounded");
    struct flow_key key={.family=6,.proto=6,.sport=49001,.dport=443};
    key.src6[0]=0xfd;key.src6[15]=1;
    key.dst6[0]=0x20;key.dst6[1]=1;key.dst6[15]=9;
    struct flow_key other=key;
    other.dst6[7]=1;assert(!flow_same_endpoints(&key,&other));
    other=key;other.family=4;assert(!flow_same_endpoints(&key,&other));
    other=key;other.seq=123;assert(flow_same_endpoints(&key,&other));
    assert(flow_mss(&key)==1440);
    unsigned char packet[1600],received[1600];
    const unsigned char body[]={0,1,2,255,7};
    size_t n=mcrf_ip6_tcp_build(packet,sizeof(packet),key.src6,key.dst6,
        key.sport,key.dport,0x12345678,0,TCP_SYN,4096,1440,7,NULL,0);
    struct flow_key parsed;size_t offset=0;
    assert(n==68&&ip_parse(packet,n,&parsed,&offset)==0&&offset==68);
    assert(flow_same_endpoints(&key,&parsed)&&parsed.sport==49001&&parsed.dport==443);
    assert(parsed.seq==0x12345678&&parsed.ws_seen&&parsed.wscale==7);
    n=flow_tcp_reply(packet,sizeof(packet),&parsed,1,parsed.seq+1,TCP_SYN|TCP_ACK,
        NULL,0,32768,flow_mss(&parsed),7);
    struct mcrf_ip6_packet decoded;
    assert(n==68&&!mcrf_ip6_parse(packet,n,&decoded));
    assert(!memcmp(decoded.src,key.dst6,16)&&!memcmp(decoded.dst,key.src6,16));
    assert(decoded.sport==443&&decoded.dport==49001&&decoded.seq==1&&decoded.ack==0x12345679);
    assert(flow_tcp_reply(packet,67,&key,1,1,TCP_SYN|TCP_ACK,NULL,0,1,1440,7)==0);
    int ends[2];assert(!pipe(ends));struct tun_dev tun={.fd=ends[1]};
    assert(!flow_write_tcp(&tun,&key,2,9,TCP_ACK|TCP_PSH,32768,body,sizeof(body)));
    ssize_t count=read(ends[0],received,sizeof(received));
    assert(count==65&&!mcrf_ip6_parse(received,(size_t)count,&decoded));
    assert(decoded.payload_length==sizeof(body)&&!memcmp(received+decoded.payload_offset,body,sizeof(body)));
    assert(!flow_write_udp(&tun,&key,body,sizeof(body),1));
    count=read(ends[0],received,sizeof(received));
    assert(count==53&&!mcrf_ip6_parse(received,(size_t)count,&decoded));
    assert(decoded.protocol==17&&decoded.payload_length==5);
    assert(!flow_write_udp(&tun,&key,NULL,0,2));
    count=read(ends[0],received,sizeof(received));
    assert(count==48&&!mcrf_ip6_parse(received,(size_t)count,&decoded)&&decoded.payload_length==0);
    /* SOCK_DGRAM keeps packet boundaries for observing fragmented replies. */
    int frames[2];assert(!socketpair(AF_UNIX,SOCK_DGRAM,0,frames));
    tun.fd=frames[1];unsigned char oversized[1233];memset(oversized,0xa5,sizeof(oversized));
    assert(!flow_write_udp(&tun,&key,oversized,sizeof(oversized),2));
    struct mcrf_ip6_assembly *assembly=mcrf_ip6_assembly_new();assert(assembly);
    size_t complete=0;count=recv(frames[0],received,sizeof(received),0);assert(count==1280);
    assert(mcrf_ip6_assemble(assembly,received,(size_t)count,1,packet,sizeof(packet),&complete)==0);
    count=recv(frames[0],received,sizeof(received),0);assert(count==57);
    assert(mcrf_ip6_assemble(assembly,received,(size_t)count,2,packet,sizeof(packet),&complete)==1);
    assert(complete==1281&&!mcrf_ip6_parse(packet,complete,&decoded)&&decoded.payload_length==1233);
    assert(!memcmp(packet+decoded.payload_offset,oversized,sizeof(oversized)));
    mcrf_ip6_assembly_free(assembly);close(frames[0]);close(frames[1]);
    packet[0]=0x60;packet[4]=packet[5]=0;
    assert(ip_parse(packet,40,&parsed,&offset)!=0);
    close(ends[0]);close(ends[1]);
    struct flow_key ipv4={.dst=0x0100007f,.src=0x0200007f,.sport=55,.dport=80};
    n=flow_tcp_reply(packet,sizeof(packet),&ipv4,8,9,TCP_ACK,body,sizeof(body),1024,0,-1);
    assert(n==45&&!ip_parse(packet,n,&parsed,&offset)&&!flow_is6(&parsed));
    assert(parsed.src==ipv4.dst&&parsed.dst==ipv4.src&&parsed.sport==80&&parsed.dport==55);
    puts("PASS: dual-family flow identity, TCP control/data/checksums, UDP output and fail-closed bounds");
    return 0;
}
