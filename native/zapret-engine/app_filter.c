/* MCRF process-scoped Zapret extension. MIT; upstream Zapret license unchanged.
 * Only observed, live endpoints are trusted. Never infer an owner from a port,
 * a DNS cache, or a PID without holding that exact process object alive.
 */
#if defined(__CYGWIN__) || defined(MCRF_APP_FILTER_TEST)
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include "app_filter.h"
#define BUCKETS 4096
#define MAX_FLOWS 8192
#define PATH_CHARS 2048
#define MAX_APPS 256
struct mcrf_app_filter { bool exclude; unsigned count; wchar_t *names[MAX_APPS]; bool paths[MAX_APPS]; };
struct tuple { UINT32 local[4],remote[4]; UINT16 local_port,remote_port; UINT8 protocol; };
struct owner { struct owner *next; struct tuple key; UINT64 endpoint; INT64 timestamp; HANDLE process; wchar_t path[PATH_CHARS]; bool ambiguous; };
static struct owner *buckets[BUCKETS];
static CRITICAL_SECTION gate;
static HANDLE observers[2]={INVALID_HANDLE_VALUE,INVALID_HANDLE_VALUE},threads[2];
static volatile LONG healthy[2];
static bool needed,started;
static unsigned count;
static wchar_t current_path[PATH_CHARS];
static struct mcrf_app_filter *bypass_apps;
static UINT64 current_endpoint;
static unsigned bucket(const struct tuple *t){unsigned h=2166136261u;for(int k=0;k<4;k++){h=(h^t->local[k])*16777619u;h=(h^t->remote[k])*16777619u;}return ((h^t->local_port^((unsigned)t->remote_port<<16)) *16777619u^t->protocol)%BUCKETS;}
static bool same(const struct tuple *a,const struct tuple *b){return a->protocol==b->protocol&&a->local_port==b->local_port&&a->remote_port==b->remote_port&&!memcmp(a->local,b->local,16)&&!memcmp(a->remote,b->remote,16);}
static UINT64 filetime(FILETIME t){return ((UINT64)t.dwHighDateTime<<32)|t.dwLowDateTime;}
static void dispose_owner(struct owner *o){if(o->process)CloseHandle(o->process);free(o);count--;}
static bool process_before_event(HANDLE process,INT64 stamp){
    FILETIME birth,exit,kernel,user,now;LARGE_INTEGER ticks,freq;
    if(!GetProcessTimes(process,&birth,&exit,&kernel,&user)||!QueryPerformanceFrequency(&freq)||!QueryPerformanceCounter(&ticks)||stamp>ticks.QuadPart)return false;
    GetSystemTimePreciseAsFileTime(&now);
    double elapsed=(double)(ticks.QuadPart-stamp)*10000000.0/freq.QuadPart;
    /* Guard calibration uncertainty and PID reuse. A very new process is skipped. */
    return elapsed>=0&&elapsed<300000000.0&&filetime(birth)+100000u<filetime(now)-(UINT64)elapsed;
}
static DWORD WINAPI observe(void *arg){int slot=(int)(INT_PTR)arg;WINDIVERT_ADDRESS a;
    while(WinDivertRecv(observers[slot],NULL,0,NULL,&a)){
        const WINDIVERT_DATA_FLOW *f=slot==0?&a.Flow:(const WINDIVERT_DATA_FLOW *)&a.Socket;
        if((f->Protocol!=6&&f->Protocol!=17)||!f->LocalPort)continue;
        struct tuple key;memset(&key,0,sizeof(key));memcpy(key.local,f->LocalAddr,16);memcpy(key.remote,f->RemoteAddr,16);key.local_port=f->LocalPort;key.remote_port=f->RemotePort;key.protocol=f->Protocol;
        bool removed=a.Event==WINDIVERT_EVENT_FLOW_DELETED||a.Event==WINDIVERT_EVENT_SOCKET_CLOSE;
        bool added=a.Event==WINDIVERT_EVENT_FLOW_ESTABLISHED||a.Event==WINDIVERT_EVENT_SOCKET_CONNECT||a.Event==WINDIVERT_EVENT_SOCKET_ACCEPT;
        if(!removed&&!added)continue;
        EnterCriticalSection(&gate);
        unsigned h=bucket(&key);struct owner **p=&buckets[h],*o;
        while((o=*p)!=NULL){
            if(same(&key,&o->key)){
                if(removed){if(o->endpoint==f->EndpointId&&a.Timestamp>=o->timestamp){*p=o->next;dispose_owner(o);continue;}break;}
                if(a.Timestamp<o->timestamp){added=false;break;}
                if(o->endpoint==f->EndpointId){added=false;break;}
                if(o->process&&WaitForSingleObject(o->process,0)==WAIT_TIMEOUT){o->ambiguous=true;added=false;break;}
                *p=o->next;dispose_owner(o);continue;
            }p=&o->next;
        }
        if(added&&f->RemotePort&&count<MAX_FLOWS){
            HANDLE process=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION|SYNCHRONIZE,FALSE,f->ProcessId);
            DWORD length=PATH_CHARS;wchar_t path[PATH_CHARS];
            if(process&&process_before_event(process,a.Timestamp)&&WaitForSingleObject(process,0)==WAIT_TIMEOUT&&QueryFullProcessImageNameW(process,0,path,&length)&&length<PATH_CHARS){
                o=calloc(1,sizeof(*o));if(o){o->key=key;o->endpoint=f->EndpointId;o->timestamp=a.Timestamp;o->process=process;memcpy(o->path,path,(length+1)*sizeof(wchar_t));o->next=buckets[h];buckets[h]=o;count++;process=NULL;}
            }if(process)CloseHandle(process);
        }
        LeaveCriticalSection(&gate);
    }InterlockedExchange(&healthy[slot],0);fprintf(stderr,"MCRF: process observer stopped; unknown application traffic passes unchanged (error %lu)\n",(unsigned long)GetLastError());return 0;
}
struct mcrf_app_filter *mcrf_apps_load(const char *file,bool exclude){
    FILE *in=fopen(file,"rb");if(!in)return NULL;struct mcrf_app_filter *filter=calloc(1,sizeof(*filter));char line[PATH_CHARS*4];bool ok=filter!=NULL;
    if(filter)filter->exclude=exclude;
    while(ok&&fgets(line,sizeof(line),in)){
        size_t n=strlen(line);while(n&&(line[n-1]=='\r'||line[n-1]=='\n'))line[--n]=0;if(!n)continue;
        bool path=!strncmp(line,"path:",5),name=!strncmp(line,"app:",4);if((!path&&!name)||filter->count==MAX_APPS||(!feof(in)&&n==sizeof(line)-1)){ok=false;break;}
        char *text=line+(path?5:4);int len=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text,-1,NULL,0);if(len<2||len>PATH_CHARS){ok=false;break;}
        wchar_t *value=malloc(len*sizeof(wchar_t));if(!value){ok=false;break;}MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text,-1,value,len);
        if(!path&&(wcschr(value,L'\\')||wcschr(value,L'/'))){free(value);ok=false;break;}
        filter->names[filter->count]=value;filter->paths[filter->count++]=path;
    }
    if(ferror(in))ok=false;fclose(in);if(!ok||!filter->count){mcrf_apps_free(filter);return NULL;}needed=true;return filter;
}
void mcrf_apps_free(struct mcrf_app_filter *filter){if(filter){for(unsigned i=0;i<filter->count;i++)free(filter->names[i]);free(filter);}}
bool mcrf_apps_start(void){
    if(!needed)return true;if(started)return false;InitializeCriticalSection(&gate);started=true;
    for(int i=0;i<2;i++){
        observers[i]=WinDivertOpen("tcp or udp",i==0?WINDIVERT_LAYER_FLOW:WINDIVERT_LAYER_SOCKET,0,WINDIVERT_FLAG_SNIFF|WINDIVERT_FLAG_RECV_ONLY);
        if(observers[i]==INVALID_HANDLE_VALUE){mcrf_apps_stop();return false;}
        InterlockedExchange(&healthy[i],1);threads[i]=CreateThread(NULL,0,observe,(void *)(INT_PTR)i,0,NULL);if(!threads[i]){mcrf_apps_stop();return false;}
    }return true;
}
void mcrf_apps_stop(void){if(!started)return;for(int i=0;i<2;i++)if(observers[i]!=INVALID_HANDLE_VALUE)WinDivertShutdown(observers[i],WINDIVERT_SHUTDOWN_RECV);for(int i=0;i<2;i++){if(threads[i]){WaitForSingleObject(threads[i],INFINITE);CloseHandle(threads[i]);threads[i]=NULL;}if(observers[i]!=INVALID_HANDLE_VALUE){WinDivertClose(observers[i]);observers[i]=INVALID_HANDLE_VALUE;}InterlockedExchange(&healthy[i],0);}for(unsigned i=0;i<BUCKETS;i++){struct owner *o=buckets[i];while(o){struct owner *next=o->next;dispose_owner(o);o=next;}buckets[i]=NULL;}DeleteCriticalSection(&gate);started=false;current_path[0]=0;}
void mcrf_apps_packet(const void *packet,unsigned length,const WINDIVERT_ADDRESS *address){
    current_path[0]=0;current_endpoint=0;if(!started||!InterlockedCompareExchange(&healthy[0],0,0)||!InterlockedCompareExchange(&healthy[1],0,0)||address->Impostor||address->Loopback)return;
    PWINDIVERT_IPHDR ip=NULL;PWINDIVERT_IPV6HDR ip6=NULL;PWINDIVERT_TCPHDR tcp=NULL;PWINDIVERT_UDPHDR udp=NULL;
    if(!WinDivertHelperParsePacket(packet,length,&ip,&ip6,NULL,NULL,NULL,&tcp,&udp,NULL,NULL,NULL,NULL)||(!tcp&&!udp))return;
    struct tuple key;memset(&key,0,sizeof(key));UINT32 src[4]={0},dst[4]={0};
    if(ip){src[0]=WinDivertHelperNtohl(ip->SrcAddr);dst[0]=WinDivertHelperNtohl(ip->DstAddr);src[1]=dst[1]=0xffff;}
    else if(ip6){WinDivertHelperNtohIPv6Address(ip6->SrcAddr,src);WinDivertHelperNtohIPv6Address(ip6->DstAddr,dst);}else return;
    memcpy(key.local,address->Outbound?src:dst,16);memcpy(key.remote,address->Outbound?dst:src,16);
    UINT16 sport=WinDivertHelperNtohs(tcp?tcp->SrcPort:udp->SrcPort),dport=WinDivertHelperNtohs(tcp?tcp->DstPort:udp->DstPort);
    key.local_port=address->Outbound?sport:dport;key.remote_port=address->Outbound?dport:sport;key.protocol=tcp?6:17;
    EnterCriticalSection(&gate);for(struct owner *o=buckets[bucket(&key)];o;o=o->next)if(same(&key,&o->key)){if(!o->ambiguous&&o->timestamp<=address->Timestamp&&WaitForSingleObject(o->process,0)==WAIT_TIMEOUT){wcscpy(current_path,o->path);current_endpoint=o->endpoint;}break;}LeaveCriticalSection(&gate);
}
bool mcrf_apps_match(const struct mcrf_app_filter *filter){
    if(!filter)return true;if(!current_path[0])return false;
    const wchar_t *base=wcsrchr(current_path,L'\\');base=base?base+1:current_path;
    bool found=false;for(unsigned i=0;i<filter->count;i++)if(CompareStringOrdinal(filter->names[i],-1,filter->paths[i]?current_path:base,-1,TRUE)==CSTR_EQUAL){found=true;break;}
    return filter->exclude?!found:found;
}
bool mcrf_apps_known(void){return current_path[0]!=0;}
bool mcrf_apps_set_bypass(const char *file){if(bypass_apps)return false;bypass_apps=mcrf_apps_load(file,false);return bypass_apps!=NULL;}
/* Unknown ownership is deliberately passed through, not guessed from a port.
 * This also protects connections created before the observer started. */
bool mcrf_apps_bypass(void){return bypass_apps && (!mcrf_apps_known() || mcrf_apps_match(bypass_apps));}
UINT64 mcrf_apps_endpoint(void){return current_path[0]?current_endpoint:0;}
#endif
