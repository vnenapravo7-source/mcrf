#define _GNU_SOURCE
#include "route_relay.h"
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

struct relay_io {int fd,udp,idle_ms;int64_t last;int (*cancelled)(void *);void *arg;};
static int64_t tick(void){struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t))return -1;return (int64_t)t.tv_sec*1000+t.tv_nsec/1000000;}
static int alive(struct relay_io *io){int64_t now=tick();return !io->cancelled(io->arg)&&now>=io->last&&now-io->last<io->idle_ms;}
static int emit(void *arg,const unsigned char *data,size_t size){
    struct relay_io *io=arg;size_t offset=0;
    if(io->udp&&size>UDP_DGRAM_ABS)return -1;
    /* Zero-size UDP is a real datagram, not stream EOF. */
    do {
        if(!alive(io))return -1;
        ssize_t n=send(io->fd,data+offset,size-offset,MSG_DONTWAIT|MSG_NOSIGNAL);
        if(n>=0){
            if(io->udp&&(size_t)n!=size)return -1;
            if(!io->udp&&n==0&&size!=0)return -1;
            offset+=(size_t)n;io->last=tick();
            if(io->udp)return 0;
        }else{
            if(errno==EINTR)continue;
            if(errno!=EAGAIN&&errno!=EWOULDBLOCK)return -1;
            struct pollfd p={.fd=io->fd,.events=POLLOUT};
            int rc=poll(&p,1,50);
            if(rc<0&&errno==EINTR)continue;
            if(rc<0||(p.revents&(POLLHUP|POLLERR|POLLNVAL)))return -1;
        }
    }while(offset<size);
    return 0;
}

