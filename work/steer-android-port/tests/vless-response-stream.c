#include <assert.h>
#include <string.h>
#include "vless_proto.h"

int main(void) {
    unsigned char wire[261]={0};
    for(unsigned extra=0;extra<=255;extra++) {
        wire[1]=(unsigned char)extra;
        memset(wire+2,0xa5,extra);memcpy(wire+2+extra,"data",4);
        for(unsigned cut=0;cut<=2+extra;cut++) {
            struct vless_response_stream s={0};size_t used=0;
            int first=vless_response_feed(&s,wire,cut,&used);
            assert(used==cut);
            assert(first==(cut<2+extra?VLESS_EAGAIN:0));
            assert(vless_response_feed(&s,wire+cut,2+extra+4-cut,&used)==0);
            assert(used==2+extra-cut);
            assert(memcmp(wire+cut+used,"data",4)==0);
        }
        struct vless_response_stream s={0};size_t used;
        for(unsigned i=0;i<2+extra;i++){
            int rc=vless_response_feed(&s,wire+i,1,&used);
            assert(used==1&&rc==(i+1<2+extra?VLESS_EAGAIN:0));
        }
    }
    struct vless_response_stream s={0};size_t used;
    unsigned char invalid=1;
    assert(vless_response_feed(&s,&invalid,1,&used)==VLESS_EPROTO);
    return 0;
}
