#ifndef MCRF_DECODER49_H
#define MCRF_DECODER49_H
#include <stddef.h>
typedef int (*mcrf_decoder49)(unsigned,const unsigned char *,size_t,unsigned char *,size_t,size_t *);
int mcrf_install_decoder49(mcrf_decoder49 decoder);
int mcrf_decode49(unsigned offered,const unsigned char *body,size_t body_n,unsigned char *out,size_t cap,size_t *out_n);
#endif
