/* Test fixture only: the original C server's public wildcard bind must never
 * expose the owned synthetic SOCKS peer on other emulator interfaces. Wire
 * framing, TLS, congestion control and protocol code remain original. */
#include <sys/socket.h>
#include <netinet/in.h>
#include <string.h>

int __real_bind(int fd, const struct sockaddr *address, socklen_t length);
int __wrap_bind(int fd, const struct sockaddr *address, socklen_t length) {
    if (address != 0 && address->sa_family == AF_INET && length >= sizeof(struct sockaddr_in)) {
        struct sockaddr_in local;
        memcpy(&local, address, sizeof(local));
        if (local.sin_addr.s_addr == htonl(INADDR_ANY)) {
            local.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            return __real_bind(fd, (const struct sockaddr *)&local, sizeof(local));
        }
    }
    if (address != 0 && address->sa_family == AF_INET6 && length >= sizeof(struct sockaddr_in6)) {
        struct sockaddr_in6 local;
        memcpy(&local, address, sizeof(local));
        if (IN6_IS_ADDR_UNSPECIFIED(&local.sin6_addr)) {
            local.sin6_addr = in6addr_loopback;
            return __real_bind(fd, (const struct sockaddr *)&local, sizeof(local));
        }
    }
    return __real_bind(fd, address, length);
}
