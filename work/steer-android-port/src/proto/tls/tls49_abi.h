#ifndef MCRF_TLS49_ABI_H
#define MCRF_TLS49_ABI_H
#include <stddef.h>
#include <stdint.h>
/* Implemented by the pinned Go helper in the SAME signed external module.
 * Handle/epoch are integers, never Go pointers retained by C. Input/output
 * memory is borrowed only for the duration of the synchronous call. */
uint64_t mcrf49_begin(void);
typedef int (*mcrf_decoder49)(unsigned,const unsigned char *,size_t,unsigned char *,size_t,size_t *);
int mcrf49_set_decoder(mcrf_decoder49 decoder);
void mcrf49_stop(uint64_t epoch);
int mcrf49_validate(char *json,size_t length);
int mcrf49_open(int fd,uint64_t epoch,char *json,size_t length,char *roots,size_t roots_n,uint64_t *handle);
int mcrf49_read(uint64_t handle,char *output,size_t cap,size_t *got,int rx_direct);
int mcrf49_write(uint64_t handle,char *data,size_t length);
int mcrf49_pending(uint64_t handle,int rx_direct);
int mcrf49_alpn(uint64_t handle,char *output,size_t cap);
void mcrf49_close(uint64_t handle);
#endif
