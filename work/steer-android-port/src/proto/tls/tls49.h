#ifndef MCRF_TLS49_H
#define MCRF_TLS49_H
#include "tls49_abi.h"
struct tr_link;
struct tr_node;
/* runtime_epoch is captured by each immutable protocol node. A late open must
 * never consult a mutable "current" epoch and attach itself to a new owner. */
int tls49_open(struct tr_link *link,const struct tr_node *node,const char *required,int timeout_s);
int tls49_validate_node(const struct tr_node *node);
int tls49_read(struct tr_link *link,unsigned char *data,size_t cap,size_t *got);
int tls49_write(struct tr_link *link,const unsigned char *data,size_t length);
#endif
