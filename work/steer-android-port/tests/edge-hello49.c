/* Packet-structure parity with pinned uTLS Edge_Auto=Edge85; synthetic only. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "reality.h"
static unsigned u16(const unsigned char *p){return (unsigned)p[0]*256+p[1];}
static unsigned norm(unsigned x){return (x&0x0f0f)==0x0a0a&&(x>>8)==(x&255)?0x0a0a:x;}
int main(void){
 struct reality_cfg cfg={.sni="fixture.example.test",.pbk="CQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",.sid="abcd",.fp="edge",.pq=1};
 struct reality_state state;unsigned char hello[2560];size_t len=0;
 assert(!reality_build_hello(&cfg,&state,hello,sizeof(hello),&len));assert(!state.pq&&len==517&&u16(hello+3)==len-5);
 unsigned suites[]={0x0a0a,0x1301,0x1302,0x1303,0xc02b,0xc02f,0xc02c,0xc030,0xcca9,0xcca8,0xc013,0xc014,0x009c,0x009d,0x002f,0x0035};
 size_t at=9+2+32;at+=1+hello[at];unsigned sn=u16(hello+at);at+=2;assert(sn==sizeof(suites)/sizeof(suites[0])*2);
 for(unsigned i=0;i<sn/2;i++)assert(norm(u16(hello+at+2*i))==suites[i]);at+=sn;assert(hello[at]==1&&hello[at+1]==0);at+=2;
 unsigned en=u16(hello+at);at+=2;assert(at+en==len);
 unsigned expected[]={0x0a0a,0,0x17,0xff01,0xa,0xb,0x23,0x10,5,0xd,0x12,0x33,0x2d,0x2b,0x1b,0x0a0a,0x15};size_t count=0;
 while(at<len){assert(at+4<=len);unsigned type=u16(hello+at),n=u16(hello+at+2);at+=4;assert(at+n<=len&&count<sizeof(expected)/sizeof(expected[0]));assert(norm(type)==expected[count++]);
  const unsigned char *p=hello+at;
  if(type==0xa){assert(n==10&&u16(p)==8&&norm(u16(p+2))==0x0a0a&&u16(p+4)==0x1d&&u16(p+6)==0x17&&u16(p+8)==0x18);}
  if(type==0x33){assert(n==43&&u16(p)==41&&norm(u16(p+2))==0x0a0a&&u16(p+4)==1&&p[6]==0&&u16(p+7)==0x1d&&u16(p+9)==32);}
  if(type==0x2b){assert(n==11&&p[0]==10&&norm(u16(p+1))==0x0a0a&&u16(p+3)==0x304&&u16(p+5)==0x303&&u16(p+7)==0x302&&u16(p+9)==0x301);}
  if(type==0x10){const unsigned char want[]={0,12,2,'h','2',8,'h','t','t','p','/','1','.','1'};assert(n==sizeof(want)&&!memcmp(p,want,n));}
  if(type==0x1b)assert(n==3&&p[0]==2&&u16(p+1)==2);
  at+=n;
 }
 assert(count==sizeof(expected)/sizeof(expected[0]));
 cfg.fp="chrome";assert(!reality_build_hello(&cfg,&state,hello,sizeof(hello),&len));assert(state.pq&&len>1500);
 puts("EDGE49 edge85Exact=1 chromePqPreserved=1 edgeRecordBytes=517");return 0;
}
