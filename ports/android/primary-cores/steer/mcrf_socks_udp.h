/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef MCRF_SOCKS_UDP_H
#define MCRF_SOCKS_UDP_H
#include <stddef.h>

/* RFC1928 UDP framing, without allocations or reading before length checks.
 * A valid empty UDP payload is preserved. Fragmented/malformed frames are not
 * reinterpreted as application data. The caller retains all flow ownership. */
static int mcrf_socks_udp_payload(const unsigned char *packet, size_t length, size_t *offset) {
    size_t header;
    if (packet == NULL || offset == NULL || length < 4) return 0;
    if (packet[0] != 0 || packet[1] != 0 || packet[2] != 0) return 0;
    switch (packet[3]) {
    case 1: header = 10; break;
    case 4: header = 22; break;
    case 3:
        if (length < 5 || packet[4] == 0) return 0;
        header = 7 + (size_t)packet[4];
        break;
    default: return 0;
    }
    if (length < header) return 0;
    *offset = header;
    return 1;
}
#endif
