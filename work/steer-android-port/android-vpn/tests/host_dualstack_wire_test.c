/* Queued whole-P1 numeric target framing, no socket/provider/credentials. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "vless_proto.h"
#include "pxwire.h"

int main(void){
    uint8_t uuid[16]={0},ip[16]={0x20,1,0xd,0xb8},out[80],old[80],ip4[4]={192,0,2,7};
    ip[15]=42;
    size_t n=vless_build_request_ip(uuid,VLESS_CMD_TCP,NULL,ip,6,443,NULL,out,sizeof(out));
    assert(n==38&&out[18]==VLESS_CMD_TCP&&out[19]==1&&out[20]==0xbb&&out[21]==VLESS_ADDR_IPV6);
    assert(!memcmp(out+22,ip,16));
    memset(out,0x5a,sizeof(out));
    assert(!vless_build_request_ip(uuid,VLESS_CMD_TCP,NULL,ip,6,443,NULL,out,37)&&out[37]==0x5a);
    assert(!vless_build_request_ip(uuid,VLESS_CMD_UDP,NULL,NULL,6,443,NULL,out,sizeof(out)));
    assert(!vless_build_request_ip(uuid,VLESS_CMD_TCP,NULL,ip,5,443,NULL,out,sizeof(out)));
    n=vless_build_request(uuid,VLESS_CMD_UDP,NULL,ip4,53,NULL,old,sizeof(old));
    assert(n==26&&n==vless_build_request_ip(uuid,VLESS_CMD_UDP,NULL,ip4,4,53,NULL,out,sizeof(out))&&!memcmp(old,out,n));
    n=vless_build_request_ip(uuid,VLESS_CMD_MUX,NULL,NULL,6,0,NULL,out,sizeof(out));
    assert(n==19&&out[18]==VLESS_CMD_MUX); /* Mux has no target address. */
    n=px_socks_addr_ip(out,sizeof(out),ip,6,443);
    assert(n==19&&out[0]==4&&!memcmp(out+1,ip,16)&&out[17]==1&&out[18]==0xbb);
    memset(out,0x5a,sizeof(out));assert(!px_socks_addr_ip(out,18,ip,6,443)&&out[18]==0x5a);
    assert(!px_socks_addr_ip(out,sizeof(out),ip,5,443));
    uint32_t net;memcpy(&net,ip4,4);n=px_socks_addr(old,net,53);
    assert(n==7&&n==px_socks_addr_ip(out,sizeof(out),ip4,4,53)&&!memcmp(old,out,n));
    struct px_numeric_endpoint endpoint;
    n=px_socks_addr_ip(out,sizeof(out),ip,6,1080);
    assert(px_socks_udp_endpoint(out,n,&endpoint)==0&&endpoint.family==6&&endpoint.port==1080&&!memcmp(endpoint.ip,ip,16));
    memset(endpoint.ip,0x5a,16);endpoint.family=99;endpoint.port=77;
    assert(px_socks_udp_endpoint(out,n-1,&endpoint)!=0&&endpoint.family==99&&endpoint.port==77&&endpoint.ip[15]==0x5a);
    assert(px_socks_udp_endpoint(out,n+1,&endpoint)!=0&&endpoint.family==99);
    out[17]=out[18]=0;assert(px_socks_udp_endpoint(out,n,&endpoint)!=0&&endpoint.family==99);
    uint8_t special[16]={0};
    n=px_socks_addr_ip(out,sizeof(out),special,6,1080);assert(px_socks_udp_endpoint(out,n,&endpoint)!=0); // no IPv4 fallback for unspecified6
    special[10]=special[11]=255;special[12]=192;special[14]=2;special[15]=1;
    n=px_socks_addr_ip(out,sizeof(out),special,6,1080);assert(px_socks_udp_endpoint(out,n,&endpoint)!=0);
    memset(special,0,16);special[0]=0xfe;special[1]=0x80;special[15]=1;
    n=px_socks_addr_ip(out,sizeof(out),special,6,1080);assert(px_socks_udp_endpoint(out,n,&endpoint)!=0);
    special[0]=0xff;special[1]=2;n=px_socks_addr_ip(out,sizeof(out),special,6,1080);assert(px_socks_udp_endpoint(out,n,&endpoint)!=0);
    out[0]=3;assert(px_socks_udp_endpoint(out,n,&endpoint)!=0); // domain relay cannot invoke an unowned resolver
    uint8_t zero4[4]={0};n=px_socks_addr_ip(out,sizeof(out),zero4,4,1080);
    assert(px_socks_udp_endpoint(out,n,&endpoint)==0&&endpoint.family==4&&endpoint.ip[0]==0&&endpoint.ip[3]==0);
    n=px_socks_addr_ip(out,sizeof(out),ip4,4,1080);assert(px_socks_udp_endpoint(out,n,&endpoint)==0&&endpoint.family==4&&!memcmp(endpoint.ip,ip4,4));
    uint8_t method[2]={5,2},auth[2]={1,0};
    assert(px_socks_method_allowed(method,1)&&!px_socks_method_allowed(method,0));
    method[1]=0;assert(!px_socks_method_allowed(method,1)&&px_socks_method_allowed(method,0));
    method[1]=255;assert(!px_socks_method_allowed(method,0)&&!px_socks_method_allowed(method,1));
    method[0]=4;method[1]=0;assert(!px_socks_method_allowed(method,0));
    assert(px_socks_auth_ok(auth));auth[0]=5;assert(!px_socks_auth_ok(auth));auth[0]=1;auth[1]=1;assert(!px_socks_auth_ok(auth));
    assert(px_socks_udp_endpoint(NULL,0,&endpoint)!=0&&px_socks_udp_endpoint(out,n,NULL)!=0);
    assert(!px_socks_method_allowed(NULL,0)&&!px_socks_auth_ok(NULL));
    puts("PASS: VLESS and SOCKS numeric IPv6/IPv4 framing, target bounds and mux separation");return 0;
}
