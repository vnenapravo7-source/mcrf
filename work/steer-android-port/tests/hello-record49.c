/* Exact transcript/record bounds and invalid input, no network/provider data. */
#define _GNU_SOURCE
#include <assert.h>
#include <unistd.h>
#include "hello_record.h"
int main(void){
 unsigned char input[1800];for(size_t i=0;i<sizeof(input);i++)input[i]=(unsigned char)(i*37);
 input[0]=0x16;input[1]=3;input[2]=1;input[3]=(sizeof(input)-5)>>8;input[4]=(sizeof(input)-5)&255;input[5]=1;
 for(int split=0;split<2;split++){
  int f[2];assert(!socketpair(AF_UNIX,SOCK_STREAM,0,f));assert(!tr_hello_send(f[0],input,sizeof(input),split));shutdown(f[0],SHUT_WR);
  unsigned char wire[1900];size_t count=0;ssize_t n;while((n=read(f[1],wire+count,sizeof(wire)-count))>0)count+=(size_t)n;
  if(!split){assert(count==sizeof(input));assert(!memcmp(wire,input,count));}
  else{size_t off=0,done=5;while(off<count){assert(off+5<=count&&wire[off]==0x16&&wire[off+1]==3);size_t len=((size_t)wire[off+3]<<8)|wire[off+4];assert(len>0&&len<=1024&&off+5+len<=count);assert(!memcmp(wire+off+5,input+done,len));done+=len;off+=len+5;}assert(done==sizeof(input));}
  close(f[0]);close(f[1]);
 }
 int f[2];assert(!socketpair(AF_UNIX,SOCK_STREAM,0,f));input[4]^=1;assert(tr_hello_send(f[0],input,sizeof(input),1)==-1);close(f[0]);close(f[1]);
 return 0;
}
