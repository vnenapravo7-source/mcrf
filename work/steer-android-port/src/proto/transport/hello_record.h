#ifndef STEER_HELLO_RECORD_H
#define STEER_HELLO_RECORD_H
#include <errno.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>

static inline int tr_hello_send_all(int fd,const unsigned char *data,size_t len){
 size_t off=0;
 while(off<len){ssize_t n=send(fd,data+off,len-off,MSG_NOSIGNAL);
  if(n<0&&errno==EINTR)continue;
  if(n<=0)return -1;
  off+=(size_t)n;
 }
 return 0;
}
/* Split RECORDS, not the ClientHello message. Reality authenticates the same
 * complete handshake bytes and TLS hashes that same transcript. No key-share,
 * authentication, SNI, ALPN or cipher is removed. TCP_NODELAY is set by dialer.
 * The ordinary TLS/HTTP-upgrade path stays byte-exact. */
static inline int tr_hello_send(int fd,const unsigned char *hello,size_t len,int split){
 if(!split||len<=1029)return tr_hello_send_all(fd,hello,len);
 if(len<9||hello[0]!=0x16||hello[1]!=3||hello[5]!=1||
    (((size_t)hello[3]<<8)|hello[4])!=len-5)return -1;
 size_t off=5;
 while(off<len){
  unsigned char record[1029];size_t count=len-off;if(count>1024)count=1024;
  memcpy(record,hello,5);record[3]=(unsigned char)(count>>8);record[4]=(unsigned char)count;
  memcpy(record+5,hello+off,count);
  if(tr_hello_send_all(fd,record,count+5))return -1;
  off+=count;
 }
 return 0;
}
#endif
