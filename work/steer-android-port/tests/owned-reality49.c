/* Synthetic loopback-only Reality wire test. Never consumes saved profiles. */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <poll.h>
#include "client.h"
#include "vless_proto.h"
#include "node_bind49.h"
#ifdef MCRF_UTLS49
#include "tls49.h"
static uint64_t fixture_epoch49;
static void fixture_tls_close49(void){if(fixture_epoch49)mcrf49_stop(fixture_epoch49);}
#endif

int main(int argc,char **argv){
 if(argc!=5)return 2;
 int port=atoi(argv[1]),target=atoi(argv[3]);
 if(port<1024||port>65535||target<1024||target>65535||strlen(argv[2])!=43)return 2;
 int custom_fp=!strncmp(argv[4],"fp:",3)||!strncmp(argv[4],"fpbad:",6);
 if(!strcmp(argv[4],"short")){
#ifdef MCRF_UTLS49
  return 2; /* no hidden fingerprint downgrade in the production layer */
#else
  setenv("STEER_NOPQ","1",1);
#endif
 }
 else if(strcmp(argv[4],"pq")&&strcmp(argv[4],"badkey")&&strcmp(argv[4],"empty")&&strcmp(argv[4],"emptybadkey")&&
         strcmp(argv[4],"edge")&&strcmp(argv[4],"edgebadkey")&&strcmp(argv[4],"ios")&&strcmp(argv[4],"iosbadkey")&&
         strcmp(argv[4],"qq")&&strcmp(argv[4],"qqbadkey")&&strcmp(argv[4],"safari")&&strcmp(argv[4],"safaribadkey")&&
         strcmp(argv[4],"firefox")&&strcmp(argv[4],"firefoxbadkey")&&!custom_fp)return 2;
 struct vless_node node={0};
 strcpy(node.host,"127.0.0.1");node.port=port;
 strcpy(node.uuid,"4b72af9e-32b4-4b14-a2fc-4a9100d2eb21");
 strcpy(node.type,"tcp");strcpy(node.security,"reality");strcpy(node.sni,"fixture.example.test");
 strcpy(node.pbk,argv[2]);strcpy(node.sid,"abcd");strcpy(node.fp,"chrome");
 const char *pq_verify49=getenv("MCRF_P049_PQV");
 if(pq_verify49){if(strlen(pq_verify49)>4096)return 2;node.pqv=pq_verify49;}
 int wrong=strstr(argv[4],"badkey")!=NULL||!strncmp(argv[4],"fpbad:",6);
 if(custom_fp){const char *fp=argv[4]+(wrong?6:3);if(!fp[0]||strlen(fp)>=sizeof node.fp)return 2;strcpy(node.fp,fp);}
 if(!strncmp(argv[4],"edge",4))strcpy(node.fp,"edge");
 if(!strncmp(argv[4],"ios",3))strcpy(node.fp,"ios");
 if(!strncmp(argv[4],"qq",2))strcpy(node.fp,"qq");
 if(!strncmp(argv[4],"safari",6))strcpy(node.fp,"safari");
 if(!strncmp(argv[4],"firefox",7))strcpy(node.fp,"firefox");
 if(wrong)node.pbk[0]=node.pbk[0]=='A'?'B':'A';
 if(!strncmp(argv[4],"empty",5)){strcpy(node.host,"original.fixture.test");node.sni[0]=0;steer_bind_node49(&node,"127.0.0.1");}
#ifdef MCRF_UTLS49
 fixture_epoch49=mcrf49_begin();if(!fixture_epoch49)return 2;
 atexit(fixture_tls_close49);node.runtime_epoch49=fixture_epoch49;
#endif
 struct transport conn={0};int rc=vless_connect(&node,&conn,3);
 if(rc){printf("WIRE49 mode=%s handshake=%d\n",argv[4],rc);return wrong?0:1;}
 if(wrong){transport_close(&conn);printf("WIRE49 mode=%s handshake=0\n",argv[4]);return 1;}
 unsigned char uuid[16],req[1024],ip[4]={127,0,0,1};vless_uuid_parse(node.uuid,uuid);
 size_t n=vless_build_request(uuid,VLESS_CMD_TCP,NULL,ip,target,"",req,sizeof(req));
 const char http[]="GET / HTTP/1.1\r\nHost: owned.fixture.test\r\nConnection: close\r\n\r\n";
 memcpy(req+n,http,sizeof(http)-1);n+=sizeof(http)-1;rc=transport_write(&conn,req,n);
 if(rc){transport_close(&conn);printf("WIRE49 mode=%s write=%d\n",argv[4],rc);return 1;}
 struct vless_response_stream response={0};int header=0;char body[4096];size_t used=0;
 for(int round=0;round<20&&used<sizeof(body)-1;round++){
  struct pollfd wait={transport_fd(&conn),POLLIN,0};if(!transport_has_data(&conn)&&poll(&wait,1,3000)<=0)break;
  unsigned char buf[4096];size_t got=0;rc=transport_read(&conn,buf,sizeof(buf),&got);if(rc)break;if(!got)continue;
  size_t skip=0;if(!header){int hr=vless_response_feed(&response,buf,got,&skip);if(hr==VLESS_EPROTO)break;if(hr==VLESS_EAGAIN)continue;header=1;}
  size_t count=got-skip;if(count>sizeof(body)-1-used)break;memcpy(body+used,buf+skip,count);used+=count;
  body[used]=0;if(strstr(body,"HTTP/1.1 204"))break;
 }
 transport_close(&conn);body[used]=0;int success=header&&strstr(body,"HTTP/1.1 204");
 printf("WIRE49 mode=%s handshake=0 vless=%d http204=%d\n",argv[4],header,success);return success?0:1;
}
