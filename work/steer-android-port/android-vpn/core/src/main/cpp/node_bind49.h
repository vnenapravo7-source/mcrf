#ifndef STEER_NODE_BIND49_H
#define STEER_NODE_BIND49_H
#include <stdio.h>
#include <string.h>
#include "vless.h"
#include "diag49.h"
/* Replacing a DNS name with its pinned physical address must NOT invent a
 * Reality serverName. An explicitly empty Reality SNI is an actual identity.
 * Ordinary TLS/HTTP carriers retain their original hostname fallback. */
static inline void steer_bind_node49(struct vless_node *node,const char *address){
 int reality=!strcmp(node->security,"reality");
 mcrf_tls49(7,(reality?2:0)|(!node->sni[0]?1:0));
 if(!reality&&!node->sni[0])snprintf(node->sni,sizeof(node->sni),"%s",node->host);
 snprintf(node->host,sizeof(node->host),"%s",address);
}
#endif
