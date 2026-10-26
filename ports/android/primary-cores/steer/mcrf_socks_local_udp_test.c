/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "mcrf_socks_local_udp.h"
#ifdef NDEBUG
#error "The source-port fixture must retain assertions"
#endif
#include <assert.h>
#include <poll.h>

int main(void) {
    uint16_t source=0, relay_port=0;
    int client=mcrf_socks_local_udp_open(&source);
    int relay=mcrf_socks_local_udp_open(&relay_port);
    const unsigned char loopback[4]={127,0,0,1}, other[4]={127,0,0,2};
    struct sockaddr_in from;
    socklen_t length=sizeof(from);
    unsigned char byte=0;
    struct pollfd readable={relay,POLLIN,0};
    assert(client>=0 && relay>=0 && source!=0 && relay_port!=0);
    assert(mcrf_socks_local_udp_relay(client,4,other,relay_port)==-1);
    assert(mcrf_socks_local_udp_relay(client,6,loopback,relay_port)==-1);
    assert(mcrf_socks_local_udp_relay(client,4,loopback,0)==-1);
    assert(mcrf_socks_local_udp_relay(client,4,loopback,relay_port)==0);
    assert(send(client,"x",1,0)==1 && poll(&readable,1,1000)==1);
    assert(recvfrom(relay,&byte,1,0,(struct sockaddr *)&from,&length)==1);
    assert(byte=='x' && ntohs(from.sin_port)==source);
    assert(from.sin_addr.s_addr==htonl(INADDR_LOOPBACK));
    close(client); close(relay);
    return 0;
}
