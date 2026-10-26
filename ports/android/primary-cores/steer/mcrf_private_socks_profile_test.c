#include "mcrf_private_socks_profile.h"
#include <assert.h>
#include <stdio.h>
int main(void) {
    const char *u="11111111-1111-4111-8111-111111111111",*p="22222222-2222-4222-8222-222222222222";
    assert(mcrf_private_socks_profile("127.0.0.1",12345,5,u,p));
    assert(!mcrf_private_socks_profile("127.0.0.1",12345,5,"",p));
    assert(!mcrf_private_socks_profile("127.0.0.1",12345,5,u,"short"));
    assert(!mcrf_private_socks_profile("127.0.0.1",12345,4,u,p));
    assert(!mcrf_private_socks_profile("0.0.0.0",12345,5,u,p));
    assert(!mcrf_private_socks_profile("127.0.0.2",12345,5,u,p));
    assert(!mcrf_private_socks_profile("192.0.2.1",12345,5,u,p));
    assert(!mcrf_private_socks_profile("127.0.0.1",80,5,u,p));
    assert(!mcrf_private_socks_profile("127.0.0.1",65536,5,u,p));
    assert(!mcrf_private_socks_profile("127.0.0.1",12345,5,"11111111-1111-4111-8111-11111111111/",p));
    puts("PRIVATE_AUTHENTICATED_LOOPBACK_ADMISSION_PASS");
    return 0;
}
