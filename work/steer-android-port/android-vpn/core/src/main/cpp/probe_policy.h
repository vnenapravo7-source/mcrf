#ifndef MCRF_STEER_PROBE_POLICY_H
#define MCRF_STEER_PROBE_POLICY_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>
static inline int mcrf_probe_remaining(int64_t deadline,int64_t now){
    if(now<0||deadline<=now)return 0;
    int64_t left=deadline-now;return left>30000?30000:(int)left;
}
static inline int mcrf_probe_request_valid(const char *host,const char *path){
    if(!host||!path||!*host||strlen(host)>253||*path!='/'||strlen(path)>2048)return 0;
    for(const unsigned char *p=(const unsigned char *)host;*p;p++)
        if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||*p=='.'||*p=='-'))return 0;
    for(const unsigned char *p=(const unsigned char *)path;*p;p++)if(*p<=32||*p>=127||*p=='#')return 0;
    return 1;
}
static inline int mcrf_probe_status_204(const unsigned char *line,size_t n){
    const unsigned char *end=memchr(line,'\n',n);if(!end||(size_t)(end-line)<13)return 0;
    if(end[-1]!='\r')return 0;
    for(const unsigned char *p=line;p<end-1;p++)if(*p<32||*p>=127)return 0;
    return (!memcmp(line,"HTTP/1.1 204",12)||!memcmp(line,"HTTP/1.0 204",12))&&
        (line[12]==' '||line[12]=='\r');
}
/* -1 malformed/oversize, 0 incomplete, 1 complete strict 204 headers.
 * Never accept a bare status line, redirect, folded field, body framing or
 * unbounded header. Operates on bounded TLS plaintext, not a NUL string. */
static inline int mcrf_probe_headers_204(const unsigned char *data,size_t n){
    if(!data||n>8192)return -1;
    size_t start=0;int status=1;
    for(size_t i=0;i<n;i++){
        unsigned char c=data[i];
        if(c=='\r'){
            if(i+1==n)return 0;
            if(data[i+1]!='\n')return -1;
            continue;
        }
        if(c=='\n'){
            if(i==0||data[i-1]!='\r')return -1;
            size_t len=i-start-1;
            if(status){if(!mcrf_probe_status_204(data+start,i-start+1))return -1;status=0;}
            else if(!len)return i+1==n?1:-1; /* 204 has no message body. */
            else {
                size_t colon=0;
                while(colon<len&&data[start+colon]!=':'){
                    unsigned char t=data[start+colon];
                    if(!((t>='a'&&t<='z')||(t>='A'&&t<='Z')||(t>='0'&&t<='9')||strchr("!#$%&'*+-.^_`|~",t)))return -1;
                    colon++;
                }
                if(!colon||colon==len)return -1;
                /* Tolerate explicit zero length from health endpoints, but
                 * never accept a body or transfer-framing ambiguity. */
                const char *forbidden[]={"content-length","transfer-encoding"};
                for(size_t k=0;k<2;k++){
                    size_t width=strlen(forbidden[k]);int same=colon==width;
                    for(size_t j=0;same&&j<width;j++){
                        unsigned char t=data[start+j];if(t>='A'&&t<='Z')t=(unsigned char)(t-'A'+'a');
                        if(t!=(unsigned char)forbidden[k][j])same=0;
                    }
                    if(same){
                        if(k==1)return -1;
                        size_t first=colon+1,last=len;
                        while(first<last&&(data[start+first]==' '||data[start+first]=='\t'))first++;
                        while(last>first&&(data[start+last-1]==' '||data[start+last-1]=='\t'))last--;
                        if(first==last)return -1;
                        while(first<last)if(data[start+first++]!='0')return -1;
                    }
                }
            }
            start=i+1;
        }else if(c!='\r'&&c!='\t'&&(c<32||c>=127))return -1;
    }
    return n==8192?-1:0;
}
#endif
