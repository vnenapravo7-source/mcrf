#include "ip6_packet.h"
#include <string.h>
#include <stdlib.h>

/* Original implementation of RFC8200 section8.1 pseudo-header arithmetic.
 * https://www.rfc-editor.org/rfc/rfc8200.html#section-8.1 */
static uint16_t read16(const uint8_t *p){return (uint16_t)(((uint16_t)p[0]<<8)|p[1]);}
static uint32_t read32(const uint8_t *p){return ((uint32_t)read16(p)<<16)|read16(p+2);}
static int mapped4(const uint8_t *p){
    for(unsigned i=0;i<10;i++)if(p[i])return 0;
    return p[10]==255&&p[11]==255;
}
static void write16(uint8_t *p,uint16_t n){p[0]=(uint8_t)(n>>8);p[1]=(uint8_t)n;}
static void write32(uint8_t *p,uint32_t n){write16(p,(uint16_t)(n>>16));write16(p+2,(uint16_t)n);}
static uint32_t add(const uint8_t *p,size_t n,uint32_t sum){
    while(n>=2){sum+=read16(p);p+=2;n-=2;}
    if(n)sum+=(uint32_t)*p<<8;
    return sum;
}
static uint16_t checksum(const uint8_t src[16],const uint8_t dst[16],uint8_t protocol,const uint8_t *body,size_t n){
    uint32_t sum=add(src,16,0);sum=add(dst,16,sum);
    sum+=(uint32_t)(n>>16)+(uint32_t)(n&65535)+protocol;
    sum=add(body,n,sum);
    while(sum>>16)sum=(sum&65535)+(sum>>16);
    return (uint16_t)~sum;
}
static int options(const uint8_t *p,size_t n){
    size_t at=2;
    while(at<n){
        if(p[at]==0){at++;continue;} /* Pad1 */
        if(at+2>n||(size_t)p[at+1]>n-at-2)return -1;
        /* Only inert PadN is admitted in this initial codec; never ignore an
         * unknown option's action bits, Jumbo Payload, or routing semantics. */
        if(p[at]!=1)return -1;
        for(size_t j=0;j<p[at+1];j++)if(p[at+2+j]!=0)return -1;
        at+=2+p[at+1];
    }
    return 0;
}
int mcrf_ip6_parse(const uint8_t *p,size_t n,struct mcrf_ip6_packet *out){
    if(!p||!out||n<40||(p[0]>>4)!=6||p[7]==0)return -1;
    if(mapped4(p+8)||mapped4(p+24))return -1;
    size_t declared=read16(p+4);
    if(declared==0||declared!=n-40)return -1;
    uint8_t next=p[6];size_t at=40,headers=0,extension_bytes=0;
    while(next==0||next==60){
        if(++headers>8||at+2>n||(next==0&&at!=40))return -1;
        size_t bytes=((size_t)p[at+1]+1)*8;
        if(bytes>n-at||bytes>256-extension_bytes||options(p+at,bytes))return -1;
        extension_bytes+=bytes;next=p[at];at+=bytes;
    }
    if(next!=6&&next!=17)return -1;
    size_t transport=n-at;
    if(transport<(next==6?20u:8u)||checksum(p+8,p+24,next,p+at,transport)!=0)return -1;
    struct mcrf_ip6_packet result={0};
    memcpy(result.src,p+8,16);memcpy(result.dst,p+24,16);result.protocol=next;
    result.sport=read16(p+at);result.dport=read16(p+at+2);
    size_t header=8;
    if(next==17){
        if(read16(p+at+4)!=transport||read16(p+at+6)==0)return -1;
    }else{
        header=(size_t)(p[at+12]>>4)*4;
        if(header<20||header>transport)return -1;
        result.seq=read32(p+at+4);result.ack=read32(p+at+8);
        result.flags=p[at+13];result.window=read16(p+at+14);
        size_t option=20;
        while(option<header){
            uint8_t kind=p[at+option];
            if(kind==0)break;
            if(kind==1){option++;continue;}
            if(option+2>header)return -1;
            size_t bytes=p[at+option+1];
            if(bytes<2||bytes>header-option)return -1;
            if(kind==3){
                if(bytes!=3||result.ws_seen)return -1;
                if(result.flags&2){result.ws_seen=1;result.wscale=p[at+option+2]>14?14:p[at+option+2];}
            }
            option+=bytes;
        }
    }
    result.payload_offset=at+header;result.payload_length=transport-header;
    *out=result;return 0;
}
static void header(uint8_t *out,const uint8_t src[16],const uint8_t dst[16],uint8_t protocol,size_t transport){
    uint8_t a[16],b[16];memcpy(a,src,16);memcpy(b,dst,16);
    memset(out,0,40);out[0]=0x60;write16(out+4,(uint16_t)transport);out[6]=protocol;out[7]=64;
    memcpy(out+8,a,16);memcpy(out+24,b,16);
}
size_t mcrf_ip6_tcp_build(uint8_t *out,size_t cap,const uint8_t src[16],const uint8_t dst[16],
    uint16_t sport,uint16_t dport,uint32_t seq,uint32_t ack,uint8_t flags,uint16_t window,
    unsigned mss,int wscale,const uint8_t *payload,size_t n){
    if(!out||!src||!dst||(!payload&&n)||mss>65535||wscale < -1||wscale>14)return 0;
    if((mss||wscale>=0)&&!(flags&2))return 0;
    size_t opts=(mss?4u:0u)+(wscale>=0?4u:0u),th=20+opts;
    if(n>65535-th||cap<40+th+n)return 0;
    /* Move first so an in-place payload source is not erased by header setup. */
    if(n)memmove(out+40+th,payload,n);
    header(out,src,dst,6,th+n);memset(out+40,0,th);
    uint8_t *t=out+40;write16(t,sport);write16(t+2,dport);write32(t+4,seq);write32(t+8,ack);
    t[12]=(uint8_t)((th/4)<<4);t[13]=flags;write16(t+14,window);
    size_t at=20;
    if(mss){t[at++]=2;t[at++]=4;write16(t+at,(uint16_t)mss);at+=2;}
    if(wscale>=0){t[at++]=1;t[at++]=3;t[at++]=3;t[at]=(uint8_t)wscale;}
    write16(t+16,checksum(out+8,out+24,6,t,th+n));return 40+th+n;
}
size_t mcrf_ip6_udp_build(uint8_t *out,size_t cap,const uint8_t src[16],const uint8_t dst[16],
    uint16_t sport,uint16_t dport,const uint8_t *payload,size_t n){
    if(!out||!src||!dst||(!payload&&n)||n>65527||cap<48+n)return 0;
    if(n)memmove(out+48,payload,n);
    header(out,src,dst,17,8+n);uint8_t *u=out+40;memset(u,0,8);
    write16(u,sport);write16(u+2,dport);write16(u+4,(uint16_t)(8+n));
    uint16_t value=checksum(out+8,out+24,17,u,8+n);
    write16(u+6,value?value:65535);return 48+n;
}

