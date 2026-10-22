/* Six NEW cases only. Reuse the owned mock TLS server, not its closed suite. */
#if !defined(MCRF_CERT_COMPRESSION49)||!defined(STEER_HAVE_X509WRITE)
#error Scoped compressed-TLS fixture requires real codecs and private PKI build
#endif
#define main old_vless_fixture_main49
#include "vlessmatch49.inc"
#undef main
int main(void){
    signal(SIGPIPE,SIG_IGN);
    if(chain_build())return 2;
    g_cert_roots=g_roots_file;
    const struct plan cases[]={
        {.name="compressed TLS valid chain+raw transcript",.chain=LEAF_OK,.compressed_zlib49=1},
        {.name="compressed TLS wrong name refused",.chain=LEAF_NAME,.compressed_zlib49=1},
        {.name="compressed TLS untrusted root refused",.chain=LEAF_SELF,.compressed_zlib49=1},
        {.name="compressed TLS bad signature refused",.chain=LEAF_OK,.compressed_zlib49=1,.cv_bad_sig=1},
        {.name="compressed TLS wrong declared size refused",.chain=LEAF_OK,.compressed_zlib49=1,.compressed_bad_length49=1},
        {.name="compressed TLS unoffered algorithm refused",.chain=LEAF_OK,.compressed_zlib49=1}
    };
    for(unsigned i=0;i<sizeof cases/sizeof cases[0];i++){
        struct vless_node node;node_tls(&node,"tcp");strcpy(node.fp,i==5?"edge":"safari");
        int before=fd_count(),contexts=-1;char reason[256];
        int rc=run_case(&cases[i],&node,reason,sizeof reason,&contexts);
        check(cases[i].name,i?TLS13_ECERT:0,rc);
        check("compressed TLS descriptors retired",before,fd_count());
        check("compressed TLS cipher contexts retired",0,contexts);
    }
    g_cert_roots=NULL;chain_free();
    puts(fails?"CERT_WIRE49 FAILED":"CERT_WIRE49 chain+name+signature+raw-transcript+negatives PASS");return fails?1:0;
}
