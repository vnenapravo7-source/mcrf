#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <arpa/inet.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <unistd.h>
#include <sys/socket.h>
#include <fcntl.h>
#include "vless.h"
#include "vldial.h"
#include "stack.h"
#include "transport.h"
#include "platform.h"
#include "proxy.h"
#include "pxdial.h"
#include "hy2.h"
#include "hy2dial.h"
#include "hy2conn.h"
#include "quic.h"
#include "sublink.h"
#include "hostdial.h"
#include "node_bind49.h"
#include "probe.h"
#include "route_relay.h"
#include "route_slots.h"
#ifdef MCRF_UTLS49
#include "tls49.h"
static _Atomic uint64_t active_tls49;
static atomic_int stopping_tls49;
static uint64_t begin_tls49(void){
    if(atomic_load(&stopping_tls49))return 0;
    uint64_t id=mcrf49_begin(),empty=0;if(!id)return 0;
    if(!atomic_compare_exchange_strong(&active_tls49,&empty,id)){mcrf49_stop(id);return 0;}
    if(atomic_load(&stopping_tls49)){
        uint64_t current=id;atomic_compare_exchange_strong(&active_tls49,&current,0);mcrf49_stop(id);return 0;
    }
    return id;
}
static void end_tls49(uint64_t id){
    if(!id)return;uint64_t current=id;
    atomic_compare_exchange_strong(&active_tls49,&current,0);mcrf49_stop(id);
}
#endif
JNIEXPORT void JNICALL Java_org_steer_core_SteerCore_cancelTLS(JNIEnv *env,jclass cls){
    (void)env;(void)cls;
#ifdef MCRF_UTLS49
    atomic_store(&stopping_tls49,1);
    uint64_t id=atomic_exchange(&active_tls49,0);if(id)mcrf49_stop(id);
#endif
}

JNIEXPORT jint JNICALL Java_org_steer_core_SteerCore_verifyTlsNative(JNIEnv *env,jclass cls,jstring hostname,jstring ipv4,jstring state) {
    (void)cls;if(!hostname||!ipv4||!state)return -1;
    const char *host=(*env)->GetStringUTFChars(env,hostname,NULL);if(!host)return -1;
    const char *ip=(*env)->GetStringUTFChars(env,ipv4,NULL);
    if(!ip){(*env)->ReleaseStringUTFChars(env,hostname,host);return -1;}
    const char *dir=(*env)->GetStringUTFChars(env,state,NULL);
    if(!dir){(*env)->ReleaseStringUTFChars(env,hostname,host);(*env)->ReleaseStringUTFChars(env,ipv4,ip);return -1;}
    struct in_addr address;int rc=-1;
    if(inet_pton(AF_INET,ip,&address)==1) {
        struct vless_node vn={0};snprintf(vn.host,sizeof(vn.host),"%s",ip);vn.port=443;
        snprintf(vn.type,sizeof(vn.type),"tcp");snprintf(vn.security,sizeof(vn.security),"tls");
        snprintf(vn.sni,sizeof(vn.sni),"%s",host);snprintf(vn.fp,sizeof(vn.fp),"chrome");
#ifdef MCRF_UTLS49
        vn.runtime_epoch49=begin_tls49();
#endif
        struct tr_node node;sl_tr_node(&vn,&node);
        plat_select("android");steer_set_state_dir(dir);
        struct tr_link link;rc=tr_link_open(&link,&node,"http/1.1",8);
        tr_link_close(&link);steer_set_state_dir(NULL);
#ifdef MCRF_UTLS49
        end_tls49(vn.runtime_epoch49);
#endif
    }
    (*env)->ReleaseStringUTFChars(env,hostname,host);(*env)->ReleaseStringUTFChars(env,ipv4,ip);(*env)->ReleaseStringUTFChars(env,state,dir);
    return rc;
}

