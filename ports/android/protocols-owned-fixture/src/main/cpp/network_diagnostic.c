#include <android/multinetwork.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>
int main(int argc,char **argv){
 if(argc!=2)return 2;
 net_handle_t h=strtoull(argv[1],0,10);
 errno=0;int r=android_setprocnetwork(h);int e=errno;
 printf("uid=%d process_rc=%d process_errno=%d\n",getuid(),r,e);
 int fd=socket(AF_INET,SOCK_STREAM,0);
 errno=0;r=android_setsocknetwork(h,fd);e=errno;
 printf("socket_rc=%d socket_errno=%d\n",r,e);
 if(fd>=0)close(fd);
 return 0;
}
