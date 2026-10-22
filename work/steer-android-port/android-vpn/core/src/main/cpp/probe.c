#define _GNU_SOURCE
#include "probe.h"
#include "probe_policy.h"
#include "reality.h"
#include "tls13.h"
#include "roots.h"
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

static int64_t probe_now(void){
    struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t))return -1;
    return (int64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static int remaining(int64_t deadline){
    return mcrf_probe_remaining(deadline,probe_now());
}
static int deadline_socket(int fd,int64_t deadline){
    int left=remaining(deadline);if(!left)return -1;
    struct timeval tv={.tv_sec=left/1000,.tv_usec=(left%1000)*1000};
    return setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof(tv))||
        setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof(tv))?-1:0;
}
static int write_deadline(int fd,const unsigned char *data,size_t n,int64_t deadline){
    while(n){
        int left=remaining(deadline);if(!left)return -1;
        ssize_t sent=send(fd,data,n,MSG_DONTWAIT|MSG_NOSIGNAL);
        if(sent>0){data+=sent;n-=(size_t)sent;continue;}
        if(sent<0&&errno==EINTR)continue;
        if(sent>=0||(errno!=EAGAIN&&errno!=EWOULDBLOCK))return -1;
        struct pollfd p={.fd=fd,.events=POLLOUT};
        int ready=poll(&p,1,left);
        if(ready<0&&errno==EINTR)continue;
        if(ready<=0||(p.revents&(POLLERR|POLLHUP|POLLNVAL)))return -1;
    }
    return 0;
}
struct probe_relay {
    struct dialer dialer;
    struct flow_key target;
    int fd;
    int64_t deadline;
};
static int relay_emit(void *arg,const unsigned char *data,size_t n){
    struct probe_relay *relay=arg;
    return write_deadline(relay->fd,data,n,relay->deadline);
}
static void *relay_run(void *arg){
    struct probe_relay *r=arg;const struct dialer_ops *ops=r->dialer.ops;
    void *session=calloc(1,ops->sess_size);
    unsigned char *buffer=malloc(TUNNEL_BUF),pending[4096];size_t pending_n=0;
    if(!session||!buffer)goto done;
    ops->clear(session);
    if(ops->flow_open(r->dialer.ctx,session,&r->target,0))goto close_session;
    int left=remaining(r->deadline);if(!left)goto close_session;
    if(ops->connect(r->dialer.ctx,session,(left+999)/1000))goto close_session;
    while(remaining(r->deadline)){
        int remote=ops->fd(session),aux=ops->aux_fd?ops->aux_fd(session):-1;
        struct pollfd fds[3]={{.fd=r->fd,.events=pending_n?0:POLLIN},
            {.fd=remote,.events=POLLIN},{.fd=aux,.events=POLLIN}};
        int buffered=ops->has_data&&ops->has_data(session);
        int wait=remaining(r->deadline);if(wait>50)wait=50;
        int ready=poll(fds,3,buffered?0:wait);
        if(ready<0){if(errno==EINTR)continue;break;}
        if(fds[0].revents&(POLLERR|POLLHUP|POLLNVAL))break;
        if(fds[2].revents&POLLIN){if(!ops->aux_drain||ops->aux_drain(session))break;}
        if(fds[2].revents&(POLLERR|POLLHUP|POLLNVAL))break;
        if(!pending_n&&(fds[0].revents&POLLIN)){
            ssize_t got=recv(r->fd,pending,sizeof(pending),MSG_DONTWAIT);
            if(got>0)pending_n=(size_t)got;
            else if(got==0||(errno!=EINTR&&errno!=EAGAIN&&errno!=EWOULDBLOCK))break;
        }
        if(pending_n){
            // SEND_AGAIN retains exactly the same bytes, as in the TUN stack.
            int sent=ops->send(r->dialer.ctx,session,&r->target,0,pending,pending_n);
            if(sent==SEND_FATAL)break;
            if(sent==SEND_OK){memset(pending,0,pending_n);pending_n=0;}
        }
        if(buffered||(fds[1].revents&POLLIN)){
            size_t got=0;const unsigned char *data=NULL;
            if(ops->read(session,buffer,TUNNEL_BUF,&data,&got))break;
            if(got&&(!data||ops->deliver(r->dialer.ctx,session,0,data,got,relay_emit,r)))break;
        }else if(fds[1].revents&(POLLERR|POLLHUP|POLLNVAL))break;
    }
close_session:
    ops->close(session);
done:
    memset(pending,0,sizeof(pending));
    if(buffer){memset(buffer,0,TUNNEL_BUF);free(buffer);}
    free(session);shutdown(r->fd,SHUT_RDWR);close(r->fd);return NULL;
}
long steer_https_probe(const struct dialer *dialer,const struct flow_key *target,
                      const char *host,const char *path,int timeout_ms){
    if(!dialer||!dialer->ops||!target||target->proto!=6||target->dport!=443||
       timeout_ms<1||timeout_ms>30000||!mcrf_probe_request_valid(host,path))return -1;
    const struct dialer_ops *ops=dialer->ops;
    if(!ops->sess_size||!ops->clear||!ops->flow_open||!ops->connect||!ops->send||!ops->read||!ops->deliver||!ops->close||!ops->fd)return -1;
    int64_t started=probe_now();if(started<0)return -1;
    int64_t deadline=started+timeout_ms;
    int pair[2];if(socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair))return -1;
    struct probe_relay relay={.dialer=*dialer,.target=*target,.fd=pair[1],.deadline=deadline};
    if(deadline_socket(pair[0],deadline)){close(pair[0]);close(pair[1]);return -1;}
    pthread_t worker;if(pthread_create(&worker,NULL,relay_run,&relay)){close(pair[0]);close(pair[1]);return -1;}
    long result=-1;
    struct tls13 *tls=calloc(1,sizeof(*tls));
    struct reality_cfg config={.sni=host,.alpn="http/1.1",.plain=1};
    struct reality_state state;memset(&state,0,sizeof(state));
    unsigned char hello[2048];size_t hello_n=0;
    if(!tls||reality_build_hello(&config,&state,hello,sizeof(hello),&hello_n))goto done;
    if(write_deadline(pair[0],hello,hello_n,deadline)||deadline_socket(pair[0],deadline))goto done;
    struct tls13_auth auth={.host=host,.roots=tls_cert_roots()};
    if(tls13_handshake_auth(tls,pair[0],hello,hello_n,state.priv,&auth))goto done;
    if(tls->alpn[0]&&strcmp(tls->alpn,"http/1.1"))goto done;
    char request[2560];int n=snprintf(request,sizeof(request),
        "GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n",path,host);
    if(n<=0||(size_t)n>=sizeof(request)||deadline_socket(pair[0],deadline)||
       tls13_write(tls,(const unsigned char *)request,(size_t)n))goto done;
    unsigned char buffer[TLS13_MAX_PLAIN],headers[8192];size_t used=0;
    int64_t first=-1;
    while(remaining(deadline)){
        size_t got=0;if(deadline_socket(pair[0],deadline)||tls13_read(tls,buffer,sizeof(buffer),&got))break;
        if(!got)continue;
        if(first<0)first=probe_now();
        if(got>sizeof(headers)-used)break;
        memcpy(headers+used,buffer,got);used+=got;
        int parsed=mcrf_probe_headers_204(headers,used);
        if(parsed){
            if(parsed==1&&first>=started&&remaining(deadline))result=(long)(first-started);
            break;
        }
    }
done:
    if(tls){tls13_free(tls);free(tls);}
    memset(&state,0,sizeof(state));memset(hello,0,sizeof(hello));
    shutdown(pair[0],SHUT_RDWR);close(pair[0]);
    // Socket shutdown/cancel is not proof that the protocol thread exited.
    // Join before releasing its context, regardless of the measured deadline.
    int joined;
    while((joined=pthread_join(worker,NULL))!=0){
        // An unexpected join failure is NOT permission to free the stack that
        // the relay still owns. The exact fresh-worker death barrier/reaper
        // remains responsible for terminating a stuck native invocation.
        result=-1;if(joined!=EINTR)sleep(1);
    }
    return result;
}
