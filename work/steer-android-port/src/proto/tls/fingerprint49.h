/* Pinned refraction uTLS aa6edf4b11af identities. DATA, not browser labels on
 * Chrome bytes. Full expanded package remains gated until pending families,
 * TLS12 and compressed-certificate paths have their real implementations. */
#ifndef MCRF_FINGERPRINT49_H
#define MCRF_FINGERPRINT49_H
#include <string.h>
enum fp49_kind {FP49_CHROME,FP49_EDGE,FP49_IOS,FP49_QQ,FP49_SAFARI,FP49_FIREFOX,FP49_PENDING=-1};
static inline enum fp49_kind fp49_kind(const char *name){
    if(!name||!name[0]||!strcmp(name,"chrome")||!strcmp(name,"hellochrome_auto")||!strcmp(name,"hellochrome_133"))return FP49_CHROME;
    if(!strcmp(name,"edge")||!strcmp(name,"helloedge_auto")||!strcmp(name,"helloedge_85"))return FP49_EDGE;
    if(!strcmp(name,"ios")||!strcmp(name,"helloios_auto")||!strcmp(name,"helloios_14"))return FP49_IOS;
    if(!strcmp(name,"qq")||!strcmp(name,"helloqq_auto")||!strcmp(name,"helloqq_11_1"))return FP49_QQ;
    if(!strcmp(name,"safari")||!strcmp(name,"hellosafari_auto")||!strcmp(name,"hellosafari_26_3"))return FP49_SAFARI;
    if(!strcmp(name,"firefox")||!strcmp(name,"hellofirefox_auto")||!strcmp(name,"hellofirefox_148"))return FP49_FIREFOX;
    return FP49_PENDING; /* never silently fall back to Chrome */
}
static inline int fp49_hybrid(enum fp49_kind kind){return kind==FP49_CHROME||kind==FP49_SAFARI||kind==FP49_FIREFOX;}
static inline int fp49_padding(enum fp49_kind kind){return kind==FP49_EDGE||kind==FP49_IOS||kind==FP49_QQ;}
static const unsigned fp49_ios_suites[]={
    0x1301,0x1302,0x1303,0xc02c,0xc02b,0xcca9,0xc030,0xc02f,0xcca8,
    0xc024,0xc023,0xc00a,0xc009,0xc028,0xc027,0xc014,0xc013,
    0x009d,0x009c,0x003d,0x003c,0x0035,0x002f,0xc008,0xc012,0x000a
};
static const unsigned fp49_safari_suites[]={
    0x1302,0x1303,0x1301,0xc02c,0xc02b,0xcca9,0xc030,0xc02f,0xcca8,
    0xc00a,0xc009,0xc014,0xc013,0x009d,0x009c,0x0035,0x002f,0xc008,0xc012,0x000a
};
static const unsigned fp49_ios_sigs[]={0x0403,0x0804,0x0401,0x0503,0x0203,0x0805,0x0805,0x0501,0x0806,0x0601,0x0201};
static const unsigned fp49_safari_sigs[]={0x0403,0x0804,0x0401,0x0503,0x0805,0x0805,0x0501,0x0806,0x0601,0x0201};
static const unsigned fp49_firefox_suites[]={
    0x1301,0x1303,0x1302,0xc02b,0xc02f,0xcca9,0xcca8,0xc02c,0xc030,
    0xc00a,0xc009,0xc013,0xc014,0x009c,0x009d,0x002f,0x0035
};
static const unsigned fp49_firefox_sigs[]={0x0403,0x0503,0x0603,0x0804,0x0805,0x0806,0x0401,0x0501,0x0601,0x0203,0x0201};
#endif
