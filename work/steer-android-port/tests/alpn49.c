#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "alpn49.h"
int main(void){
 unsigned char out[258];size_t n;
 assert(!mcrf_alpn49(NULL,NULL,out,sizeof out,&n)&&n==14&&out[2]==2&&!memcmp(out+3,"h2",2));
 assert(!mcrf_alpn49(NULL,"http/1.1",out,sizeof out,&n)&&n==11&&out[2]==8);
 assert(!mcrf_alpn49("http/1.1,h2","h2",out,sizeof out,&n)&&n==14&&out[2]==8&&out[11]==2);
 assert(!mcrf_alpn49("",NULL,out,sizeof out,&n)&&n==0);
 assert(mcrf_alpn49("","h2",out,sizeof out,&n));
 assert(mcrf_alpn49("h2","http/1.1",out,sizeof out,&n));
 for(const char **p=(const char*[]){",h2","h2,","h2,,http/1.1","h2\r\n","h2, http/1.1",NULL};*p;p++)
  assert(mcrf_alpn49(*p,NULL,out,sizeof out,&n));
 assert(mcrf_alpn49("http/1.1",NULL,out,4,&n));
 puts("ALPN49 exact-order absent-empty transport-conflict bounds PASS");return 0;
}
