/* Synthetic ClientHello structure only. Never accepts an imported server/key. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include "reality.h"
static unsigned u16(const unsigned char *p){return (unsigned)p[0]*256+p[1];}
static unsigned norm(unsigned x){return (x&0x0f0f)==0x0a0a&&(x>>8)==(x&255)?0x0a0a:x;}
static void numbers(const unsigned char *p,size_t n){
 putchar('[');for(size_t i=0;i+1<n;i+=2)printf("%s%u",i?",":"",norm(u16(p+i)));putchar(']');
}
static void alpn(const unsigned char *p,size_t n){
 size_t at=2;int comma=0;putchar('[');
 while(at<n){size_t len=p[at++];if(at+len>n)break;printf("%s\"",comma++?",":"");
  for(size_t i=0;i<len;i++){unsigned c=p[at+i];if(c=='"'||c=='\\')putchar('\\');if(c<32||c>126)printf("\\u%04x",c);else putchar(c);}
  putchar('"');at+=len;
 }putchar(']');
}
int main(int argc,char **argv){
 if(argc!=3)return 2;
 for(const char *p=argv[1];*p;p++)if(!isalnum((unsigned char)*p)&&*p!='_'&&*p!='.')return 2;
 if(strcmp(argv[2],"tls")&&strcmp(argv[2],"reality"))return 2;
 struct reality_cfg cfg={.sni="fixture.example.test",.pbk="CQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",.sid="abcd",.fp=argv[1],.pq=reality_fp_pq(argv[1]),.plain=!strcmp(argv[2],"tls")};
 struct reality_state st;unsigned char raw[4096];size_t n=0;int rc=reality_build_hello(&cfg,&st,raw,sizeof raw,&n);
 if(rc){printf("{\"name\":\"%s\",\"error\":%d,\"remoteAccepted\":false}\n",argv[1],rc);return 1;}
 size_t at=43;if(n<=at)return 3;at+=1+raw[at];if(at+2>n)return 3;
 size_t suites=u16(raw+at);at+=2;if(at+suites+1>n)return 3;
 printf("{\"name\":\"%s\",\"remoteAccepted\":false,\"cipherSuites\":",argv[1]);numbers(raw+at,suites);at+=suites;
 at+=1+raw[at];if(at+2>n)return 3;size_t exts=u16(raw+at);at+=2;if(at+exts!=n)return 3;
 int comma=0;printf(",\"extensions\":[");
 const unsigned char *groups=NULL,*versions=NULL,*shares=NULL,*protocols=NULL,*sigs=NULL;size_t gn=0,vn=0,kn=0,an=0,sn=0;
 while(at<n){if(at+4>n)return 3;unsigned type=u16(raw+at);size_t len=u16(raw+at+2);at+=4;if(at+len>n)return 3;
  printf("%s%u",comma++?",":"",norm(type));
  if(type==10){groups=raw+at;gn=len;}if(type==43){versions=raw+at;vn=len;}
  if(type==51){shares=raw+at;kn=len;}if(type==16){protocols=raw+at;an=len;}
  if(type==13){sigs=raw+at;sn=len;}at+=len;
 }
 printf("],\"groups\":");if(groups&&gn>=2)numbers(groups+2,gn-2);else printf("[]");
 printf(",\"versions\":");if(versions&&vn>=1)numbers(versions+1,vn-1);else printf("[]");
 printf(",\"shareGroups\":[");comma=0;
 for(size_t p=2;shares&&p+4<=kn;){unsigned group=u16(shares+p);size_t len=u16(shares+p+2);p+=4;if(p+len>kn)return 3;printf("%s%u",comma++?",":"",norm(group));p+=len;}
 printf("],\"signatureSchemes\":");if(sigs&&sn>=2)numbers(sigs+2,sn-2);else printf("[]");
 printf(",\"alpn\":");if(protocols)alpn(protocols,an);else printf("[]");printf("}\n");return 0;
}