struct endpoint { struct vless_node vl; struct px_node px; struct hy2_node hy; int proxy; };
static int endpoint_parse(const char *url, struct endpoint *e) {
    memset(e,0,sizeof(*e));
    if(!strncmp(url,"vless://",8))return vless_parse_url(url,&e->vl);
    if(!strncmp(url,"hy2://",6)||!strncmp(url,"hysteria2://",12)) {
        e->proxy=2;int rc=hy2_parse_url(url,&e->hy);
        return rc?rc:(e->hy.insecure?1:0);
    }
    e->proxy=1;
    return px_parse_url(url,&e->px,0);
}
static struct vless_node *endpoint_node(struct endpoint *e) {return e->proxy?&e->px.vn:&e->vl;}
struct blocked {unsigned count;struct {uint32_t network,mask;} rules[128];};
static int blocked_flow(void *arg,const struct flow_key *key,int udp) {
    if(flow_is6(key))return -1; /* No dual-stack policy router admitted. */
    (void)udp;const struct blocked *b=arg;uint32_t address=ntohl(key->dst);
    for(unsigned i=0;i<b->count;i++)if((address & b->rules[i].mask)==b->rules[i].network)return -1;
    return 0;
}
static int load_blocked(JNIEnv *env,jobjectArray rows,struct blocked *b) {
    memset(b,0,sizeof(*b));if(!rows)return -1;
    jsize n=(*env)->GetArrayLength(env,rows);if(n>128)return -1;
    for(jsize i=0;i<n;i++) {
        jstring row=(*env)->GetObjectArrayElement(env,rows,i);if(!row)return -1;
        const char *text=(*env)->GetStringUTFChars(env,row,NULL);if(!text){(*env)->DeleteLocalRef(env,row);return -1;}
        char copy[64];int ok=strlen(text)<sizeof(copy);snprintf(copy,sizeof(copy),"%s",text);
        (*env)->ReleaseStringUTFChars(env,row,text);(*env)->DeleteLocalRef(env,row);
        char *slash=strchr(copy,'/'),*end=NULL;struct in_addr address;
        if(!ok||!slash)return -1;*slash++=0;
        long bits=strtol(slash,&end,10);
        if(!*slash||*end||bits<0||bits>32||inet_pton(AF_INET,copy,&address)!=1)return -1;
        uint32_t mask=bits?UINT32_MAX << (32-bits):0,network=ntohl(address.s_addr);
        if((network & mask)!=network)return -1;
        b->rules[b->count].network=network;b->rules[b->count++].mask=mask;
    }
    return 0;
}
JNIEXPORT jlongArray JNICALL Java_org_steer_core_SteerCore_counters(JNIEnv *env,jclass cls) {
    (void)cls;struct stack_counters counters;stack_get_counters(&counters);
    jlong values[3]={(jlong)counters.tx_payload,(jlong)counters.rx_payload,(jlong)counters.blocked_flows};
    jlongArray result=(*env)->NewLongArray(env,3);if(result)(*env)->SetLongArrayRegion(env,result,0,3,values);return result;
}

