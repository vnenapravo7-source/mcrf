#ifndef MCRF_TLS_DIAG49_H
#define MCRF_TLS_DIAG49_H
/* Bounded numeric handshake progress ONLY: never keys, names or packet bytes. */
#ifdef __ANDROID__
#include <stdatomic.h>
#include <android/log.h>
static inline void mcrf_tls49(unsigned stage,int code){
 static atomic_uint count;
 if(atomic_fetch_add(&count,1)<24)
  __android_log_print(ANDROID_LOG_INFO,"McrfTlsP049","stage=%u code=%d",stage,code);
}
#else
static inline void mcrf_tls49(unsigned stage,int code){(void)stage;(void)code;}
#endif
#endif
