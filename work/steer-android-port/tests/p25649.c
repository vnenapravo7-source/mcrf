/* Execute only with the real pinned crypto library in the common native batch. */
#include <assert.h>
#include <string.h>
#include <stdio.h>
#include "scrypto.h"
int main(void){
 unsigned char a[32],b[32],ap[65],bp[65],ab[32],ba[32],bad[65]={4},zero[32]={0};
 assert(!sc_p256_keypair(a,ap)&&!sc_p256_keypair(b,bp));assert(ap[0]==4&&bp[0]==4);
 assert(!sc_p256_shared(ab,a,bp)&&!sc_p256_shared(ba,b,ap));assert(!memcmp(ab,ba,32));
 assert(sc_p256_shared(ab,a,bad)!=0&&!memcmp(ab,zero,32));
 bad[0]=2;assert(sc_p256_shared(ab,a,bad)!=0&&!memcmp(ab,zero,32));
 assert(sc_p256_shared(ab,zero,bp)!=0&&!memcmp(ab,zero,32));
 memset(a,0,sizeof a);memset(b,0,sizeof b);memset(ba,0,sizeof ba);
 puts("P25649 two-real-keys shared-secret off-curve format zero-scalar PASS");return 0;
}