struct host { JavaVM *vm; jobject service, router; jmethodID protect, route, route_port,route_owned,route6,flow_result; };
static int route_owned_flow(void *arg,const struct flow_key *original,struct flow_key *target){
    struct host *h=arg;JNIEnv *env=NULL;int attached=0,action=3;
    if(!h->route_owned||(original->proto!=6&&original->proto!=17)||!original->sport||!original->dport)return 3;
    if((*h->vm)->GetEnv(h->vm,(void **)&env,JNI_VERSION_1_6)!=JNI_OK){if((*h->vm)->AttachCurrentThread(h->vm,&env,NULL)!=JNI_OK)return 3;attached=1;}
    unsigned size=flow_is6(original)?16:4;unsigned char wire[38];
    wire[0]=size==16?6:4;wire[1]=original->proto;wire[2]=original->sport>>8;wire[3]=original->sport;wire[4]=original->dport>>8;wire[5]=original->dport;
    memcpy(wire+6,size==16?original->src6:(const uint8_t *)&original->src,size);
    memcpy(wire+6+size,size==16?original->dst6:(const uint8_t *)&original->dst,size);
    jbyteArray input=(*env)->NewByteArray(env,6+2*size),output=NULL;
    if(input){
        (*env)->SetByteArrayRegion(env,input,0,6+2*size,(const jbyte *)wire);
        if(!(*env)->ExceptionCheck(env))output=(jbyteArray)(*env)->CallObjectMethod(env,h->router,h->route_owned,input);
        if(!(*env)->ExceptionCheck(env)&&output&&(*env)->GetArrayLength(env,output)==(jsize)(1+size)){
            jbyte decision[17];(*env)->GetByteArrayRegion(env,output,0,1+size,decision);
            int dns_rewrite=host_dns_control_rewrite(original);
            if(!(*env)->ExceptionCheck(env)&&(decision[0]==1||decision[0]==2||(dns_rewrite&&(decision[0]==4||decision[0]==5)))){
                action=decision[0];*target=*original;
                memcpy(size==16?target->dst6:(uint8_t *)&target->dst,decision+1,size);
            }
        }
    }
    if((*env)->ExceptionCheck(env))(*env)->ExceptionClear(env);
    if(output)(*env)->DeleteLocalRef(env,output);if(input)(*env)->DeleteLocalRef(env,input);
    if(attached)(*h->vm)->DetachCurrentThread(h->vm);return action;
}
static void proxy_flow_result(void *arg,const struct flow_key *key,int ready){
    struct host *h=arg;JNIEnv *env=NULL;int attached=0;
    if(!h->flow_result)return;
    if((*h->vm)->GetEnv(h->vm,(void **)&env,JNI_VERSION_1_6)!=JNI_OK){if((*h->vm)->AttachCurrentThread(h->vm,&env,NULL)!=JNI_OK)return;attached=1;}
    jsize count=flow_is6(key)?16:4;
    jbyteArray alias=(*env)->NewByteArray(env,count);
    if(alias){
        (*env)->SetByteArrayRegion(env,alias,0,count,(const jbyte *)(flow_is6(key)?key->dst6:(const uint8_t *)&key->dst));
        if(!(*env)->ExceptionCheck(env))(*env)->CallVoidMethod(env,h->router,h->flow_result,alias,(jint)key->dport,(jint)key->sport,ready?JNI_TRUE:JNI_FALSE);
        (*env)->DeleteLocalRef(env,alias);
    }
    if((*env)->ExceptionCheck(env))(*env)->ExceptionClear(env);
    if(attached)(*h->vm)->DetachCurrentThread(h->vm);
}
static int route_flow6(void *arg,const uint8_t ip[16],uint8_t target[16]) {
    struct host *h=arg;JNIEnv *env=NULL;int attached=0,action=3;
    if(!h->route6)return 3;
    if((*h->vm)->GetEnv(h->vm,(void **)&env,JNI_VERSION_1_6)!=JNI_OK){if((*h->vm)->AttachCurrentThread(h->vm,&env,NULL)!=JNI_OK)return 3;attached=1;}
    jbyteArray input=(*env)->NewByteArray(env,16);
    jbyteArray output=NULL;
    if(input){
        (*env)->SetByteArrayRegion(env,input,0,16,(const jbyte *)ip);
        if(!(*env)->ExceptionCheck(env))output=(jbyteArray)(*env)->CallObjectMethod(env,h->router,h->route6,input);
        if(!(*env)->ExceptionCheck(env)&&output&&(*env)->GetArrayLength(env,output)==17){
            jbyte bytes[17];(*env)->GetByteArrayRegion(env,output,0,17,bytes);
            if(!(*env)->ExceptionCheck(env)&&(bytes[0]==1||bytes[0]==2)){action=bytes[0];memcpy(target,bytes+1,16);}
        }
    }
    if((*env)->ExceptionCheck(env))(*env)->ExceptionClear(env);
    if(output)(*env)->DeleteLocalRef(env,output);
    if(input)(*env)->DeleteLocalRef(env,input);
    if(attached)(*h->vm)->DetachCurrentThread(h->vm);
    return action;
}
static uint64_t route_flow(void *arg,uint32_t ip) {
    struct host *h=arg;JNIEnv *env=NULL;int attached=0;
    if((*h->vm)->GetEnv(h->vm,(void **)&env,JNI_VERSION_1_6)!=JNI_OK){if((*h->vm)->AttachCurrentThread(h->vm,&env,NULL)!=JNI_OK)return 3ULL<<32;attached=1;}
    jlong value=(*env)->CallLongMethod(env,h->router,h->route,(jint)ip);
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);value=3ULL<<32;}
    if(attached)(*h->vm)->DetachCurrentThread(h->vm);return (uint64_t)value;
}
static int protect_socket(void *arg, int fd) {
    struct host *h=arg;
    JNIEnv *env=NULL;
    int attached=0;
    if ((*h->vm)->GetEnv(h->vm,(void **)&env,JNI_VERSION_1_6)!=JNI_OK) {
        if((*h->vm)->AttachCurrentThread(h->vm,&env,NULL)!=JNI_OK) return -1;
        attached=1;
    }
    jboolean ok=(*env)->CallBooleanMethod(env,h->service,h->protect,(jint)fd);
    if((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); ok=JNI_FALSE; }
    if(attached)(*h->vm)->DetachCurrentThread(h->vm);
    return ok?0:-1;
}
static uint64_t route_flow_port(void *arg,uint32_t ip,unsigned port,unsigned protocol){
    struct host *h=arg;JNIEnv *env=NULL;int attached=0;
    if(!h->route_port||port>65535||(protocol!=6&&protocol!=17))return 3ULL<<32;
    if((*h->vm)->GetEnv(h->vm,(void **)&env,JNI_VERSION_1_6)!=JNI_OK){if((*h->vm)->AttachCurrentThread(h->vm,&env,NULL)!=JNI_OK)return 3ULL<<32;attached=1;}
    jlong value=(*env)->CallLongMethod(env,h->router,h->route_port,(jint)ip,(jint)port,(jint)protocol);
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);value=3ULL<<32;}
    if(attached)(*h->vm)->DetachCurrentThread(h->vm);return (uint64_t)value;
}
JNIEXPORT jint JNICALL Java_org_steer_core_SteerCore_nativeProbeSchema(JNIEnv *env,jclass self){
    (void)env;(void)self;return 1; /* Mechanical API only, not acceptance. */
}
JNIEXPORT jlong JNICALL Java_org_steer_core_SteerCore_probeNative(JNIEnv *env,jclass self,
        jstring url,jstring dial,jstring target,jstring hostname,jstring path,jstring state,
        jint timeout,jobject protector){
    (void)self;
    if(!url||!dial||!target||!hostname||!path||!state||!protector||timeout<1||timeout>30000)return -1;
    jstring strings[6]={url,dial,target,hostname,path,state};
    const char *values[6]={0};jlong result=-1;
    for(unsigned i=0;i<6;i++)if(!(values[i]=(*env)->GetStringUTFChars(env,strings[i],NULL)))goto release;
    struct endpoint *endpoint=calloc(1,sizeof(*endpoint));
    struct host *owner=calloc(1,sizeof(*owner));
    char *directory=strdup(values[5]);
    if(!endpoint||!owner||!directory){free(endpoint);free(owner);free(directory);goto release;}
    struct in_addr pinned;
    struct flow_key key={.sport=40000,.dport=443,.proto=6};
    if(endpoint_parse(values[0],endpoint)||inet_pton(AF_INET,values[1],&pinned)!=1)goto fail;
    if(inet_pton(AF_INET,values[2],&key.dst)==1){key.family=4;key.src=htonl(0x0a590002u);}
    else if(inet_pton(AF_INET6,values[2],key.dst6)==1){key.family=6;key.src6[0]=0xfd;key.src6[15]=2;}
    else goto fail;
    if(endpoint->proxy==2){
        if(!endpoint->hy.sni[0])snprintf(endpoint->hy.sni,sizeof(endpoint->hy.sni),"%s",endpoint->hy.host);
        snprintf(endpoint->hy.host,sizeof(endpoint->hy.host),"%s",values[1]);
    }else{
        struct vless_node *node=endpoint_node(endpoint);
        steer_bind_node49(node,values[1]);
    }
    (*env)->GetJavaVM(env,&owner->vm);
    owner->service=(*env)->NewGlobalRef(env,protector);
    jclass cls=(*env)->GetObjectClass(env,protector);
    if(cls){owner->protect=(*env)->GetMethodID(env,cls,"protectSocket","(I)Z");(*env)->DeleteLocalRef(env,cls);}
    if(!owner->service||!owner->protect||(*env)->ExceptionCheck(env))goto fail;
#ifdef MCRF_UTLS49
    if(endpoint->proxy!=2){
        endpoint_node(endpoint)->runtime_epoch49=begin_tls49();
        if(!endpoint_node(endpoint)->runtime_epoch49)goto fail;
    }
#endif
    plat_select("android");steer_set_state_dir(directory);
    transport_set_socket_prepare(protect_socket,owner);qc_set_socket_prepare(protect_socket,owner);
    struct dialer native={.ops=endpoint->proxy==2?&hy2_dialer:
        (endpoint->proxy?px_dialer_for(endpoint->px.proto):&vless_dialer),
        .ctx=endpoint->proxy==2?(const void *)&endpoint->hy:
        (endpoint->proxy?(const void *)&endpoint->px:(const void *)&endpoint->vl),.silence_s=30};
    // Multiplexer callbacks/node paths may outlive this invocation, exactly as
    // in runNative. Their one-use fresh-worker owner must remain alive until
    // main observes actual worker death; never clear these hooks on a timeout.
    if(native.ops&&(endpoint->proxy!=2||hy2c_start(&endpoint->hy)==0))
        result=(jlong)steer_https_probe(&native,&key,values[3],values[4],timeout);
#ifdef MCRF_UTLS49
    if(endpoint->proxy!=2)end_tls49(endpoint_node(endpoint)->runtime_epoch49);
#endif
    goto release;
fail:
    if(owner->service)(*env)->DeleteGlobalRef(env,owner->service);
    free(endpoint);free(owner);free(directory);
release:
    for(unsigned i=0;i<6;i++)if(values[i])(*env)->ReleaseStringUTFChars(env,strings[i],values[i]);
    return result;
}
/* Standalone route backend: one pinned protocol context per fresh worker.
 * Never creates a TUN/listener and never owns Java's public proxy credentials.
 * Context/hooks/multiplexers deliberately survive until actual worker death. */
