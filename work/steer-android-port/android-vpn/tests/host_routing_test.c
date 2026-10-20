/* Exercise the Android adapter independently of protocol/network servers. */
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include "hostdial.h"

int tr_dial(const char *host,uint16_t port,int timeout){(void)host;(void)port;(void)timeout;return -1;}
static int owned_udp=-1;
static char last_udp_host[16];static uint16_t last_udp_port;
int tr_dial_udp(const char *host,uint16_t port){snprintf(last_udp_host,sizeof(last_udp_host),"%s",host);last_udp_port=port;return owned_udp<0?-1:dup(owned_udp);}
static unsigned char direct6[16];static int dial6_calls,last6_udp;static uint16_t last6_port;
int tr_dial_ip6(const unsigned char ip[16],uint16_t port,int udp,int timeout){
    assert(timeout==2&&((port==443&&udp==0)||(port==53&&udp==1)));
    memcpy(direct6,ip,16);dial6_calls++;last6_port=port;last6_udp=udp;return -1;
}
static struct flow_key opened;
static int datagrams,again_at;
static int request_ready,connect_failure,readiness_events,last_ready;
static struct flow_key observed_original;
static struct flow_key owner_original;static int owner_action,owner_calls;
static int owned_route(void *ctx,const struct flow_key *original,struct flow_key *target){
    (void)ctx;owner_calls++;owner_original=*original;*target=*original;
    if(flow_is6(original)){memset(target->dst6,0,16);target->dst6[0]=0x20;target->dst6[1]=1;target->dst6[2]=0x0d;target->dst6[3]=0xb8;target->dst6[15]=55;}
    else target->dst=htonl(0xc000024d);
    return owner_action;
}
static int connect_mock(const void *ctx,void *p,int timeout){(void)ctx;(void)p;(void)timeout;return connect_failure?-1:0;}
static int proxy_ready_mock(const void *p){(void)p;return request_ready;}
static void readiness_mock(void *ctx,const struct flow_key *key,int ready){(void)ctx;observed_original=*key;readiness_events++;last_ready=ready;}
static uint64_t route(void *ctx,uint32_t ip){(void)ctx;if(ip==0xc6130001)return (1ULL<<32)|0xc6120001;if(ip==0xc6130002)return (2ULL<<32)|0x7f000001;return 3ULL<<32;}
static uint64_t route_port(void *ctx,uint32_t ip,unsigned port,unsigned protocol){
    if((ip&0xfffffc00u)==0x0a590400u){
        if(port!=5353||(protocol!=6&&protocol!=17))return 3ULL<<32;
        if(ip==0x0a590401u)return (4ULL<<32)|0xc0000235u;
        if(ip==0x0a590402u)return (5ULL<<32)|0xc0000236u;
        return 3ULL<<32;
    }
    return route(ctx,ip);
}
static int route6(void *ctx,const uint8_t ip[16],uint8_t target[16]){
    (void)ctx;if(ip[15]!=1&&ip[15]!=2)return 3;
    memcpy(target,ip,16);target[0]=0x20;target[1]=1;target[15]=9;return ip[15];
}
static void clear_mock(void *p){memset(p,0,4);}
static void close_mock(void *p){(void)p;}
static int open_mock(const void *ctx,void *p,const struct flow_key *key,int udp){(void)ctx;(void)p;(void)udp;opened=*key;return 0;}
static size_t frame_mock(const unsigned char *p,size_t n,unsigned char *out,size_t cap){assert(cap>=n+1);out[0]=0x42;memcpy(out+1,p,n);return n+1;}
static int send_mock(const void *ctx,void *p,const struct flow_key *key,int udp,const unsigned char *d,size_t n){(void)ctx;(void)p;if(!udp){assert(n==0);request_ready=1;return SEND_OK;}assert(key->dst==htonl(0xc6120001));assert(n==4&&d[0]==0x42);datagrams++;return datagrams==again_at?SEND_AGAIN:SEND_OK;}
int main(void){
    struct dialer_ops mock={.sess_size=4,.clear=clear_mock,.close=close_mock,.flow_open=open_mock,.dgram_frame=frame_mock,.send=send_mock,.connect=connect_mock,.proxy_ready=proxy_ready_mock};
    struct host_router r={.inner={.ops=&mock},.route=route,.dns_port=12345,.upstream_dns=htonl(0x01010101)};
    const struct dialer_ops *ops=&host_dialer;void *s=calloc(1,ops->sess_size);assert(s);
    assert(!(ops->caps&DC_PRECONNECT));assert(!(ops->caps&DC_DGRAM_RAW));
    struct flow_key key={.dst=htonl(0xc6130001),.dport=443};ops->clear(s);assert(!ops->flow_open(&r,s,&key,1));
    assert(host_route_filter(&r,&key,1)==0);
    assert(opened.dst==htonl(0xc6120001)&&opened.dport==443);assert(key.dst==htonl(0xc6130001));
    unsigned char bytes[]={0,3,1,2,3,0,3,4,5,6};
    assert(ops->send(&r,s,&key,1,bytes,sizeof(bytes))==SEND_OK&&datagrams==2);
    datagrams=0;again_at=2;assert(ops->send(&r,s,&key,1,bytes,sizeof(bytes))==SEND_FATAL);
    ops->close(s);ops->clear(s);key.dst=htonl(0xc6139999);assert(host_route_filter(&r,&key,0)!=0);assert(ops->flow_open(&r,s,&key,0)!=0);ops->close(s);
    ops->clear(s);key.dst=htonl(0x0a590003);key.dport=5353;assert(!ops->flow_open(&r,s,&key,1));assert(opened.dst==htonl(0x01010101)&&opened.dport==53);ops->close(s);
    ops->clear(s);key.dst=htonl(0xc6130002);key.dport=80;assert(!ops->flow_open(&r,s,&key,0));assert(ops->fd(s)==-1);ops->close(s);
    r.route_port=route_port;key=(struct flow_key){.dst=htonl(0x0a590401),.dport=5353,.proto=17};
    assert(host_route_filter(&r,&key,1)==0);ops->clear(s);assert(!ops->flow_open(&r,s,&key,1));
    assert(opened.dst==htonl(0xc0000235)&&opened.dport==53);ops->close(s);
    key.dport=443;assert(host_route_filter(&r,&key,1)!=0);ops->clear(s);assert(ops->flow_open(&r,s,&key,1)!=0);ops->close(s);
    key.dport=5353;key.proto=1;assert(host_route_filter(&r,&key,1)!=0);key.proto=17;key.dst=htonl(0x0a590499);assert(host_route_filter(&r,&key,1)!=0);
    key.dst=htonl(0x0a590402);ops->clear(s);assert(!ops->flow_open(&r,s,&key,1));assert(ops->connect(&r,s,2)!=0);
    assert(last_udp_port==53&&!strcmp(last_udp_host,"192.0.2.54"));ops->close(s);
    key.dst=htonl(0x0a590003);assert(host_route_filter(&r,&key,1)!=0); /* model2 cannot bypass managed DNS using legacy virtual upstream */
    key=(struct flow_key){.family=6,.dport=443};key.dst6[0]=0xfd;key.dst6[15]=1;
    ops->clear(s);assert(host_route_filter(&r,&key,0)!=0);assert(ops->flow_open(&r,s,&key,0)!=0);
    r.route6=route6;
    assert(host_route_filter(&r,&key,0)==0);assert(!ops->flow_open(&r,s,&key,0));
    assert(opened.family==6&&opened.dst6[0]==0x20&&opened.dst6[15]==9);
    assert(key.dst6[0]==0xfd&&key.dst6[15]==1);ops->close(s);
    ops->clear(s);key.dst6[15]=2;assert(!ops->flow_open(&r,s,&key,0));
    assert(ops->connect(&r,s,2)!=0&&dial6_calls==1&&direct6[0]==0x20&&direct6[15]==9);ops->close(s);
    ops->clear(s);key.dst6[15]=3;assert(host_route_filter(&r,&key,0)!=0);assert(ops->flow_open(&r,s,&key,0)!=0);ops->close(s);
    r.flow_result=readiness_mock;key=(struct flow_key){.dst=htonl(0xc6130001),.sport=44001,.dport=443,.proto=6};
    ops->clear(s);assert(!ops->flow_open(&r,s,&key,0)&&readiness_events==0);
    assert(!ops->connect(&r,s,2)&&readiness_events==0); /* local SYN-ACK/transport alone not ready */
    assert(ops->send(&r,s,&key,0,NULL,0)==SEND_OK&&readiness_events==1&&last_ready==1);
    assert(observed_original.dst==key.dst&&observed_original.sport==44001&&observed_original.dport==443);
    assert(ops->send(&r,s,&key,0,NULL,0)==SEND_OK&&readiness_events==1);
    ops->close(s);assert(readiness_events==1);
    ops->clear(s);request_ready=0;connect_failure=1;assert(!ops->flow_open(&r,s,&key,0));
    assert(ops->connect(&r,s,2)!=0&&readiness_events==2&&last_ready==0);
    ops->close(s);assert(readiness_events==2);
    int pair[2];assert(socketpair(AF_UNIX,SOCK_DGRAM,0,pair)==0);owned_udp=pair[0];
    ops->clear(s);key=(struct flow_key){.dst=htonl(0xc6130002),.dport=9000,.proto=17};
    assert(!ops->flow_open(&r,s,&key,1)&&!ops->connect(&r,s,2));
    unsigned char zero_frame[2]={0,0},block[16];
    assert(ops->send(&r,s,&key,1,zero_frame,2)==SEND_OK);
    assert(recv(pair[1],block,sizeof(block),MSG_DONTWAIT)==0);
    assert(send(pair[1],block,0,0)==0);const unsigned char *received=NULL;size_t got=99;
    assert(ops->read(s,block,sizeof(block),&received,&got)==READ_UDP_EMPTY&&got==0);
    assert(ops->read(s,block,sizeof(block),&received,&got)==0&&got==0); /* EAGAIN is NOT another datagram */
    ops->close(s);close(pair[0]);close(pair[1]);owned_udp=-1;
    r.route_owned=owned_route;owner_action=3;
    key=(struct flow_key){.dst=htonl(0xc6130001),.src=htonl(0x0a590002),.sport=50123,.dport=443,.proto=6};
    ops->clear(s);assert(host_route_filter(&r,&key,0)==0&&ops->flow_open(&r,s,&key,0)!=0);
    assert(owner_calls==1&&owner_original.dst==key.dst&&owner_original.src==key.src&&owner_original.sport==50123);ops->close(s);
    owner_action=1;ops->clear(s);assert(!ops->flow_open(&r,s,&key,0));
    assert(opened.dst==htonl(0xc000024d)&&opened.src==key.src&&opened.sport==key.sport&&key.dst==htonl(0xc6130001));ops->close(s);
    key=(struct flow_key){.family=6,.sport=50124,.dport=443,.proto=6};key.src6[0]=0xfd;key.src6[15]=19;key.dst6[0]=0xfd;key.dst6[15]=71;
    ops->clear(s);assert(!ops->flow_open(&r,s,&key,0)&&flow_is6(&opened));
    assert(!memcmp(owner_original.src6,key.src6,16)&&!memcmp(owner_original.dst6,key.dst6,16));
    assert(opened.dst6[0]==0x20&&opened.dst6[15]==55&&opened.sport==50124);ops->close(s);
    owner_action=2;ops->clear(s);assert(!ops->flow_open(&r,s,&key,0));assert(ops->connect(&r,s,2)!=0&&direct6[15]==55);ops->close(s);
    owner_action=4;key=(struct flow_key){.dst=htonl(0xc6130001),.sport=50125,.dport=5353,.proto=17};
    ops->clear(s);assert(ops->flow_open(&r,s,&key,1)!=0);ops->close(s); /* arbitrary destinations cannot request DNS port rewrite */
    key.dst=htonl(0x0a590401);key.src=htonl(0x0a590002);
    ops->clear(s);assert(!ops->flow_open(&r,s,&key,1));
    assert(opened.dst==htonl(0xc000024d)&&opened.dport==53&&owner_original.dport==5353&&owner_original.src==key.src);
    ops->close(s);owner_action=5;ops->clear(s);assert(!ops->flow_open(&r,s,&key,1));
    assert(ops->connect(&r,s,2)!=0&&last_udp_port==53&&!strcmp(last_udp_host,"192.0.2.77"));ops->close(s);
    key.dport=853;ops->clear(s);assert(ops->flow_open(&r,s,&key,1)!=0);ops->close(s);
    key.dport=5353;key.proto=1;ops->clear(s);assert(ops->flow_open(&r,s,&key,1)!=0);ops->close(s);
    key.proto=17;owner_action=3;int denied_events=readiness_events;
    ops->clear(s);assert(ops->flow_open(&r,s,&key,1)!=0&&readiness_events==denied_events);ops->close(s);
    /* A destination-only router cannot substitute for the original-owner decision. */
    r.route_port=NULL;owner_action=4;ops->clear(s);assert(ops->flow_open(&r,s,&key,1)!=0);ops->close(s);
    r.route_port=route_port;key.family=6;key.dst6[0]=0xfd;key.dst6[15]=1;
    ops->clear(s);assert(ops->flow_open(&r,s,&key,1)!=0);ops->close(s);
    key=(struct flow_key){.family=6,.sport=50127,.dport=5353,.proto=17};key.src6[0]=0xfd;key.src6[15]=19;
    assert(inet_pton(AF_INET6,"fdfe:dcba:9876:dd53::1",key.dst6)==1&&host_dns_control_rewrite(&key));
    owner_action=4;ops->clear(s);assert(!ops->flow_open(&r,s,&key,1));
    assert(flow_is6(&opened)&&opened.dport==53&&opened.dst6[0]==0x20&&opened.dst6[15]==55);
    assert(owner_original.dport==5353&&!memcmp(owner_original.src6,key.src6,16)&&!memcmp(owner_original.dst6,key.dst6,16));ops->close(s);
    owner_action=5;ops->clear(s);assert(!ops->flow_open(&r,s,&key,1)&&ops->connect(&r,s,2)!=0);
    assert(last6_port==53&&last6_udp==1&&direct6[15]==55);ops->close(s);
    key.dport=853;assert(!host_dns_control_rewrite(&key));ops->clear(s);assert(ops->flow_open(&r,s,&key,1)!=0);ops->close(s);
    key.dport=5353;key.proto=1;assert(!host_dns_control_rewrite(&key));ops->clear(s);assert(ops->flow_open(&r,s,&key,1)!=0);ops->close(s);
    key.proto=17;owner_action=3;ops->clear(s);assert(ops->flow_open(&r,s,&key,1)!=0);ops->close(s);
    /* A registered owned callback does not grant IPv6 capture by itself. */
    r.route6=NULL;owner_action=4;int calls_before=owner_calls,dials_before=dial6_calls;
    ops->clear(s);assert(host_route_filter(&r,&key,1)!=0&&ops->flow_open(&r,s,&key,1)!=0);ops->close(s);
    key.dport=443;key.proto=6;owner_action=1;
    ops->clear(s);assert(host_route_filter(&r,&key,0)!=0&&ops->flow_open(&r,s,&key,0)!=0);ops->close(s);
    key.dport=53;key.proto=17;
    ops->clear(s);assert(host_route_filter(&r,&key,1)!=0&&ops->flow_open(&r,s,&key,1)!=0);ops->close(s);
    assert(owner_calls==calls_before&&dial6_calls==dials_before);
    free(s);puts("PASS: dual-family original-owner/no fallback, readiness, exact-port DNS controls/detours, UDP empty/framing/backpressure");return 0;
}
