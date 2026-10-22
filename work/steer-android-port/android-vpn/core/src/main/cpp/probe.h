#ifndef MCRF_STEER_HTTPS_PROBE_H
#define MCRF_STEER_HTTPS_PROBE_H
#include "dialer.h"

/* SOURCE CANDIDATE, not an advertised capability. One fresh worker per probe.
 * Caller installs the exact owner's protect/bind callback BEFORE this call.
 * No TUN, listener, direct target socket or generic probe-cache access.
 * HTTPS is transported by the supplied native protocol dialer. Returns a
 * monotonic full-path latency only after trusted TLS + HTTP 204; else -1.
 * Return joins our relay, but is NOT worker/process/multiplexer retirement. */
long steer_https_probe(const struct dialer *dialer,const struct flow_key *target,
                      const char *host,const char *path,int timeout_ms);
#endif