struct route_backend {struct endpoint endpoint;struct host owner;char *directory;_Atomic unsigned channel_mask;int started;};
static _Atomic(struct route_backend *) route_backend;
static atomic_bool route_claimed;
JNIEXPORT jint JNICALL Java_org_steer_core_SteerCore_nativeRouteSchema(JNIEnv *env,jclass self){(void)env;(void)self;return 1;}
JNIEXPORT jboolean JNICALL Java_org_steer_core_SteerCore_prepareRouteNative(JNIEnv *env,jclass self,jstring url,jstring dial,jstring state,jobject protector){
    (void)self;_Bool expected=0;
    if(!url||!dial||!state||!protector||!atomic_compare_exchange_strong(&route_claimed,&expected,1))return JNI_FALSE;
    jstring strings[3]={url,dial,state};const char *values[3]={0};jboolean result=JNI_FALSE;
    struct route_backend *backend=NULL;
    for(unsigned i=0;i<3;i++)if(!(values[i]=(*env)->GetStringUTFChars(env,strings[i],NULL)))goto release_route;
    backend=calloc(1,sizeof(*backend));if(!backend)goto release_route;
    struct in_addr pinned;
    if(endpoint_parse(values[0],&backend->endpoint)||inet_pton(AF_INET,values[1],&pinned)!=1||strlen(values[2])>4096)goto fail_route;
    backend->directory=strdup(values[2]);if(!backend->directory)goto fail_route;
    if(backend->endpoint.proxy==2){
        struct hy2_node *node=&backend->endpoint.hy;
        if(!node->sni[0])snprintf(node->sni,sizeof(node->sni),"%s",node->host);
        snprintf(node->host,sizeof(node->host),"%s",values[1]);
    }else{
        struct vless_node *node=endpoint_node(&backend->endpoint);
        steer_bind_node49(node,values[1]);
    }
    if((*env)->GetJavaVM(env,&backend->owner.vm)!=JNI_OK)goto fail_route;
    backend->owner.service=(*env)->NewGlobalRef(env,protector);
    jclass type=(*env)->GetObjectClass(env,protector);
    if(type){backend->owner.protect=(*env)->GetMethodID(env,type,"protectSocket","(I)Z");(*env)->DeleteLocalRef(env,type);}
    if(!backend->owner.service||!backend->owner.protect||(*env)->ExceptionCheck(env))goto fail_route;
#ifdef MCRF_UTLS49
    if(backend->endpoint.proxy!=2){
        endpoint_node(&backend->endpoint)->runtime_epoch49=begin_tls49();
        if(!endpoint_node(&backend->endpoint)->runtime_epoch49)goto fail_route;
    }
#endif
    atomic_init(&backend->channel_mask,0);
    plat_select("android");steer_set_state_dir(backend->directory);
    transport_set_socket_prepare(protect_socket,&backend->owner);qc_set_socket_prepare(protect_socket,&backend->owner);
    backend->started=backend->endpoint.proxy!=2||hy2c_start(&backend->endpoint.hy)==0;
    atomic_store(&route_backend,backend);result=backend->started?JNI_TRUE:JNI_FALSE;goto release_route;
fail_route:
#ifdef MCRF_UTLS49
    if(backend->endpoint.proxy!=2)end_tls49(endpoint_node(&backend->endpoint)->runtime_epoch49);
#endif
    if(backend->owner.service)(*env)->DeleteGlobalRef(env,backend->owner.service);
    free(backend->directory);free(backend);
release_route:
    for(unsigned i=0;i<3;i++)if(values[i])(*env)->ReleaseStringUTFChars(env,strings[i],values[i]);
    return result;
}
struct relay_notice {JNIEnv *env;jobject owner;jmethodID ready,cancelled;};
static void relay_ready(void *argument,int ready){
    struct relay_notice *n=argument;(*n->env)->CallVoidMethod(n->env,n->owner,n->ready,ready?JNI_TRUE:JNI_FALSE);
    if((*n->env)->ExceptionCheck(n->env))(*n->env)->ExceptionClear(n->env);
}
static int relay_cancelled(void *argument){
    struct relay_notice *n=argument;int value=(*n->env)->CallBooleanMethod(n->env,n->owner,n->cancelled)==JNI_TRUE;
    if((*n->env)->ExceptionCheck(n->env)){(*n->env)->ExceptionClear(n->env);return 1;}return value;
}
JNIEXPORT jint JNICALL Java_org_steer_core_SteerCore_relayRouteNative(JNIEnv *env,jclass self,jstring address,jint port,jboolean udp,jint borrowed_fd,jint timeout,jobject notice){
    /* Java retains this exact borrowed descriptor until the blocking call
     * returns; native owns ONLY its duplicate, including every error path. */
    (void)self;int result=-1,owned_fd=borrowed_fd>=0?fcntl(borrowed_fd,F_DUPFD_CLOEXEC,0):-1;const char *literal=NULL;
    struct route_backend *backend=atomic_load(&route_backend);int slot=-1;
    struct relay_notice completion={.env=env,.owner=notice};
    if(!notice||owned_fd<0)goto release_relay;
    jclass type=(*env)->GetObjectClass(env,notice);
    if(type){completion.ready=(*env)->GetMethodID(env,type,"completed","(Z)V");completion.cancelled=(*env)->GetMethodID(env,type,"cancelled","()Z");(*env)->DeleteLocalRef(env,type);}
    if(!completion.ready||!completion.cancelled||(*env)->ExceptionCheck(env))goto release_relay;
    if(!backend||!backend->started||!address||port<1||port>65535||timeout<1||timeout>30000)goto refuse_relay;
    slot=mcrf_route_slot_acquire(&backend->channel_mask);if(slot<0)goto refuse_relay;
    literal=(*env)->GetStringUTFChars(env,address,NULL);if(!literal)goto refuse_relay;
    struct flow_key key={.sport=(uint16_t)(40000+slot),.dport=(uint16_t)port,.proto=udp?17:6};
    if(inet_pton(AF_INET,literal,&key.dst)==1){key.family=4;key.src=htonl(0x0a590002u);}
    else if(inet_pton(AF_INET6,literal,key.dst6)==1){key.family=6;key.src6[0]=0xfd;key.src6[15]=2;}
    else goto refuse_relay;
    const struct endpoint *endpoint=&backend->endpoint;
    struct dialer dialer={.ops=endpoint->proxy==2?&hy2_dialer:(endpoint->proxy?px_dialer_for(endpoint->px.proto):&vless_dialer),
        .ctx=endpoint->proxy==2?(const void *)&endpoint->hy:(endpoint->proxy?(const void *)&endpoint->px:(const void *)&endpoint->vl),.silence_s=120};
    result=steer_route_relay(&dialer,&key,udp?1:0,owned_fd,timeout,120000,relay_ready,relay_cancelled,&completion);
    owned_fd=-1;goto release_relay;
refuse_relay:
    if(!(*env)->ExceptionCheck(env))relay_ready(&completion,0);
release_relay:
    if(literal)(*env)->ReleaseStringUTFChars(env,address,literal);
    if(owned_fd>=0){shutdown(owned_fd,SHUT_RDWR);close(owned_fd);}
    if(slot>=0)mcrf_route_slot_release(&backend->channel_mask,slot);
    return result;
}
JNIEXPORT jstring JNICALL Java_org_steer_core_SteerCore_serverHost(JNIEnv *env,jclass self,jstring url) {
    (void)self;
    if(!url) return NULL;
    const char *text=(*env)->GetStringUTFChars(env,url,NULL);
    if(!text) return NULL;
    struct endpoint endpoint;
    int rc=endpoint_parse(text,&endpoint);
    (*env)->ReleaseStringUTFChars(env,url,text);
    return rc?NULL:(*env)->NewStringUTF(env,endpoint.proxy==2?endpoint.hy.host:endpoint_node(&endpoint)->host);
}
JNIEXPORT jint JNICALL Java_org_steer_core_SteerCore_nativeRouterSchema(JNIEnv *env,jclass self){
    (void)env;(void)self;return 2;
}
JNIEXPORT jint JNICALL Java_org_steer_core_SteerCore_runNative(JNIEnv *env,jclass self,jstring url,jstring ip,jstring state,jint fd,jobject protector,jobjectArray blockedRows,jobject router,jint dnsPort,jstring upstreamDns) {
    (void)self;
    if(!url||!ip||!state||fd<0) return 2;
    struct blocked blocked;if(load_blocked(env,blockedRows,&blocked)!=0)return 2;
    struct in_addr dnsAddress={0};
    if(router){if(!upstreamDns||dnsPort<1||dnsPort>65535)return 2;const char *dns=(*env)->GetStringUTFChars(env,upstreamDns,NULL);if(!dns)return 2;int valid=inet_pton(AF_INET,dns,&dnsAddress);(*env)->ReleaseStringUTFChars(env,upstreamDns,dns);if(valid!=1)return 2;}
    struct endpoint endpoint;
    const char *text=(*env)->GetStringUTFChars(env,url,NULL);
    if(!text) return 2;
    int rc=endpoint_parse(text,&endpoint);
    (*env)->ReleaseStringUTFChars(env,url,text);
    if(rc) return 2;
    struct vless_node *node=endpoint_node(&endpoint);
    const char *address=(*env)->GetStringUTFChars(env,ip,NULL);
    if(!address) return 2;
    struct in_addr parsed;
    rc=inet_pton(AF_INET,address,&parsed);
    if(rc==1) {
        // Preserve certificate/HTTP hostname before replacing dial address.
        if(endpoint.proxy==2) {
            if(!endpoint.hy.sni[0])snprintf(endpoint.hy.sni,sizeof(endpoint.hy.sni),"%s",endpoint.hy.host);
            snprintf(endpoint.hy.host,sizeof(endpoint.hy.host),"%s",address);
        }else{
            steer_bind_node49(node,address);
        }
    }
    (*env)->ReleaseStringUTFChars(env,ip,address);
    if(rc!=1) return 2;
    const char *dir=(*env)->GetStringUTFChars(env,state,NULL);
    if(!dir) return 2;
    plat_select("android"); steer_set_state_dir(dir);
    struct host h={0};
    (*env)->GetJavaVM(env,&h.vm);
    h.service=(*env)->NewGlobalRef(env,protector);
    jclass cls=(*env)->GetObjectClass(env,protector);
    h.protect=(*env)->GetMethodID(env,cls,"protectSocket","(I)Z");
    (*env)->DeleteLocalRef(env,cls);
    if(router){h.router=(*env)->NewGlobalRef(env,router);jclass routerClass=(*env)->GetObjectClass(env,router);h.route=(*env)->GetMethodID(env,routerClass,"routeFlow","(I)J");(*env)->DeleteLocalRef(env,routerClass);}
    if(router && !(*env)->ExceptionCheck(env)){
        jclass dual=(*env)->FindClass(env,"org/steer/core/SteerCore$DualStackFlowRouter");
        if(dual){
            if((*env)->IsInstanceOf(env,router,dual)){
                jmethodID enabled=(*env)->GetMethodID(env,dual,"ipv6FlowsEnabled","()Z");
                if(enabled&&!(*env)->ExceptionCheck(env)&&(*env)->CallBooleanMethod(env,router,enabled)&&!(*env)->ExceptionCheck(env))
                    h.route6=(*env)->GetMethodID(env,dual,"routeFlow6","([B)[B");
            }
            (*env)->DeleteLocalRef(env,dual);
        }
        /* Missing interface in an old host is not IPv6 admission. */
        if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);h.route6=NULL;}
    }
    if(!h.service||!h.protect||(router&&(!h.router||!h.route))) { (*env)->ReleaseStringUTFChars(env,state,dir); return 2; }
    if(router){
        jclass observer=(*env)->FindClass(env,"org/steer/core/SteerCore$ProxyFlowObserver");
        if(observer&&(*env)->IsInstanceOf(env,router,observer)){
            jmethodID enabled=(*env)->GetMethodID(env,observer,"observesProxyFlows","()Z");
            if(enabled&&!(*env)->ExceptionCheck(env)&&(*env)->CallBooleanMethod(env,router,enabled)&&!(*env)->ExceptionCheck(env))
                h.flow_result=(*env)->GetMethodID(env,observer,"proxyFlowResult","([BIIZ)V");
        }
        if(observer)(*env)->DeleteLocalRef(env,observer);
        if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);h.flow_result=NULL;}
    }
    if(router){
        jclass detailed=(*env)->FindClass(env,"org/steer/core/SteerCore$PortFlowRouter");
        if(detailed&&(*env)->IsInstanceOf(env,router,detailed)){
            jmethodID enabled=(*env)->GetMethodID(env,detailed,"portFlowsEnabled","()Z");
            if(enabled&&!(*env)->ExceptionCheck(env)&&(*env)->CallBooleanMethod(env,router,enabled)&&!(*env)->ExceptionCheck(env))
                h.route_port=(*env)->GetMethodID(env,detailed,"routeFlowPort","(III)J");
        }
        if(detailed)(*env)->DeleteLocalRef(env,detailed);
        if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);h.route_port=NULL;}
    }
    if(router){
        jclass owned=(*env)->FindClass(env,"org/steer/core/SteerCore$OwnedFlowRouter");
        if(owned&&(*env)->IsInstanceOf(env,router,owned)){
            jmethodID enabled=(*env)->GetMethodID(env,owned,"ownerFlowsEnabled","()Z");
            if(enabled&&!(*env)->ExceptionCheck(env)&&(*env)->CallBooleanMethod(env,router,enabled)&&!(*env)->ExceptionCheck(env))
                h.route_owned=(*env)->GetMethodID(env,owned,"routeOwnedFlow","([B)[B");
        }
        if(owned)(*env)->DeleteLocalRef(env,owned);
        if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);h.route_owned=NULL;}
    }
    transport_set_socket_prepare(protect_socket,&h);
    qc_set_socket_prepare(protect_socket,&h);
    stack_set_flow_filter(blocked_flow,&blocked);stack_enable_counters(1);
    struct output output={0};
    snprintf(output.device,sizeof(output.device),"android-vpn");
    struct dialer dialer={.ops=endpoint.proxy==2?&hy2_dialer:(endpoint.proxy?px_dialer_for(endpoint.px.proto):&vless_dialer),
        .ctx=endpoint.proxy==2?(const void *)&endpoint.hy:(endpoint.proxy?(const void *)&endpoint.px:(const void *)&endpoint.vl),.silence_s=30};
    struct host_router *routing=NULL;
    if(router){routing=calloc(1,sizeof(*routing));if(!routing){(*env)->DeleteGlobalRef(env,h.service);(*env)->DeleteGlobalRef(env,h.router);transport_set_socket_prepare(NULL,NULL);qc_set_socket_prepare(NULL,NULL);stack_set_flow_filter(NULL,NULL);steer_set_state_dir(NULL);(*env)->ReleaseStringUTFChars(env,state,dir);return 2;}routing->inner=dialer;routing->route=route_flow;routing->host=&h;routing->dns_port=(unsigned)dnsPort;routing->upstream_dns=dnsAddress.s_addr;}
    if(routing && h.route6)routing->route6=route_flow6;
    if(routing && h.route_port)routing->route_port=route_flow_port;
    if(routing && h.route_owned)routing->route_owned=route_owned_flow;
    if(routing && h.flow_result)routing->flow_result=proxy_flow_result;
    if(endpoint.proxy==2) {
        /* Multiplexer threads outlive stack_run_fd. Keep their callbacks, node
         * and paths valid until the host ends its isolated process. */
        struct endpoint *persistent=malloc(sizeof(*persistent));
        struct host *persistent_host=malloc(sizeof(*persistent_host));
        struct blocked *persistent_blocked=malloc(sizeof(*persistent_blocked));
        char *persistent_dir=strdup(dir);
        if(!persistent||!persistent_host||!persistent_blocked||!persistent_dir) {
            free(persistent);free(persistent_host);free(persistent_blocked);free(persistent_dir);
            transport_set_socket_prepare(NULL,NULL);qc_set_socket_prepare(NULL,NULL);stack_set_flow_filter(NULL,NULL);
            (*env)->DeleteGlobalRef(env,h.service);steer_set_state_dir(NULL);
            (*env)->ReleaseStringUTFChars(env,state,dir);return 2;
        }
        *persistent=endpoint;*persistent_host=h;*persistent_blocked=blocked;
        steer_set_state_dir(persistent_dir);
        (*env)->ReleaseStringUTFChars(env,state,dir);
        transport_set_socket_prepare(protect_socket,persistent_host);qc_set_socket_prepare(protect_socket,persistent_host);
        stack_set_flow_filter(blocked_flow,persistent_blocked);dialer.ctx=&persistent->hy;
        if(hy2c_start(&persistent->hy)!=0)return 2;
        if(routing){routing->inner=dialer;routing->host=persistent_host;dialer.ops=&host_dialer;dialer.ctx=routing;stack_set_flow_filter(host_route_filter,routing);}
        return stack_run_fd(&output,&dialer,fd,NULL,NULL);
    }
    if(routing){dialer.ops=&host_dialer;dialer.ctx=routing;stack_set_flow_filter(host_route_filter,routing);}
#ifdef MCRF_UTLS49
    node->runtime_epoch49=begin_tls49();
    rc=node->runtime_epoch49?stack_run_fd(&output,&dialer,fd,NULL,NULL):2;
    end_tls49(node->runtime_epoch49);
#else
    rc=stack_run_fd(&output,&dialer,fd,NULL,NULL);
#endif
    free(routing);if(h.router)(*env)->DeleteGlobalRef(env,h.router);
    // Workers have joined when stack returns.
    transport_set_socket_prepare(NULL,NULL);
    qc_set_socket_prepare(NULL,NULL);
    stack_set_flow_filter(NULL,NULL);
    (*env)->DeleteGlobalRef(env,h.service);
    steer_set_state_dir(NULL);
    (*env)->ReleaseStringUTFChars(env,state,dir);
    return rc;
}
