/* Queued P2 isolated REAL socketpair/IO/STOP model with a fake protocol dialer.
 * Does not prove the actual SOCKS/TLS/JNI/Android worker path. No TUN/public IO. */
#define _GNU_SOURCE
#include <assert.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#include "route_relay.h"

struct fixture {
    int udp,remote,opened,closed,ready,success,sends,retries,small_window;
    atomic_int stop;
    pthread_mutex_t lock;pthread_cond_t changed;pthread_t peer;
    struct flow_key expected;
    unsigned char retained[128];size_t retained_n;
};
struct session {int fd;struct fixture *owner;};
static void clear_session(void *arg){struct session *s=arg;memset(s,0,sizeof(*s));s->fd=-1;}
static int flow_open(const void *arg,void *storage,const struct flow_key *key,int udp){
    struct fixture *f=(struct fixture *)arg;struct session *s=storage;s->owner=f;
    assert(udp==f->udp&&key->family==f->expected.family&&key->dport==f->expected.dport&&key->proto==f->expected.proto);
    if(key->family==6)assert(!memcmp(key->dst6,f->expected.dst6,16));else assert(key->dst==f->expected.dst);
    f->opened++;return 0;
}
static void *echo_peer(void *arg){
    struct fixture *f=arg;unsigned char bytes[256];
    while(!atomic_load(&f->stop)){
        struct pollfd p={.fd=f->remote,.events=POLLIN};int rc=poll(&p,1,100);
        if(rc<0&&errno==EINTR)continue;
        if(rc<0||(p.revents&(POLLHUP|POLLERR|POLLNVAL)))break;
        if(!(p.revents&POLLIN))continue;
        ssize_t n=recv(f->remote,bytes,sizeof(bytes),0);if(n<=0)break;
        assert(send(f->remote,bytes,(size_t)n,MSG_NOSIGNAL)==n);
    }
    close(f->remote);return NULL;
}
static int connect_session(const void *arg,void *storage,int seconds){
    struct fixture *f=(struct fixture *)arg;struct session *s=storage;assert(seconds>0);
    int pair[2];assert(!socketpair(AF_UNIX,(f->udp?SOCK_DGRAM:SOCK_STREAM)|SOCK_CLOEXEC,0,pair));
    s->fd=pair[0];f->remote=pair[1];assert(!pthread_create(&f->peer,NULL,echo_peer,f));return 0;
}
static int session_fd(const void *arg){return ((const struct session *)arg)->fd;}
static long room(const void *arg,void *storage){(void)storage;return ((const struct fixture *)arg)->small_window?3:-1;}
static int send_session(const void *arg,void *storage,const struct flow_key *key,int udp,const unsigned char *data,size_t n){
    (void)key;struct fixture *f=(struct fixture *)arg;struct session *s=storage;assert(udp==f->udp);f->sends++;
    if(!udp&&!n)return SEND_OK; /* Server-first priming, no application byte. */
    if(f->retries<2){
        if(!f->retries){assert(n<=sizeof(f->retained));memcpy(f->retained,data,n);f->retained_n=n;}
        else assert(n==f->retained_n&&!memcmp(data,f->retained,n));
        f->retries++;return SEND_AGAIN;
    }
    if(f->retries==2){assert(n==f->retained_n&&!memcmp(data,f->retained,n));f->retries++;}
    return send(s->fd,data,n,MSG_NOSIGNAL)==(ssize_t)n?SEND_OK:SEND_FATAL;
}
static size_t frame(const unsigned char *data,size_t n,unsigned char *out,size_t capacity){
    if(n>64||capacity<n+2)return 0;
    out[0]=(unsigned char)(n>>8);out[1]=(unsigned char)n;memcpy(out+2,data,n);return n+2;
}
static int read_session(void *arg,unsigned char *bytes,size_t capacity,const unsigned char **out,size_t *n){
    struct session *s=arg;ssize_t got=recv(s->fd,bytes,capacity,0);if(got<=0)return -1;*out=bytes;*n=(size_t)got;return 0;
}
static int deliver(const void *arg,void *storage,int udp,const unsigned char *data,size_t n,dialer_emit_fn emit,void *owner){
    (void)arg;(void)storage;
    if(udp){if(n<2||((size_t)data[0]*256+data[1])!=n-2)return -1;return emit(owner,data+2,n-2);}
    return emit(owner,data,n);
}
static void close_session(void *arg){struct session *s=arg;s->owner->closed++;if(s->fd>=0)close(s->fd);s->fd=-1;}
static const struct dialer_ops ops={.sess_size=sizeof(struct session),.clear=clear_session,.flow_open=flow_open,
    .connect=connect_session,.fd=session_fd,.room=room,.send=send_session,.read=read_session,.deliver=deliver,
    .close=close_session,.dgram_frame=frame,.dgram_max=64};
