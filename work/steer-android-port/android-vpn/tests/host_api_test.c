#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include "tun.h"
#include "transport.h"

static int calls, last_fd, reject;
static int prepare(void *ctx, int fd) {
    assert(ctx == &calls);
    assert(fcntl(fd,F_GETFD)>=0);
    calls++; last_fd=fd;
    return reject ? -1 : 0;
}
int main(void) {
    int pair[2]; assert(socketpair(AF_UNIX,SOCK_DGRAM,0,pair)==0);
    struct tun_dev tun;
    memset(&tun,0xff,sizeof(tun));
    assert(tun_adopt_fd(&tun,pair[0])==1);
    assert(tun.fd!=pair[0] && tun.fd>=0);
    assert(fcntl(tun.fd,F_GETFD)&FD_CLOEXEC);
    assert(!tun.gso && !tun.gro && !tun.rx_gso && !tun.rx);
    close(pair[0]);
    assert(write(pair[1],"packet",6)==6);
    char buf[10]; assert(tun_read_packet(&tun,(unsigned char *)buf,sizeof(buf))==6);
    assert(!memcmp(buf,"packet",6));
    close(tun.fd); close(pair[1]);
    assert(tun_adopt_fd(&tun,-1)==TUN_ESETUP);
    assert(tun_adopt_fd(NULL,0)==TUN_ESETUP);
    puts("PASS: adopted descriptor ownership, CLOEXEC, raw packet, invalid fd");

    int listener=socket(AF_INET,SOCK_STREAM,0); assert(listener>=0);
    struct sockaddr_in addr={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    assert(bind(listener,(struct sockaddr *)&addr,sizeof(addr))==0);
    assert(listen(listener,2)==0);
    socklen_t len=sizeof(addr); assert(getsockname(listener,(struct sockaddr *)&addr,&len)==0);
    transport_set_socket_prepare(prepare,&calls);
    reject=1;
    assert(tr_dial("127.0.0.1",ntohs(addr.sin_port),1)<0);
    assert(calls==1 && fcntl(last_fd,F_GETFD)==-1 && errno==EBADF);
    assert(tr_dial_udp("127.0.0.1",ntohs(addr.sin_port))<0);
    assert(calls==2 && fcntl(last_fd,F_GETFD)==-1 && errno==EBADF);
    puts("PASS: TCP/UDP denied before connect, descriptors closed");
    reject=0;
    int fd=tr_dial("127.0.0.1",ntohs(addr.sin_port),1); assert(fd>=0 && calls==3);
    int peer=accept(listener,NULL,NULL); assert(peer>=0); close(peer); close(fd);
    fd=tr_dial_udp("127.0.0.1",ntohs(addr.sin_port)); assert(fd>=0 && calls==4);close(fd);
    transport_set_socket_prepare(NULL,NULL);
    fd=tr_dial("127.0.0.1",ntohs(addr.sin_port),1); assert(fd>=0 && calls==4);
    close(fd); close(listener);
    puts("PASS: TCP/UDP allowed, unset callback preserves default");
    return 0;
}
