#include "ip6_packet.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

/* Owned offline fixtures only; no socket/TUN/process/provider calls. */
static size_t fragment(uint8_t *out,const uint8_t *packet,size_t offset,size_t bytes,int more,uint32_t id){
    memcpy(out,packet,40);out[6]=44;out[4]=(uint8_t)((8+bytes)>>8);out[5]=(uint8_t)(8+bytes);
    out[40]=17;out[41]=0;size_t bits=offset|(more?1u:0u);
    out[42]=(uint8_t)(bits>>8);out[43]=(uint8_t)bits;
    out[44]=(uint8_t)(id>>24);out[45]=(uint8_t)(id>>16);out[46]=(uint8_t)(id>>8);out[47]=(uint8_t)id;
    memcpy(out+48,packet+40+offset,bytes);return 48+bytes;
}
static void fragment_cases(void){
    uint8_t a[16]={0xfd},b[16]={0xfd},body[20],full[80],first[80],last[80],out[80];
    a[15]=1;b[15]=2;memset(body,0xa5,sizeof(body));
    size_t n=mcrf_ip6_udp_build(full,sizeof(full),a,b,111,222,body,sizeof(body));assert(n==68);
    size_t f=fragment(first,full,0,16,1,7),l=fragment(last,full,16,12,0,7),written=0;
    struct mcrf_ip6_assembly *owner=mcrf_ip6_assembly_new();assert(owner);
    assert(mcrf_ip6_assemble(owner,last,l,1,out,sizeof(out),&written)==0);
    assert(mcrf_ip6_assemble(owner,first,f,2,out,sizeof(out),&written)==1&&written==n&&!memcmp(out,full,n));
    assert(mcrf_ip6_assemble(owner,first,f,3,out,sizeof(out),&written)==-1); /* complete ID tombstone */
    assert(mcrf_ip6_assemble(owner,first,f,20002,out,sizeof(out),&written)==0);
    assert(mcrf_ip6_assemble(owner,first,f,20003,out,sizeof(out),&written)==-1); /* overlap poisons all */
    assert(mcrf_ip6_assemble(owner,last,l,20004,out,sizeof(out),&written)==-1);
    mcrf_ip6_assembly_free(owner);owner=mcrf_ip6_assembly_new();assert(owner);
    last[l-1]^=1;memset(out,0x5a,sizeof(out));
    assert(mcrf_ip6_assemble(owner,first,f,1,out,sizeof(out),&written)==0);
    assert(mcrf_ip6_assemble(owner,last,l,2,out,sizeof(out),&written)==-1&&out[0]==0x5a);
    last[l-1]^=1;
    mcrf_ip6_assembly_free(owner);owner=mcrf_ip6_assembly_new();assert(owner);
    for(uint32_t id=1;id<=4;id++){
        fragment(first,full,0,16,1,id);
        assert(mcrf_ip6_assemble(owner,first,f,id,out,sizeof(out),&written)==0);
    }
    fragment(first,full,0,16,1,5);assert(mcrf_ip6_assemble(owner,first,f,5,out,sizeof(out),&written)==-1);
    assert(mcrf_ip6_assemble(owner,first,f,20005,out,sizeof(out),&written)==0); /* bounded expiry */
    fragment(last,full,16,12,0,5);
    assert(mcrf_ip6_assemble(owner,last,l,20006,out,n-1,&written)==-1); /* no short output */
    assert(mcrf_ip6_assemble(owner,last,l,1,out,sizeof(out),&written)==-1); /* clock rollback */
    mcrf_ip6_assembly_free(owner);
}
static void mapped_cases(void){
    uint8_t a[16]={0xfd},mapped[16]={0},packet[80],body[1]={0};struct mcrf_ip6_packet parsed;
    a[15]=1;mapped[10]=mapped[11]=255;mapped[12]=192;mapped[14]=2;mapped[15]=1;
    size_t n=mcrf_ip6_udp_build(packet,sizeof(packet),a,mapped,111,222,body,sizeof(body));
    assert(n==49&&mcrf_ip6_parse(packet,n,&parsed)==-1);
    n=mcrf_ip6_udp_build(packet,sizeof(packet),mapped,a,111,222,body,sizeof(body));
    assert(n==49&&mcrf_ip6_parse(packet,n,&parsed)==-1);
}
int main(void){
    fragment_cases();
    mapped_cases();
    const uint8_t a[16]={0x20,1,0x0d,0xb8,0,0,0,0,0,0,0,0,0,0,0,1};
    const uint8_t b[16]={0x20,1,0x0d,0xb8,0,0,0,0,0,0,0,0,0,0,0,2};
    const uint8_t data[3]={'a','b','c'};
    uint8_t packet[1600],saved[1600];struct mcrf_ip6_packet parsed,untouched;
    size_t n=mcrf_ip6_udp_build(packet,sizeof packet,a,b,12345,53,data,3);
    assert(n==51&&packet[4]==0&&packet[5]==11&&packet[6]==17);
    /* Fixed golden checksum calculated independently from network-order words:
     * addresses5b75 + pseudo001c + ports3039/0035 + length000b + data6162/6300. */
    assert(packet[46]==0xaf&&packet[47]==0x92);
    assert(mcrf_ip6_parse(packet,n,&parsed)==0&&parsed.protocol==17);
    assert(parsed.sport==12345&&parsed.dport==53&&parsed.payload_offset==48&&parsed.payload_length==3);
    assert(!memcmp(parsed.src,a,16)&&!memcmp(parsed.dst,b,16)&&!memcmp(packet+48,data,3));
    memcpy(saved,packet,n);memset(&parsed,0xa5,sizeof parsed);memcpy(&untouched,&parsed,sizeof parsed);
    packet[50]^=1;assert(mcrf_ip6_parse(packet,n,&parsed)==-1&&!memcmp(&parsed,&untouched,sizeof parsed));
    memcpy(packet,saved,n);packet[46]=packet[47]=0;assert(mcrf_ip6_parse(packet,n,&parsed)==-1);
    memcpy(packet,saved,n);assert(mcrf_ip6_parse(packet,n-1,&parsed)==-1&&mcrf_ip6_parse(packet,n+1,&parsed)==-1);
    packet[4]=packet[5]=0;assert(mcrf_ip6_parse(packet,n,&parsed)==-1);
    memcpy(packet,saved,n);packet[7]=0;assert(mcrf_ip6_parse(packet,n,&parsed)==-1);
    memcpy(packet,saved,n);memmove(packet+48,packet+40,n-40);
    memset(packet+40,0,8);packet[6]=60;packet[5]=19;packet[40]=17;packet[42]=1;packet[43]=4;
    assert(mcrf_ip6_parse(packet,n+8,&parsed)==0&&parsed.payload_offset==56&&parsed.payload_length==3);
    packet[42]=0x20;packet[43]=0;assert(mcrf_ip6_parse(packet,n+8,&parsed)==-1);
    packet[6]=44;assert(mcrf_ip6_parse(packet,n+8,&parsed)==-1);
    packet[6]=43;assert(mcrf_ip6_parse(packet,n+8,&parsed)==-1);
    n=mcrf_ip6_tcp_build(packet,sizeof packet,a,b,12345,443,0x01020304,0,2,65535,1440,7,data,3);
    assert(n==71&&mcrf_ip6_parse(packet,n,&parsed)==0);
    assert(parsed.protocol==6&&parsed.flags==2&&parsed.seq==0x01020304&&parsed.ack==0);
    assert(parsed.window==65535&&parsed.ws_seen&&parsed.wscale==7&&parsed.payload_offset==68&&parsed.payload_length==3);
    assert(mcrf_ip6_tcp_build(packet,sizeof packet,a,b,1,2,0,0,16,1024,1440,-1,data,3)==0);
    assert(mcrf_ip6_udp_build(packet,50,a,b,1,2,data,3)==0);
    assert(mcrf_ip6_udp_build(packet,sizeof packet,a,b,1,2,NULL,3)==0);
    assert(mcrf_ip6_udp_build(packet,sizeof packet,a,b,1,2,data,(size_t)-1)==0);
    memcpy(packet,data,3);n=mcrf_ip6_udp_build(packet,sizeof packet,a,b,12345,53,packet,3);
    assert(n==51&&mcrf_ip6_parse(packet,n,&parsed)==0&&!memcmp(packet+48,data,3));
    puts("PASS owned IPv6 TCP/UDP codec/golden checksum/bounds/options; not live IPv6 capability");
    return 0;
}