static void ready(void *arg,int success){struct fixture *f=arg;pthread_mutex_lock(&f->lock);f->ready++;f->success=success;pthread_cond_broadcast(&f->changed);pthread_mutex_unlock(&f->lock);}
static int cancelled(void *arg){return atomic_load(&((struct fixture *)arg)->stop);}
struct run {struct fixture *f;int fd;};
static void *relay(void *arg){struct run *r=arg;struct dialer d={.ops=&ops,.ctx=r->f};
    steer_route_relay(&d,&r->f->expected,r->f->udp,r->fd,1000,5000,ready,cancelled,r->f);return NULL;}
static void scenario(int udp,int family){
    struct fixture f={.udp=udp,.remote=-1,.small_window=!udp};atomic_init(&f.stop,0);
    assert(!pthread_mutex_init(&f.lock,NULL)&&!pthread_cond_init(&f.changed,NULL));
    f.expected.proto=udp?17:6;f.expected.family=(uint8_t)family;f.expected.dport=443;
    if(family==4)assert(inet_pton(AF_INET,"198.18.0.2",&f.expected.dst)==1);
    else assert(inet_pton(AF_INET6,"fd89::2",f.expected.dst6)==1);
    int pair[2];assert(!socketpair(AF_UNIX,(udp?SOCK_DGRAM:SOCK_STREAM)|SOCK_CLOEXEC,0,pair));
    struct run job={.f=&f,.fd=pair[1]};pthread_t worker;assert(!pthread_create(&worker,NULL,relay,&job));
    struct timespec until;assert(!clock_gettime(CLOCK_REALTIME,&until));until.tv_sec+=5;
    pthread_mutex_lock(&f.lock);while(!f.ready)assert(!pthread_cond_timedwait(&f.changed,&f.lock,&until));assert(f.ready==1&&f.success);pthread_mutex_unlock(&f.lock);
    const unsigned char payload[]="owned-p2-relay";unsigned char received[sizeof(payload)];
    if(udp){unsigned char too_big[65];memset(too_big,1,sizeof(too_big));assert(send(pair[0],too_big,sizeof(too_big),0)==(ssize_t)sizeof(too_big));}
    assert(send(pair[0],payload,sizeof(payload),0)==(ssize_t)sizeof(payload));
    if(!udp)assert(!shutdown(pair[0],SHUT_WR)); // Still drain reply; never shutdown native mux/transport fd.
    size_t used=0;
    do {struct pollfd p={.fd=pair[0],.events=POLLIN};assert(poll(&p,1,3000)>0&&(p.revents&POLLIN));
        ssize_t n=recv(pair[0],received+used,sizeof(received)-used,0);assert(n>0);used+=(size_t)n;
    }while(used<sizeof(payload));
    assert(!memcmp(payload,received,sizeof(payload)));
    atomic_store(&f.stop,1);assert(!pthread_join(worker,NULL));assert(!pthread_join(f.peer,NULL));
    assert(f.opened==1&&f.closed==1&&f.ready==1&&f.retries==3);assert(fcntl(pair[1],F_GETFD)==-1&&errno==EBADF);
    close(pair[0]);pthread_cond_destroy(&f.changed);pthread_mutex_destroy(&f.lock);
}
static void wrong_private_socket_type_refused(void){
    struct fixture f={0};atomic_init(&f.stop,0);assert(!pthread_mutex_init(&f.lock,NULL)&&!pthread_cond_init(&f.changed,NULL));
    f.expected.proto=6;f.expected.dport=443;f.expected.family=4;
    int pair[2];assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,pair));
    struct dialer d={.ops=&ops,.ctx=&f};assert(steer_route_relay(&d,&f.expected,0,pair[1],1000,1000,ready,cancelled,&f)==-1);
    assert(f.ready==1&&!f.success&&!f.opened&&!f.closed);assert(fcntl(pair[1],F_GETFD)==-1&&errno==EBADF);
    close(pair[0]);pthread_cond_destroy(&f.changed);pthread_mutex_destroy(&f.lock);
}
int main(void){scenario(0,4);scenario(0,6);scenario(1,4);scenario(1,6);wrong_private_socket_type_refused();return 0;}
