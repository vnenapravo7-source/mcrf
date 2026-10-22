#ifndef MCRF_STEER_ROUTE_RELAY_H
#define MCRF_STEER_ROUTE_RELAY_H
#include "dialer.h"

/* Standalone per-server backend candidate, never another Android TUN.
 * fd is an OWNED private AF_UNIX stream/datagram endpoint, not a public socket.
 * ready fires once, after actual native protocol connect, or with false.
 * Returns only after THIS session is closed; native multiplexer/process
 * retirement is still the fresh worker owner's independent responsibility.
 * Numeric targets only; the dispatcher must resolve through the chosen
 * server's own DNS detour before handing a domain target to this bridge. */
int steer_route_relay(const struct dialer *dialer,const struct flow_key *target,
                      int udp,int fd,int connect_ms,int idle_ms,
                      void (*ready)(void *,int),int (*cancelled)(void *),void *argument);
#endif