int steer_route_relay(const struct dialer *dialer,const struct flow_key *target,
                      int udp,int fd,int connect_ms,int idle_ms,
                      void (*ready)(void *,int),int (*cancelled)(void *),void *argument){
    int rc=-1,announced=0,opened=0;
    void *session=NULL;unsigned char *buffer=NULL,*pending=NULL,*payload=NULL;
    struct relay_io io={.fd=fd,.udp=udp,.idle_ms=idle_ms,.last=tick(),.cancelled=cancelled,.arg=argument};
    const struct dialer_ops *ops=dialer?dialer->ops:NULL;
    if(fd<0||!ops||!target||!ready||!cancelled||cancelled(argument)||connect_ms<1||connect_ms>30000||idle_ms<100||idle_ms>300000||
       (udp!=0&&udp!=1)||(target->family!=4&&target->family!=6)||(target->proto!=(udp?17:6))||!target->dport||
       !ops->sess_size||!ops->clear||!ops->flow_open||!ops->connect||!ops->send||!ops->read||!ops->deliver||!ops->close||!ops->fd||
       (udp&&!ops->dgram_frame))goto done;
    int socket_type=0;socklen_t type_n=sizeof(socket_type);
    struct sockaddr_storage local;socklen_t local_n=sizeof(local);
    if(getsockopt(fd,SOL_SOCKET,SO_TYPE,&socket_type,&type_n)||getsockname(fd,(struct sockaddr *)&local,&local_n)||
       local.ss_family!=AF_UNIX||socket_type!=(udp?SOCK_DGRAM:SOCK_STREAM))goto done;
    session=calloc(1,ops->sess_size);buffer=malloc(TUNNEL_BUF);
    pending=malloc(UDP_DGRAM_ABS+64);payload=malloc(UDP_DGRAM_ABS);
    if(!session||!buffer||!pending||!payload)goto done;
    ops->clear(session);opened=1;
    if(ops->flow_open(dialer->ctx,session,target,udp)||ops->connect(dialer->ctx,session,(connect_ms+999)/1000))goto done;
    if(cancelled(argument)||tick()<io.last||tick()-io.last>connect_ms)goto done;
    /* A client retired during handshake must not receive late success. */
    struct pollfd initial={.fd=fd,.events=0};
    if(poll(&initial,1,0)<0||(initial.revents&(POLLHUP|POLLERR|POLLNVAL)))goto done;
    announced=1;ready(argument,1);io.last=tick();
    /* Prime server-first TCP protocols through the same send(0) contract as
     * the TUN stack. Success still does not manufacture target reachability. */
    size_t pending_n=0;int has_pending=!udp,local_eof=0;
    size_t dgram_max=ops->dgram_max?ops->dgram_max:UDP_DGRAM_MAX;
    if(dgram_max>UDP_DGRAM_ABS)dgram_max=UDP_DGRAM_ABS;
    while(alive(&io)){
        int remote=ops->fd(session),aux=ops->aux_fd?ops->aux_fd(session):-1;
        long room=!udp&&ops->room?ops->room(dialer->ctx,session):-1;
        struct pollfd fds[3]={{.fd=local_eof?-1:fd,.events=has_pending||room==0?0:POLLIN},
            {.fd=remote,.events=POLLIN},{.fd=aux,.events=POLLIN}};
        int buffered=ops->has_data&&ops->has_data(session);
        int result=poll(fds,3,buffered?0:50);
        if(result<0){if(errno==EINTR)continue;break;}
        /* Stream read half-close is handled below; a datagram owner closes
         * both halves at retirement. No timer or closed fd is cleanup ACK. */
        if(fds[0].revents&(POLLERR|POLLNVAL))break;
        if((fds[0].revents&POLLHUP)&&!(fds[0].revents&POLLIN))break;
        if(fds[2].revents&POLLIN){if(!ops->aux_drain||ops->aux_drain(session))break;}
        if(fds[2].revents&(POLLERR|POLLHUP|POLLNVAL))break;
        if(!has_pending&&(fds[0].revents&POLLIN)){
            size_t limit=udp?dgram_max:4096;
            if(!udp&&room>=0&&(size_t)room<limit)limit=(size_t)room;
            ssize_t n=recv(fd,payload,limit,MSG_DONTWAIT|(udp?MSG_TRUNC:0));
            if(n>=0){
                if(!udp&&!n){
                    /* No generic protocol half-close operation exists. remote
                     * may be a SHARED mux notification fd, not a stream socket.
                     * Never shutdown it. Drain the response until remote EOF,
                     * bounded idle expiry or exact owner's cancellation. */
                    local_eof=1;
                }
                else if((size_t)n<=limit){
                    pending_n=udp?ops->dgram_frame(payload,(size_t)n,pending,UDP_DGRAM_ABS+64):(size_t)n;
                    if(!udp)memcpy(pending,payload,pending_n);
                    if(udp&&(!pending_n||pending_n>UDP_DGRAM_ABS+64))break;
                    has_pending=1;io.last=tick();
                } /* Oversize UDP is dropped whole, never truncated to a valid packet. */
            }else if(errno!=EINTR&&errno!=EAGAIN&&errno!=EWOULDBLOCK)break;
        }
        if(has_pending){
            int sent=ops->send(dialer->ctx,session,target,udp,pending,pending_n);
            if(sent==SEND_FATAL)break;
            if(sent==SEND_OK){memset(pending,0,pending_n);pending_n=0;has_pending=0;io.last=tick();}
            /* SEND_AGAIN retains the exact framed datagram/stream bytes. */
        }
        if(buffered||(fds[1].revents&POLLIN)){
            const unsigned char *data=NULL;size_t n=0;
            if(ops->read(session,buffer,TUNNEL_BUF,&data,&n))break;
            if(n&&(!data||ops->deliver(dialer->ctx,session,udp,data,n,emit,&io)))break;
        }else if(fds[1].revents&(POLLERR|POLLHUP|POLLNVAL)){rc=0;break;}
    }
done:
    if(!announced&&ready)ready(argument,0);
    if(opened)ops->close(session);
    if(pending){memset(pending,0,UDP_DGRAM_ABS+64);free(pending);}
    if(payload){memset(payload,0,UDP_DGRAM_ABS);free(payload);}
    if(buffer){memset(buffer,0,TUNNEL_BUF);free(buffer);}
    free(session);
    if(fd>=0){shutdown(fd,SHUT_RDWR);close(fd);}
    return rc;
}
