/* NEW actual production Go-TLS/native-decoder composition. Synthetic socketpair
 * and private temporary PKI only; no saved profile, user trust or network. */
#if !defined(MCRF_CERT_COMPRESSION49)||!defined(STEER_HAVE_X509WRITE)
#error Private codec+PKI required
#endif
#define main unused_legacy_suite49
#include "vlessmatch49.inc"
#undef main
#include "tls49_abi.h"
#include "cert_compress49.h"

static int go_case49(const struct plan *plan,const char *fp,const char *roots,size_t roots_n){
    int sv[2];if(socketpair(AF_UNIX,SOCK_STREAM,0,sv))return -200;
    sock_ready(sv[0],3);sock_ready(sv[1],3);
    struct srv server={.fd=sv[1],.plan=plan};pthread_t thread;
    if(pthread_create(&thread,NULL,server_half,&server)){close(sv[0]);close(sv[1]);return -201;}
    char options[1024];int n=snprintf(options,sizeof options,
        "{\"security\":\"tls\",\"host\":\"node.invalid\",\"sni\":\"%s\",\"fingerprint\":\"%s\",\"timeoutMS\":2500}",TLS_SNI,fp);
    uint64_t epoch=mcrf49_begin(),handle=0;
    int rc=epoch?mcrf49_open(sv[0],epoch,options,(size_t)n,(char *)roots,roots_n,&handle):-202;
    if(handle)mcrf49_close(handle);if(epoch)mcrf49_stop(epoch);
    /* The C side still owns the borrowed descriptor. Close it too, including
     * every rejection, so server join cannot wait for a peer nobody owns. */
    shutdown(sv[0],SHUT_RDWR);close(sv[0]);pthread_join(thread,NULL);
    if(server.fd>=0)close(server.fd);
    return rc;
}
int main(void){
    signal(SIGPIPE,SIG_IGN);
    if(mcrf49_set_decoder(cert_decompress49))return 2;
    if(chain_build())return 2;
    char roots[65536];FILE *f=fopen(g_roots_file,"rb");if(!f){chain_free();return 2;}
    size_t roots_n=fread(roots,1,sizeof roots,f);int bad=ferror(f)||!feof(f);fclose(f);
    if(bad||!roots_n){chain_free();return 2;}
    const struct plan cases[]={
      {.name="Go compressed TLS chain+raw transcript",.chain=LEAF_OK,.compressed_zlib49=1},
      {.name="Go compressed TLS wrong name",.chain=LEAF_NAME,.compressed_zlib49=1},
      {.name="Go compressed TLS untrusted root",.chain=LEAF_SELF,.compressed_zlib49=1},
      {.name="Go compressed TLS bad CV",.chain=LEAF_OK,.compressed_zlib49=1,.cv_bad_sig=1},
      {.name="Go compressed TLS wrong length",.chain=LEAF_OK,.compressed_zlib49=1,.compressed_bad_length49=1},
      {.name="Go compressed TLS unoffered codec",.chain=LEAF_OK,.compressed_zlib49=1}
    };
    /* Initialize Go/netpoll once before the descriptor-leak baseline. */
    if(go_case49(&cases[0],"safari",roots,roots_n)){chain_free();return 1;}
    int before=fd_count();
    for(unsigned i=0;i<sizeof cases/sizeof cases[0];i++){
        int rc=go_case49(&cases[i],i==5?"edge":"safari",roots,roots_n);
        check(cases[i].name,i?1:0,rc!=0);
        check("Go compressed TLS owned fds retired",before,fd_count());
    }
    chain_free();
    puts(fails?"CERT_WIRE49_GO FAILED":"CERT_WIRE49_GO production-auth+bounded-codec+raw-transcript+negative-cert+fd-retirement PASS");
    return fails?1:0;
}
