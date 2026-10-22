/* P2 owned wire fixture ONLY. Links the real protocol dialer, probe and TLS;
 * this executable supplies its OWN ephemeral trust path. Production roots.c,
 * runtime AAR and module capabilities are not changed by this test seam.
 * One fresh process per case, no TUN, listener, user profile or public target. */
#define _GNU_SOURCE
#ifdef NDEBUG
#error "P2 native wire assertions must remain enabled"
#endif
#include <assert.h>
#include <arpa/inet.h>
#include <dirent.h>
#include <signal.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#include "../core/src/main/cpp/probe.h"
#include "proxy.h"
#include "pxdial.h"
#include "platform.h"
#include "transport.h"

static const char *owned_roots;
const char *tls_cert_roots(void){return owned_roots;} /* Fixture link only. */
static int prepared;
static int prepare(void *arg,int fd){
    (void)arg;struct sockaddr_storage address;socklen_t size=sizeof(address);
    if(getsockname(fd,(struct sockaddr *)&address,&size)||address.ss_family!=AF_INET)return -1;
    prepared++;return 0;
}
static int count_fds(void){
    DIR *dir=opendir("/proc/self/fd");assert(dir);int n=0;struct dirent *e;
    while((e=readdir(dir)))if(strcmp(e->d_name,".")&&strcmp(e->d_name,".."))n++;
    closedir(dir);return n;
}
static long long now_ms(void){struct timespec t;assert(!clock_gettime(CLOCK_MONOTONIC,&t));return (long long)t.tv_sec*1000+t.tv_nsec/1000000;}
static atomic_int fixture_finished;
static void *fixture_control(void *unused){
    (void)unused;
    while(!atomic_load(&fixture_finished)){
        struct pollfd input={.fd=STDIN_FILENO,.events=POLLIN};int result=poll(&input,1,50);
        if(result<0)continue;
        if(result>0&&(input.revents&(POLLIN|POLLHUP|POLLERR))){char value;ssize_t n=read(STDIN_FILENO,&value,1);
            if(n<=0||value=='S')_exit(124); /* Own fixture only, never signal another PID. */
        }
    }
    return NULL;
}
static unsigned long long fixture_birth(void){
    char buffer[4096];FILE *file=fopen("/proc/self/stat","r");assert(file&&fgets(buffer,sizeof(buffer),file));assert(!fclose(file));
    char *end=strrchr(buffer,')');assert(end&&end[1]==' ');char *save=NULL,*value=strtok_r(end+2," ",&save);
    for(int field=3;field<22;field++){assert(value);value=strtok_r(NULL," ",&save);}assert(value);
    char *tail;unsigned long long birth=strtoull(value,&tail,10);assert(birth&&(*tail==' '||*tail=='\0'||*tail=='\n'));return birth;
}
int main(int argc,char **argv){
    assert(argc==8||(argc==9&&!strcmp(argv[8],"owned-control")));
    pthread_t control;int controlled=argc==9;
    if(controlled){
        printf("P2_NATIVE_SELF %ld %llu\n",(long)getpid(),fixture_birth());assert(!fflush(stdout));
        char start;if(read(STDIN_FILENO,&start,1)!=1||start!='G')_exit(124);
        atomic_init(&fixture_finished,0);assert(!pthread_create(&control,NULL,fixture_control,NULL));
    }
    int port=atoi(argv[1]),family=atoi(argv[4]),want=atoi(argv[5]),timeout=atoi(argv[6]);
    assert(port>0&&port<=65535&&(family==4||family==6)&&(want==0||want==1)&&timeout>=100&&timeout<=30000);
    assert(argv[2][0]=='/'&&access(argv[2],R_OK)==0);owned_roots=argv[2];
    assert(!strcmp(argv[3],"owned.example.test")||!strcmp(argv[3],"wrong.example.test"));
    signal(SIGPIPE,SIG_IGN);plat_select("android");transport_set_socket_prepare(prepare,NULL);
    assert(strlen(argv[7])==32&&strspn(argv[7],"0123456789abcdef")==32);
    char uri[160];assert(snprintf(uri,sizeof(uri),"socks://owned:%s@127.0.0.1:%d",argv[7],port)>0);
    struct px_node node;memset(&node,0,sizeof(node));assert(!px_parse_url(uri,&node,PX_SOCKS));
    struct dialer dialer={.ops=px_dialer_for(PX_SOCKS),.ctx=&node,.silence_s=3};
    struct flow_key target={.sport=40000,.dport=443,.proto=6,.family=family};
    if(family==4){assert(inet_pton(AF_INET,"198.18.0.2",&target.dst)==1);target.src=htonl(0x0a590002u);}
    else{assert(inet_pton(AF_INET6,"fd89::2",target.dst6)==1);target.src6[0]=0xfd;target.src6[15]=3;}
    int before=count_fds();long long start=now_ms();
    long value=steer_https_probe(&dialer,&target,argv[3],"/owned",timeout);
    long long elapsed=now_ms()-start;
    if(controlled){atomic_store(&fixture_finished,1);assert(!pthread_join(control,NULL));}
    assert((value>=0)==want&&value<=timeout&&prepared==1&&count_fds()==before);
    assert(elapsed>=0&&elapsed<=timeout+2500);
    puts(want?"P2_PROBE_TLS204_OK":"P2_PROBE_REFUSAL_OK");return 0;
}
