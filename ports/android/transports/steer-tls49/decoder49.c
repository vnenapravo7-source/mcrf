//go:build cgo && (android || linux || darwin)

/* C function pointer only; never a retained Go pointer. The signed Steer
 * module lives for the whole worker process. Install once, no replacement. */
#include "decoder49.h"
#include <stdatomic.h>
static _Atomic(mcrf_decoder49) decoder49;
int mcrf_install_decoder49(mcrf_decoder49 decoder) {
    if(!decoder)return -1;
    mcrf_decoder49 expected=NULL;
    if(atomic_compare_exchange_strong(&decoder49,&expected,decoder))return 0;
    return expected==decoder?0:-1;
}
int mcrf_decode49(unsigned offered,const unsigned char *body,size_t body_n,unsigned char *out,size_t cap,size_t *out_n) {
    mcrf_decoder49 decoder=atomic_load(&decoder49);
    if(!out_n)return -1;
    *out_n=0;
    if(!decoder)return -1; /* no decoder or trust fallback */
    return decoder(offered,body,body_n,out,cap,out_n);
}
