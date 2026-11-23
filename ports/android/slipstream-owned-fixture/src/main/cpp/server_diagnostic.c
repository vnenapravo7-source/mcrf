#define _GNU_SOURCE
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
/* Test peer only. exec preserves PID and the fixture's Pdeathsig ownership. */
int main(int argc,char **argv) {
    char path[4096],log[128];
    ssize_t n=readlink("/proc/self/exe",path,sizeof(path)-1);
    if(n<=0||n>=(ssize_t)sizeof(path)-1)return 120;
    path[n]=0;char *slash=strrchr(path,'/');if(!slash)return 121;
    int original_c=strstr(slash,"diagnostic_cserver")!=NULL;
    *slash=0;
    if(strlen(path)+sizeof("/libslipstream_testserver.so")>=sizeof(path))return 122;
    strcat(path,original_c?"/libslipstream_cserver.so":"/libslipstream_testserver.so");
    snprintf(log,sizeof(log),"%sserver-diagnostic-%ld.log",original_c?"c":"",(long)getpid());
    int fd=open(log,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);if(fd<0)return 123;
    if(dup2(fd,STDOUT_FILENO)<0||dup2(fd,STDERR_FILENO)<0)return 124;
    close(fd);setenv("RUST_LOG","debug",1);
    argv[0]=path;execv(path,argv);perror("diagnostic server exec");return 125;
}
