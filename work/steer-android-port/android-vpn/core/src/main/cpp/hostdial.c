#include "hostdial.h"
#include "transport.h"
#include <arpa/inet.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <sys/socket.h>
#include <unistd.h>

struct session {
    const struct host_router *router;
    void *inner;
    struct flow_key key;
    struct flow_key original;
    int direct, local, udp, fd,notified;
};
static void notify_result(struct session *s,int ready){
    if(s->notified||!s->router||!s->router->flow_result||s->udp||s->local)return;
    s->notified=1;s->router->flow_result(s->router->host,&s->original,ready);
}
static void notify_ready(struct session *s){
    if(!s->router||!s->router->flow_result)return;
    if(s->direct?s->fd>=0:(s->inner&&s->router->inner.ops->proxy_ready&&s->router->inner.ops->proxy_ready(s->inner)))notify_result(s,1);
}
int host_route_filter(void *ctx,const struct flow_key *key,int udp){
    (void)udp;const struct host_router *r=ctx;
    if(flow_is6(key)){
        if(!r->route6)return -1; /* owned callback cannot enable IPv6 capture */
        if(key->dport==53)return 0;
        if(r->route_owned)return 0; /* owner checked once in flow_open, before any dial */
        uint8_t target[16];int action=r->route6?r->route6(r->host,key->dst6,target):3;
        return action==1||action==2?0:-1;
    }
    if(key->dport==53||(!r->route_port&&ntohl(key->dst)==0x0a590003u&&key->dport==5353))return 0;
    if(r->route_owned)return 0;
    unsigned action=(r->route_port?r->route_port(r->host,ntohl(key->dst),key->dport,key->proto):r->route(r->host,ntohl(key->dst)))>>32;
    return action==1||action==2||(r->route_port&&(ntohl(key->dst)&0xfffffc00u)==0x0a590400u&&key->dport==5353&&(action==4||action==5))?0:-1;
}
static const char *peer(const void *ctx){(void)ctx;return "android-host-router";}
static void describe(const void *ctx,char *out,size_t n){(void)ctx;snprintf(out,n,"Android DNS/domain routing");}
static const char *error_text(int rc){(void)rc;return "Host route or connection rejected";}
static void clear(void *p){struct session *s=p;s->inner=NULL;s->router=NULL;s->fd=-1;s->notified=0;}
static void close_session(void *p){
    struct session *s=p;
    notify_result(s,0);
    if(s->inner){s->router->inner.ops->close(s->inner);free(s->inner);s->inner=NULL;}
    if(s->fd>=0){close(s->fd);s->fd=-1;}
}
static int open_flow(const void *ctx,void *p,const struct flow_key *key,int udp){
    const struct host_router *r=ctx;struct session *s=p;
    s->router=r;s->key=s->original=*key;s->udp=udp;s->direct=0;s->local=0;s->notified=0;
    if(flow_is6(key)&&!r->route6)return -1;
    if(flow_is6(key)&&key->dport==53&&r->route6){
        s->direct=s->local=1;s->key.family=4;s->key.dst=htonl(INADDR_LOOPBACK);s->key.dport=r->dns_port;
    }
    else if(!flow_is6(key)&&key->dport==53){s->direct=s->local=1;s->key.dst=htonl(INADDR_LOOPBACK);s->key.dport=r->dns_port;}
    else if(!flow_is6(key)&&!r->route_port&&ntohl(key->dst)==0x0a590003u && key->dport==5353){s->key.dst=r->upstream_dns;s->key.dport=53;}
    else if(r->route_owned){
        int action=r->route_owned(r->host,key,&s->key);
        if((action==4||action==5)&&r->route_port&&host_dns_control_rewrite(key)){action-=3;s->key.dport=53;}
        if(action!=1&&action!=2)return -1;
        s->direct=action==2;
    }
    else if(flow_is6(key)){
        int action=r->route6?r->route6(r->host,key->dst6,s->key.dst6):3;
        if(action!=1&&action!=2)return -1;
        s->direct=action==2;
    }
    else {
        uint64_t route=r->route_port?r->route_port(r->host,ntohl(key->dst),key->dport,key->proto):r->route(r->host,ntohl(key->dst));unsigned action=route>>32;
        if(r->route_port&&(action==4||action==5)){
            if(key->dport!=5353||(ntohl(key->dst)&0xfffffc00u)!=0x0a590400u)return -1;
            action-=3;s->key.dport=53;
        }
        if(action!=1&&action!=2)return -1;
        s->key.dst=htonl((uint32_t)route);s->direct=action==2;
    }
    if(!s->direct){
        s->inner=calloc(1,r->inner.ops->sess_size);if(!s->inner)return -1;
        r->inner.ops->clear(s->inner);
        if(r->inner.ops->flow_open(r->inner.ctx,s->inner,&s->key,udp)!=0){close_session(s);return -1;}
    }
    return 0;
}
static int connect_session(const void *ctx,void *p,int timeout){
    (void)ctx;struct session *s=p;
    if(!s->direct){int rc=s->router->inner.ops->connect(s->router->inner.ctx,s->inner,timeout);if(rc)notify_result(s,0);else notify_ready(s);return rc;}
    if(flow_is6(&s->key)){s->fd=tr_dial_ip6(s->key.dst6,s->key.dport,s->udp,timeout);if(s->fd<0)notify_result(s,0);else notify_ready(s);return s->fd<0?-1:0;}
    char ip[INET_ADDRSTRLEN];struct in_addr addr={s->key.dst};inet_ntop(AF_INET,&addr,ip,sizeof(ip));
    if(!s->local){s->fd=s->udp?tr_dial_udp(ip,s->key.dport):tr_dial(ip,s->key.dport,timeout);if(s->fd<0)notify_result(s,0);else notify_ready(s);return s->fd<0?-1:0;}
    /* Only our private loopback DNS listener bypasses socket protection. */
    int fd=socket(AF_INET,(s->udp?SOCK_DGRAM:SOCK_STREAM)|SOCK_CLOEXEC,0);if(fd<0)return -1;
    struct timeval tv={.tv_sec=timeout};setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof(tv));
    struct sockaddr_in sa={.sin_family=AF_INET,.sin_port=htons(s->key.dport),.sin_addr=addr};
    if(connect(fd,(struct sockaddr *)&sa,sizeof(sa))){close(fd);return -1;}
    s->fd=fd;return 0;
}
static int fd_of(const void *p){const struct session *s=p;return s->direct?s->fd:(s->inner?s->router->inner.ops->fd(s->inner):-1);}
static int has_data(const void *p){const struct session *s=p;return !s->direct&&s->inner&&s->router->inner.ops->has_data(s->inner);}
static int aux_fd(const void *p){const struct session *s=p;return !s->direct&&s->inner&&s->router->inner.ops->aux_fd?s->router->inner.ops->aux_fd(s->inner):-1;}
static int aux_drain(void *p){struct session *s=p;return !s->direct&&s->inner&&s->router->inner.ops->aux_drain?s->router->inner.ops->aux_drain(s->inner):-1;}
static long room(const void *ctx,void *p){(void)ctx;struct session *s=p;return !s->direct&&s->router->inner.ops->room?s->router->inner.ops->room(s->router->inner.ctx,s->inner):-1;}
static int send_one(struct session *s,const unsigned char *d,size_t n){
    if(!s->direct){
        const struct dialer *inner=&s->router->inner;
        static __thread unsigned char framed[UDP_DGRAM_ABS+128];
        if(s->udp){n=inner->ops->dgram_frame(d,n,framed,sizeof(framed));if(!n)return SEND_FATAL;d=framed;}
        int rc=inner->ops->send(inner->ctx,s->inner,&s->key,s->udp,d,n);
        if(rc==SEND_OK)notify_ready(s);else if(rc==SEND_FATAL)notify_result(s,0);
        return rc;
    }
    if(!n&&!s->udp)return SEND_OK;
    if(s->udp){ssize_t w=send(s->fd,d,n,MSG_DONTWAIT|MSG_NOSIGNAL);return w==(ssize_t)n?SEND_OK:(w<0&&(errno==EAGAIN||errno==EWOULDBLOCK||errno==EINTR)?SEND_AGAIN:SEND_FATAL);}
    size_t at=0;while(at<n){ssize_t w=send(s->fd,d+at,n-at,MSG_NOSIGNAL);if(w<0&&errno==EINTR)continue;if(w<=0)return SEND_FATAL;at+=(size_t)w;}return SEND_OK;
}
static int send_data(const void *ctx,void *p,const struct flow_key *key,int udp,const unsigned char *d,size_t n){
    (void)ctx;(void)key;struct session *s=p;if(!udp)return send_one(s,d,n);
    size_t at=0;while(at<n){if(n-at<2)return SEND_FATAL;size_t len=((size_t)d[at]<<8)|d[at+1];if(len>n-at-2)return SEND_FATAL;
        int rc=send_one(s,d+at+2,len);if(rc!=SEND_OK)return at?SEND_FATAL:rc;at+=len+2;}
    return SEND_OK;
}
static size_t frame(const unsigned char *p,size_t n,unsigned char *out,size_t cap){if(n+2>cap||n>65535)return 0;out[0]=n>>8;out[1]=n;memcpy(out+2,p,n);return n+2;}
static int read_data(void *p,unsigned char *buf,size_t cap,const unsigned char **data,size_t *got){
    struct session *s=p;if(!s->direct)return s->router->inner.ops->read(s->inner,buf,cap,data,got);
    static __thread unsigned char big[UDP_DGRAM_ABS];if(s->udp){buf=big;cap=sizeof(big);}*data=buf;*got=0;
    ssize_t n=recv(s->fd,buf,cap,MSG_DONTWAIT|(s->udp?MSG_TRUNC:0));
    if(n>0){if((size_t)n>cap)return -1;*got=n;return 0;}
    if(n==0&&s->udp)return READ_UDP_EMPTY;
    if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK||errno==EINTR))return 0;return -1;
}
static int deliver(const void *ctx,void *p,int udp,const unsigned char *d,size_t n,dialer_emit_fn emit,void *arg){
    (void)ctx;struct session *s=p;return s->direct?emit(arg,d,n):s->router->inner.ops->deliver(s->router->inner.ctx,s->inner,udp,d,n,emit,arg);
}
const struct dialer_ops host_dialer={.name="android-router",.caps=DC_ACK_PACED,.sess_size=sizeof(struct session),
    .peer=peer,.describe=describe,.strerror=error_text,.connect=connect_session,.close=close_session,.clear=clear,.fd=fd_of,
    .has_data=has_data,.aux_fd=aux_fd,.aux_drain=aux_drain,.flow_open=open_flow,.send=send_data,.room=room,
    .dgram_frame=frame,.dgram_max=UDP_DGRAM_MAX,.read=read_data,.deliver=deliver,.rcv_wnd_max=32768};
