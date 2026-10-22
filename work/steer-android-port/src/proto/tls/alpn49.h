/* Explicit ALPN is part of the profile, not a hint to discard. No allocation/network. */
#ifndef MCRF_ALPN49_H
#define MCRF_ALPN49_H
#include <stddef.h>
#include <string.h>

/* NULL: existing browser default; "": explicitly no extension. Required is the
 * transport protocol, not a replacement list. Return -1 before sending a Hello
 * when the configured list cannot carry the selected transport. */
static inline int mcrf_alpn49(const char *configured, const char *required,
                               unsigned char *out, size_t cap, size_t *written) {
    const char *list=configured?configured:(required&&!strcmp(required,"http/1.1")?"http/1.1":"h2,http/1.1");
    size_t at=2;int found=!required||!required[0];
    if(cap<2)return -1;
    if(!list[0]){*written=0;return found?0:-1;}
    for(const char *p=list;*p;){
        const char *end=strchr(p,',');size_t n=end?(size_t)(end-p):strlen(p);
        if(!n||n>255||at+1+n>cap)return -1;
        for(size_t i=0;i<n;i++)if((unsigned char)p[i]<0x21||(unsigned char)p[i]>0x7e)return -1;
        if(required&&strlen(required)==n&&!memcmp(p,required,n))found=1;
        out[at++]=(unsigned char)n;memcpy(out+at,p,n);at+=n;
        if(!end)break;
        p=end+1;if(!p[0])return -1;
    }
    if(!found||at-2>65535)return -1;
    out[0]=(unsigned char)((at-2)>>8);out[1]=(unsigned char)(at-2);*written=at;return 0;
}
#endif
