/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef MCRF_SOCKS_LOCAL_UDP_H
#define MCRF_SOCKS_LOCAL_UDP_H
#include <arpa/inet.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>

/* Private authenticated helper only. Bind BEFORE ASSOCIATE so the server can
 * pin the authenticated client's source port. Never learn it from a datagram.
 * Generic remote SOCKS keeps its existing unspecified-address behavior. */
static int mcrf_socks_local_udp_open(uint16_t *port) {
    struct sockaddr_in local;
    socklen_t length = sizeof(local);
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    memset(&local, 0, sizeof(local));
    local.sin_family = AF_INET;
    local.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd, (struct sockaddr *)&local, sizeof(local)) != 0 ||
        getsockname(fd, (struct sockaddr *)&local, &length) != 0 ||
        length != sizeof(local) || local.sin_port == 0) {
        close(fd); return -1;
    }
    *port = ntohs(local.sin_port);
    return fd;
}

static int mcrf_socks_local_udp_relay(int fd, int family,
                                     const unsigned char *ip, uint16_t port) {
    struct sockaddr_in relay;
    const unsigned char loopback[4] = {127, 0, 0, 1};
    if (fd < 0 || family != 4 || port == 0 || ip == NULL ||
        memcmp(ip, loopback, sizeof(loopback)) != 0) return -1;
    memset(&relay, 0, sizeof(relay));
    relay.sin_family = AF_INET;
    relay.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    relay.sin_port = htons(port);
    return connect(fd, (struct sockaddr *)&relay, sizeof(relay));
}
#endif
