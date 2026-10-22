#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "probe_policy.h"
#include "route_slots.h"

/* Queued only. Shape policy is NOT real TLS/protocol/worker acceptance. */
int main(void){
    atomic_uint slots;atomic_init(&slots,0);
    for(int i=0;i<32;i++)assert(mcrf_route_slot_acquire(&slots)==i);
    assert(mcrf_route_slot_acquire(&slots)==-1);assert(atomic_load(&slots)==UINT32_MAX);
    for(int i=0;i<32;i+=2)mcrf_route_slot_release(&slots,i);
    for(int i=0;i<32;i+=2)assert(mcrf_route_slot_acquire(&slots)==i);
    assert(mcrf_route_slot_acquire(&slots)==-1);assert(atomic_load(&slots)==UINT32_MAX);
    mcrf_route_slot_release(&slots,-1);assert(atomic_load(&slots)==UINT32_MAX);
    for(int i=0;i<32;i++)mcrf_route_slot_release(&slots,i);
    assert(!atomic_load(&slots));
    assert(mcrf_probe_request_valid("www.gstatic.com","/generate_204"));
    assert(mcrf_probe_request_valid("owned.example.test","/check?a=1"));
    assert(!mcrf_probe_request_valid("owned\r\nInjected:yes","/"));
    assert(!mcrf_probe_request_valid("owned.example.test","/a\r\nInjected:yes"));
    assert(!mcrf_probe_request_valid("owned.example.test","https://other.test/"));
    assert(!mcrf_probe_request_valid("owned.example.test","/a b"));
    assert(!mcrf_probe_request_valid("owned.example.test","/#fragment"));
    char oversize[2050];memset(oversize,'a',sizeof(oversize));oversize[0]='/';oversize[2049]=0;
    assert(!mcrf_probe_request_valid("owned.example.test",oversize));
    const char *valid[] = {"HTTP/1.1 204 No Content\r\n","HTTP/1.0 204 \r\n","HTTP/1.1 204\r\n"};
    for(size_t i=0;i<sizeof(valid)/sizeof(valid[0]);i++)assert(mcrf_probe_status_204((const unsigned char *)valid[i],strlen(valid[i])));
    const char *invalid[]={"HTTP/1.1 200 OK\r\n","HTTP/1.1 302 Found\r\n","HTTP/1.1 503 Down\r\n","HTTP/1.1 2040 Bad\r\n","HTTP/1.1 204 No Content","HTTP/2 204\r\n","204\n","HTTP/1.1 204x\r\n","HTTP/1.1 204 No Content\n","HTTP/1.1 204 \001\r\n"};
    for(size_t i=0;i<sizeof(invalid)/sizeof(invalid[0]);i++)assert(!mcrf_probe_status_204((const unsigned char *)invalid[i],strlen(invalid[i])));
    const char *headers="HTTP/1.1 204 No Content\r\nX-Owned: yes\r\n\r\n";
    for(size_t n=0;n<strlen(headers);n++)assert(mcrf_probe_headers_204((const unsigned char *)headers,n)==0);
    assert(mcrf_probe_headers_204((const unsigned char *)headers,strlen(headers))==1);
    const char *zero="HTTP/1.1 204\r\nContent-Length: 0\r\n\r\n";
    assert(mcrf_probe_headers_204((const unsigned char *)zero,strlen(zero))==1);
    const char *malformed[]={"HTTP/1.1 302 Found\r\n\r\n","HTTP/1.1 204\r\nBadHeader\r\n\r\n",
        "HTTP/1.1 204\r\n X: folded\r\n\r\n","HTTP/1.1 204\r\nX : bad\r\n\r\n",
        "HTTP/1.1 204\r\nContent-Length: 1\r\n\r\n","HTTP/1.1 204\r\nTRANSFER-ENCODING: chunked\r\n\r\n",
        "HTTP/1.1 204\r\nX: a\rBroken: b\r\n\r\n","HTTP/1.1 204\r\n\r\nbody",
        "HTTP/1.1 204\r\nX: \001\r\n\r\n"};
    for(size_t i=0;i<sizeof(malformed)/sizeof(malformed[0]);i++)assert(mcrf_probe_headers_204((const unsigned char *)malformed[i],strlen(malformed[i]))==-1);
    unsigned char large[8193];memset(large,'a',sizeof(large));
    assert(mcrf_probe_headers_204(large,sizeof(large))==-1);
    int64_t uptime=INT64_C(5000000000);
    assert(mcrf_probe_remaining(uptime+44,uptime)==44);
    assert(mcrf_probe_remaining(uptime+44,uptime+44)==0);
    assert(mcrf_probe_remaining(uptime,uptime+1)==0);
    assert(mcrf_probe_remaining(uptime+60000,uptime)==30000);
    assert(mcrf_probe_remaining(uptime,-1)==0);
    return 0;
}
