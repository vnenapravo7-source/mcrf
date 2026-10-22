/* New #49 source-only cases; join the common native/parser batch, not a micro-run. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "vless.h"
#include "sublink.h"
static void param(struct vless_node *n,const char *key,const char *value){assert(sl_link_param(n,key,strlen(key),value,strlen(value)));}
int main(void){
 struct vless_node n={0};
 param(&n,"network","websocket");assert(!strcmp(n.type,"ws"));
 param(&n,"type","ws");assert(!n.tls_fields_bad);
 param(&n,"type","grpc");assert(n.tls_fields_bad);
 memset(&n,0,sizeof n);param(&n,"network","RAW");assert(!strcmp(n.type,"tcp"));
 param(&n,"fingerprint","hellochrome_114_padding_psk_shuf");assert(!strcmp(n.fp,"hellochrome_114_padding_psk_shuf"));
 param(&n,"alpn","http%2F1.1%2Ch2");assert(n.alpn_set&&!strcmp(n.alpn,"http/1.1,h2"));
 param(&n,"serverName","fixture.example.test");assert(!strcmp(n.sni,"fixture.example.test"));
 param(&n,"sni","fixture.example.test");assert(!n.tls_fields_bad);
 param(&n,"fp","edge");assert(n.tls_fields_bad);
 memset(&n,0,sizeof n);param(&n,"alpn","");assert(n.alpn_set&&!n.alpn[0]);
 memset(&n,0,sizeof n);param(&n,"alpn","h2%2C");assert(n.tls_fields_bad);
 memset(&n,0,sizeof n);param(&n,"alpn","%20http%2F1.1%20%2C%20h2%20");assert(!n.tls_fields_bad&&!strcmp(n.alpn,"http/1.1,h2"));
 char longfp[100];memset(longfp,'a',sizeof longfp-1);longfp[sizeof longfp-1]=0;
 memset(&n,0,sizeof n);param(&n,"fp",longfp);assert(n.tls_fields_bad&&!n.fp[0]);
 memset(&n,0,sizeof n);param(&n,"sni","valid.invalid%00evil.invalid");assert(n.tls_fields_bad&&!n.sni[0]);
 const char *key="vless://00112233-4455-6677-8899-aabbccddeeff@[2001:db8::49]:443?security=tls&sni=fixture.invalid";
 memset(&n,0,sizeof n);assert(!sl_link_parse(key,"vless://",&n,NULL,NULL,NULL)&&!strcmp(n.host,"2001:db8::49")&&n.port==443);
 memset(&n,0,sizeof n);sl_vmess_tls49("{\"ps\":\"text \\\"fp\\\":\\\"chrome\\\"\",\"fp\":\"edge\",\"sni\":\"fixture.invalid\",\"alpn\":[\"http/1.1\",\"h2\"]}",&n);
 assert(!n.tls_fields_bad&&!strcmp(n.fp,"edge")&&!strcmp(n.alpn,"http/1.1,h2"));
 memset(&n,0,sizeof n);sl_vmess_tls49("{\"alpn\":[]}",&n);assert(!n.tls_fields_bad&&n.alpn_set&&!n.alpn[0]);
 memset(&n,0,sizeof n);sl_vmess_tls49("{\"fp\":\"edge\",\"fingerprint\":\"chrome\"}",&n);assert(n.tls_fields_bad);
 memset(&n,0,sizeof n);sl_vmess_tls49("{\"insecure\":true}",&n);assert(!n.tls_fields_bad&&n.allow_insecure&&!n.insecure);
 puts("LINK_OPTIONS49 aliases conflict full-fingerprint explicit-ALPN PASS");return 0;
}
