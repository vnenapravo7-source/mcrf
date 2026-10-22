#include <assert.h>
#include "node_bind49.h"
int main(void){
 struct vless_node n={0};strcpy(n.host,"original.example.test");strcpy(n.security,"reality");
 steer_bind_node49(&n,"127.0.0.1");assert(!n.sni[0]);assert(!strcmp(n.host,"127.0.0.1"));
 strcpy(n.host,"original.example.test");strcpy(n.sni,"explicit.example.test");steer_bind_node49(&n,"127.0.0.2");assert(!strcmp(n.sni,"explicit.example.test"));
 strcpy(n.host,"original.example.test");n.sni[0]=0;strcpy(n.security,"tls");steer_bind_node49(&n,"127.0.0.3");assert(!strcmp(n.sni,"original.example.test"));
 return 0;
}
