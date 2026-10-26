/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "mcrf_socks_udp.h"
#ifdef NDEBUG
#error "The SOCKS UDP fixture must retain its assertions"
#endif
#include <assert.h>

int main(void) {
    unsigned char packet[263] = {0};
    size_t offset = 999;
    assert(!mcrf_socks_udp_payload(NULL, 0, &offset));
    for (size_t size = 0; size < 4; size++) assert(!mcrf_socks_udp_payload(packet, size, &offset));
    assert(offset == 999);
    packet[3] = 1;
    for (size_t size = 4; size < 10; size++) assert(!mcrf_socks_udp_payload(packet, size, &offset));
    assert(mcrf_socks_udp_payload(packet, 10, &offset) && offset == 10);
    packet[3] = 4;
    for (size_t size = 4; size < 22; size++) assert(!mcrf_socks_udp_payload(packet, size, &offset));
    assert(mcrf_socks_udp_payload(packet, 22, &offset) && offset == 22);
    packet[3] = 3;
    assert(!mcrf_socks_udp_payload(packet, 5, &offset));
    packet[4] = 255;
    assert(!mcrf_socks_udp_payload(packet, 261, &offset));
    assert(mcrf_socks_udp_payload(packet, 262, &offset) && offset == 262);
    packet[262] = 42;
    assert(mcrf_socks_udp_payload(packet, sizeof(packet), &offset) && packet[offset] == 42);
    packet[3] = 1;
    for (size_t field = 0; field < 3; field++) {
        packet[field] = 1;
        assert(!mcrf_socks_udp_payload(packet, sizeof(packet), &offset));
        packet[field] = 0;
    }
    packet[3] = 255;
    assert(!mcrf_socks_udp_payload(packet, sizeof(packet), &offset));
    assert(!mcrf_socks_udp_payload(packet, sizeof(packet), NULL));
    return 0;
}
