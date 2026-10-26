/* SPDX-License-Identifier: GPL-3.0-or-later */
#define _GNU_SOURCE
#include "src/proto/proxy/pxsocks.c"
#ifdef NDEBUG
#error "The actual Steer SOCKS wire fixture requires assertions"
#endif
#include <assert.h>
#include <pthread.h>
#include <poll.h>

/* Compile against the actual overlaid pxsocks.c, not a second implementation.
 * Only the remote TCP connector is an owned loopback peer. Generic UDP dial
 * helpers MUST NOT be called: the authenticated source port is pre-bound. */
int tr_dial(const char *host,uint16_t port,int timeout_s){
    (void)timeout_s;assert(strcmp(host,"127.0.0.1")==0);
    int fd=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);assert(fd>=0);
    struct sockaddr_in address={0};address.sin_family=AF_INET;
    address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);address.sin_port=htons(port);
    assert(connect(fd,(struct sockaddr *)&address,sizeof address)==0);
    struct timeval limit={2,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&limit,sizeof limit);
    return fd;
}
int tr_dial_udp(const char *host,uint16_t port){(void)host;(void)port;assert(!"private UDP used unbound generic connector");return -1;}
int tr_dial_ip6(const unsigned char ip[16],uint16_t port,int udp,int timeout_s){(void)ip;(void)port;(void)udp;(void)timeout_s;assert(!"private relay escaped IPv4 loopback");return -1;}

struct peer {int tcp,udp;uint16_t source_port;};
static uint16_t local_port(int fd){struct sockaddr_in address;socklen_t length=sizeof address;assert(getsockname(fd,(struct sockaddr *)&address,&length)==0);return ntohs(address.sin_port);}
static void *serve_owned(void *arg){
    struct peer *peer=arg;int fd=accept(peer->tcp,0,0);assert(fd>=0);
    struct timeval limit={2,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&limit,sizeof limit);
    unsigned char data[512];assert(rd_full(fd,data,3)==0&&data[0]==5&&data[1]==1&&data[2]==2);assert(wr_full(fd,(const unsigned char[]){5,2},2)==0);
    assert(rd_full(fd,data,2)==0&&data[0]==1&&data[1]==12);assert(rd_full(fd,data,12)==0&&memcmp(data,"fixture-user",12)==0);
    assert(rd_full(fd,data,1)==0&&data[0]==12);assert(rd_full(fd,data,12)==0&&memcmp(data,"fixture-pass",12)==0);assert(wr_full(fd,(const unsigned char[]){1,0},2)==0);
    assert(rd_full(fd,data,10)==0);assert(memcmp(data,(const unsigned char[]){5,3,0,1,127,0,0,1},8)==0);
    peer->source_port=(uint16_t)((data[8]<<8)|data[9]);assert(peer->source_port!=0);
    uint16_t port=local_port(peer->udp);unsigned char response[10]={5,0,0,1,127,0,0,1,(unsigned char)(port>>8),(unsigned char)port};assert(wr_full(fd,response,sizeof response)==0);
    for(int i=0;i<2;i++){
        struct sockaddr_in from;socklen_t length=sizeof from;
        ssize_t n=recvfrom(peer->udp,data,sizeof data,0,(struct sockaddr *)&from,&length);assert(n>0);
        assert(from.sin_addr.s_addr==htonl(INADDR_LOOPBACK)&&ntohs(from.sin_port)==peer->source_port);
        assert(sendto(peer->udp,data,(size_t)n,0,(struct sockaddr *)&from,length)==n);
    }
    assert(read(fd,data,1)==0);close(fd);return 0;
}
static int emitted(void *arg,const unsigned char *data,size_t length){int *count=arg;assert(length==7&&memcmp(data,"payload",7)==0);(*count)++;return 0;}
int main(void){
    struct peer peer={0};peer.tcp=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);assert(peer.tcp>=0);
    struct sockaddr_in local={0};local.sin_family=AF_INET;local.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    assert(bind(peer.tcp,(struct sockaddr *)&local,sizeof local)==0&&listen(peer.tcp,1)==0);
    uint16_t udp_port;peer.udp=mcrf_socks_local_udp_open(&udp_port);assert(peer.udp>=0);
    struct timeval limit={2,0};setsockopt(peer.udp,SOL_SOCKET,SO_RCVTIMEO,&limit,sizeof limit);
    pthread_t thread;assert(pthread_create(&thread,0,serve_owned,&peer)==0);
    struct px_node node={0};strcpy(node.vn.host,"127.0.0.1");node.vn.port=local_port(peer.tcp);node.socks_ver=5;
    strcpy(node.user,"fixture-user");strcpy(node.pass,"fixture-pass");
    struct socks_sess session={0};sk_clear(&session);session.udp=1;session.dst=htonl(0xc6336407);session.dport=443;
    assert(sk_connect(&node,&session,2)==0);assert(local_port(session.udpfd)!=0);
    int count=0;
    for(int family=0;family<2;family++){
        session.ipv6=family;if(family)assert(inet_pton(AF_INET6,"2001:db8::7",session.dst6)==1);
        const unsigned char frame[]={0,7,'p','a','y','l','o','a','d'};
        assert(sk_send(&node,&session,0,1,frame,sizeof frame)==SEND_OK);
        struct pollfd wait={session.udpfd,POLLIN,0};assert(poll(&wait,1,2000)==1);
        unsigned char incoming[512];ssize_t n=recv(session.udpfd,incoming,sizeof incoming,0);assert(n>0);
        assert(sk_deliver(&node,&session,1,incoming,(size_t)n,emitted,&count)==0);
    }
    assert(count==2);sk_close(&session);assert(pthread_join(thread,0)==0);
    close(peer.tcp);close(peer.udp);return 0;
}
