/* Private pinned uTLS layer. TCP dial/protection, VLESS/VMess/Trojan, routing,
 * UDP, HTTP transports and TUN remain the native Steer implementation. */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include "transport.h"
#include "roots.h"
#include "tls49.h"
#include "cert_compress49.h"

/* Both libraries belong to one signed module and one fresh worker. The Go
 * helper cannot replace this fixed decoder pointer or use ambient libraries. */
/* Never enter Go while Android's dynamic-loader constructor lock is held:
 * Go's initialization thread may itself need dlopen for Android TLS setup. */
static pthread_once_t decoder_once49=PTHREAD_ONCE_INIT;
static int decoder_status49=-1;
static void decoder_bridge49(void){
    decoder_status49=mcrf49_set_decoder(cert_decompress49);
}
static int decoder_ready49(void){
    return pthread_once(&decoder_once49,decoder_bridge49)||decoder_status49?-105:0;
}

struct json49 {char *p;size_t n,cap;int bad;};
static void raw49(struct json49 *j,const char *s){
    size_t n=strlen(s);if(j->bad||n>=j->cap-j->n){j->bad=1;return;}
    memcpy(j->p+j->n,s,n);j->n+=n;j->p[j->n]=0;
}
static void string49(struct json49 *j,const char *s){
    static const char hex[]="0123456789abcdef";raw49(j,"\"");
    for(const unsigned char *p=(const unsigned char *)(s?s:"");*p;p++){
        char b[7]={0};
        if(*p=='"'||*p=='\\'){b[0]='\\';b[1]=(char)*p;}
        else if(*p<32){memcpy(b,"\\u00",4);b[4]=hex[*p>>4];b[5]=hex[*p&15];}
        else b[0]=(char)*p;
        raw49(j,b);
    }
    raw49(j,"\"");
}
static void field49(struct json49 *j,const char *key,const char *value){
    raw49(j,",");string49(j,key);raw49(j,":");string49(j,value);
}
static void array49(struct json49 *j,const char *key,const char *csv){
    raw49(j,",");string49(j,key);raw49(j,":[");
    if(csv&&csv[0]){
        char *copy=strdup(csv);if(!copy){j->bad=1;return;}
        char *p=copy;int first=1;
        do {char *comma=strchr(p,',');if(comma)*comma=0;
            if(!first)raw49(j,",");first=0;string49(j,p);
            if(!comma)break;p=comma+1;
        }while(1);free(copy);
    }
    raw49(j,"]");
}
static int options49(const struct tr_node *n,const char *required,int timeout,char *out,size_t cap,size_t *length){
    struct json49 j={.p=out,.cap=cap};out[0]=0;
    raw49(&j,"{\"security\":");string49(&j,n->security);
    field49(&j,"host",n->host);field49(&j,"sni",n->sni);field49(&j,"fingerprint",n->fp);
    field49(&j,"requiredALPN",required);
    if(n->profile_alpn)array49(&j,"alpn",n->profile_alpn);
    if(!strcmp(n->security,"reality")){
        field49(&j,"publicKey",n->pbk);field49(&j,"shortID",n->sid);field49(&j,"pqVerify",n->pqv);
    }else{
        array49(&j,"certPins",n->pcs);array49(&j,"publicKeyPins",n->pks);array49(&j,"verifyNames",n->vcn);
        raw49(&j,n->insecure?",\"insecure\":true":",\"insecure\":false");field49(&j,"ech",n->ech);
    }
    char number[48];snprintf(number,sizeof number,",\"timeoutMS\":%d}",timeout);raw49(&j,number);
    *length=j.n;return j.bad?-100:0;
}
int tls49_validate_node(const struct tr_node *n){
    int initialized=decoder_ready49();if(initialized)return initialized;
    char *json=malloc(32768);if(!json)return -106;size_t length;
    const char *required=!strcmp(n->type,"grpc")||!strcmp(n->type,"xhttp")?"h2":
        !strcmp(n->type,"ws")||!strcmp(n->type,"httpupgrade")?"http/1.1":"";
    int rc=options49(n,required,8000,json,32768,&length);if(!rc)rc=mcrf49_validate(json,length);
    free(json);return rc;
}
int tls49_open(struct tr_link *l,const struct tr_node *n,const char *required,int timeout_s){
    int initialized=decoder_ready49();if(initialized)return initialized;
    if(!n->runtime_epoch49||timeout_s<1||timeout_s>30)return -105;
    char *json=malloc(32768),*roots=NULL;size_t length=0,roots_n=0;int rc=-106;
    if(!json)return rc;
    rc=options49(n,required,timeout_s*1000,json,32768,&length);if(rc)goto done;
    if(!strcmp(n->security,"tls")&&!n->insecure){
        const char *path=tls_cert_roots();FILE *file=path?fopen(path,"rb"):NULL;
        if(file){
            roots=malloc(1024*1024+1);if(!roots){fclose(file);rc=-106;goto done;}
            roots_n=fread(roots,1,1024*1024+1,file);int bad=ferror(file);fclose(file);
            if(bad||roots_n>1024*1024){rc=-104;goto done;}
        }
    }
    rc=mcrf49_open(l->fd,n->runtime_epoch49,json,length,roots,roots_n,&l->compat49);
    if(!rc){
        rc=mcrf49_alpn(l->compat49,l->tls.alpn,sizeof l->tls.alpn);
        if(rc){mcrf49_close(l->compat49);l->compat49=0;}else l->tls.ready=1;
    }
done:
    free(json);free(roots);return rc;
}
int tls49_read(struct tr_link *l,unsigned char *data,size_t cap,size_t *got){
    int rc=mcrf49_read(l->compat49,(char *)data,cap,got,l->rx_direct);
    if(rc==1&&l->rx_direct){
        ssize_t n=read(l->fd,data,cap);
        if(n>0){*got=(size_t)n;return 0;}
        if(n==0)return TR_ECLOSED;
        if(errno==EINTR||errno==EAGAIN||errno==EWOULDBLOCK){*got=0;return 0;}
        return TR_EIO;
    }
    if(rc==-108){*got=0;return 0;} // partial TLS record; no busy-loop readiness
    return rc==-107?TR_ECLOSED:rc;
}
int tls49_write(struct tr_link *l,const unsigned char *data,size_t length){
    return mcrf49_write(l->compat49,(char *)data,length);
}