struct fragment_slot {
    uint8_t header[40],next,used,dead;
    uint32_t id;
    uint64_t born;
    size_t total,filled,highest;
    unsigned fragments;
    uint8_t data[65535],seen[8192];
};
struct mcrf_ip6_assembly {uint64_t last;struct fragment_slot slots[4];};
struct mcrf_ip6_assembly *mcrf_ip6_assembly_new(void){return calloc(1,sizeof(struct mcrf_ip6_assembly));}
static void poison(struct fragment_slot *s){
    s->dead=1;memset(s->data,0,sizeof(s->data));memset(s->seen,0,sizeof(s->seen));
}
void mcrf_ip6_assembly_free(struct mcrf_ip6_assembly *owner){
    if(owner){volatile uint8_t *bytes=(volatile uint8_t *)owner;for(size_t i=0;i<sizeof(*owner);i++)bytes[i]=0;free(owner);}
}
int mcrf_ip6_assemble(struct mcrf_ip6_assembly *owner,const uint8_t *p,size_t n,
    uint64_t now,uint8_t *out,size_t cap,size_t *written){
    if(!owner||!p||!out||!written||n<48||(p[0]>>4)!=6||p[6]!=44||!p[7]
        ||read16(p+4)!=n-40||p[41]!=0||now<owner->last)return -1;
    owner->last=now;
    uint8_t next=p[40];uint16_t bits=read16(p+42);
    size_t offset=bits&0xfff8u,bytes=n-48;int more=bits&1;
    uint32_t id=read32(p+44);
    if((next!=6&&next!=17)||(bits&6)||bytes==0||(more&&(bytes&7))||bytes>65535-offset)return -1;
    /* First fragment must contain the complete transport header (RFC7112).
     * Extensions after Fragment are rejected, not guessed as transport. */
    if(offset==0&&(bytes<(next==6?20u:8u)||(next==6&&((p[60]>>4)<5||(size_t)(p[60]>>4)*4>bytes))))return -1;
    struct fragment_slot *s=NULL,*available=NULL;
    for(unsigned i=0;i<4;i++){
        struct fragment_slot *candidate=&owner->slots[i];
        if(candidate->used&&now-candidate->born>=20000)memset(candidate,0,sizeof(*candidate));
        if(!candidate->used){if(!available)available=candidate;continue;}
        if(candidate->id==id&&!memcmp(candidate->header+8,p+8,32))s=candidate;
    }
    if(!s){
        if(!available)return -1;
        s=available;s->used=1;s->born=now;s->id=id;s->next=next;
        memcpy(s->header,p,40);
    }
    if(s->dead)return -1;
    if(s->next!=next||++s->fragments>128||(s->total&&(offset+bytes>s->total||(more&&offset+bytes==s->total)))){
        poison(s);return -1;
    }
    if(!more){
        if((s->total&&s->total!=offset+bytes)||s->highest>offset+bytes){poison(s);return -1;}
        s->total=offset+bytes;
    }
    for(size_t at=offset;at<offset+bytes;at++)if(s->seen[at>>3]&(1u<<(at&7))){poison(s);return -1;}
    memcpy(s->data+offset,p+48,bytes);
    for(size_t at=offset;at<offset+bytes;at++)s->seen[at>>3]|=(uint8_t)(1u<<(at&7));
    s->filled+=bytes;if(s->highest<offset+bytes)s->highest=offset+bytes;
    if(!s->total||s->filled!=s->total)return 0;
    if(cap<40+s->total){poison(s);return -1;}
    /* Validate the complete transport checksum BEFORE publishing any output. */
    if(checksum(s->header+8,s->header+24,s->next,s->data,s->total)!=0){poison(s);return -1;}
    memcpy(out,s->header,40);out[6]=s->next;write16(out+4,(uint16_t)s->total);
    memcpy(out+40,s->data,s->total);*written=40+s->total;
    poison(s);return 1;
}
