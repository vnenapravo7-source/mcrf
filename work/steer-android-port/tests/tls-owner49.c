/* Actual SAME production Go ABI, owned socketpair only. No keys/profiles/net. */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#include "tls49_abi.h"
#include "cert_compress49.h"

static int rejected_decoder49(unsigned a,const unsigned char *b,size_t n,unsigned char *o,size_t c,size_t *z){return -1;}

static const char options49[]="{\"security\":\"tls\",\"host\":\"fixture.invalid\",\"sni\":\"fixture.invalid\",\"fingerprint\":\"chrome\",\"insecure\":true,\"timeoutMS\":5000}";
struct opening49 {int fd,notify,rc;uint64_t epoch,handle;};
static long long now49(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (long long)t.tv_sec*1000+t.tv_nsec/1000000;}
static void *open49(void *value){
    struct opening49 *s=value;
    s->rc=mcrf49_open(s->fd,s->epoch,(char *)options49,sizeof(options49)-1,NULL,0,&s->handle);
    unsigned char done=49;(void)write(s->notify,&done,1);return NULL;
}
static int fds49(void){DIR *d=opendir("/proc/self/fd");if(!d)return -1;int n=0;struct dirent *p;while((p=readdir(d)))if(strcmp(p->d_name,".")&&strcmp(p->d_name,".."))n++;closedir(d);return n;}
static int check49(void){
    if(mcrf49_set_decoder(cert_decompress49)||mcrf49_set_decoder(cert_decompress49)||
        mcrf49_set_decoder(NULL)!=-1||mcrf49_set_decoder(rejected_decoder49)!=-1)return 1;
    int sv[2],done[2];if(socketpair(AF_UNIX,SOCK_STREAM,0,sv)||pipe(done))return 1;
    struct opening49 s={.fd=sv[0],.notify=done[1],.epoch=mcrf49_begin()};pthread_t thread;
    if(!s.epoch||pthread_create(&thread,NULL,open49,&s))return 1;
    /* Observe actual wire ClientHello BEFORE STOP, not a canceled dummy task. */
    struct pollfd first={sv[1],POLLIN,0};
    if(poll(&first,1,2000)!=1)return 1;
    unsigned char hello[5];if(read(sv[1],hello,sizeof hello)!=sizeof hello||hello[0]!=22)return 1;
    long long start=now49();mcrf49_stop(s.epoch);
    struct pollfd completion={done[0],POLLIN,0};
    if(poll(&completion,1,1500)!=1)return 1;
    pthread_join(thread,NULL);
    if(now49()-start>1500||s.handle||(s.rc!=-105&&s.rc!=-109))return 1;
    /* Go owns DUPs only. STOP cannot close C's borrowed original descriptor. */
    if(fcntl(sv[0],F_GETFD)<0)return 1;
    uint64_t late=99;
    int rc=mcrf49_open(sv[0],s.epoch,(char *)options49,sizeof(options49)-1,NULL,0,&late);
    if(rc!=-105||late||fcntl(sv[0],F_GETFD)<0)return 1;
    close(sv[0]);close(sv[1]);close(done[0]);close(done[1]);return 0;
}
int main(void){
    signal(SIGPIPE,SIG_IGN);
    /* Warm Go's process-lifetime netpoll descriptors BEFORE the FD baseline. */
    if(check49())return 1;
    int before=fds49();if(before<0)return 1;
    for(int i=0;i<4;i++)if(check49()||fds49()!=before)return 1;
    puts("TLS_OWNER49 blocked-stop+borrowed-fd+no-late-publish+fd-retirement PASS");return 0;
}
