/* Codec vectors only; authenticated compressed-TLS composition is cert-wire49. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "cert_compress49.h"
#include "scrypto.h"
static const unsigned char zlib_hello[]={0x78,0x9c,0xcb,0x48,0xcd,0xc9,0xc9,0x07,0x00,0x06,0x2c,0x02,0x15};
static const unsigned char zstd_hello[]={0x28,0xb5,0x2f,0xfd,0x20,0x05,0x29,0,0,'h','e','l','l','o'};
/* Official MIT-licensed Brotli1.2.0 tests/testdata/ukkonooa.compressed. */
static const unsigned char brotli_vector[]={
 0x1b,0x76,0,0,0x14,0x4a,0xac,0x9b,0x7a,0xbd,0xe1,0x97,0x9d,0x7f,0x8e,0xc2,0x82,0x36,0x0e,0x9c,0xe0,0x90,3,0xf7,0x8b,0x9e,0x38,0xe6,0xb6,0,0xab,0xc3,0xca,0xa0,0xc2,0xda,0x66,0x36,0xdc,0xcd,0x80,0x8d,0x2e,0x21,0xd7,0x6e,0xe3,0xea,0x4c,0xb8,0xf0,0xd2,0xb8,0xc7,0xc2,0x70,0x4d,0x3a,0xf0,0x69,0x7e,0xa1,0xb8,0x45,0x73,0xab,0xc4,0x57,0x1e
};
static const unsigned char brotli_sha[]={0x1f,0xb2,0x52,0x9b,0x0c,0x6f,0xbf,0x80,0xa6,0x0d,0x2b,0xb4,0xc2,0x0e,0xfb,0xa2,0xff,0xf6,0xce,0xcb,0x0c,0x52,0xd4,0x26,0x93,0xd4,4,0x50,0x10,0x64,0xf4,7};
static void vector(unsigned algorithm,const unsigned char *input,size_t in_n,size_t expected){
 unsigned char body[512]={0,(unsigned char)algorithm,0,0,(unsigned char)expected,0,(unsigned char)(in_n>>8),(unsigned char)in_n},out[512],hash[32];size_t n;
 memcpy(body+8,input,in_n);
 for(unsigned repeat=0;repeat<4;repeat++){
  assert(!cert_decompress49(1u<<algorithm,body,in_n+8,out,sizeof out,&n)&&n==expected);
  if(algorithm==2){assert(!sc_hash(SC_SHA256,out,n,hash)&&!memcmp(hash,brotli_sha,32));}
  else assert(n==5&&!memcmp(out,"hello",5));
  assert(cert_decompress49(0,body,in_n+8,out,sizeof out,&n)&&n==0);
  assert(cert_decompress49(1u<<algorithm,body,in_n+8,out,expected-1,&n)&&n==0);
  for(size_t cut=0;cut<in_n+8;cut++)assert(cert_decompress49(1u<<algorithm,body,cut,out,sizeof out,&n));
  body[in_n+8]=0;assert(cert_decompress49(1u<<algorithm,body,in_n+9,out,sizeof out,&n));
  body[7]++;assert(cert_decompress49(1u<<algorithm,body,in_n+8,out,sizeof out,&n));body[7]--;
  body[4]++;assert(cert_decompress49(1u<<algorithm,body,in_n+8,out,sizeof out,&n));body[4]--;
 }
}
int main(void){
 vector(1,zlib_hello,sizeof zlib_hello,5);vector(2,brotli_vector,sizeof brotli_vector,119);vector(3,zstd_hello,sizeof zstd_hello,5);
 unsigned char oversized[]={0,3,0,0,5,0,0,14,0x28,0xb5,0x2f,0xfd,0,0x90,0x29,0,0,'h','e','l','l','o'},out[32];size_t n;
 assert(cert_decompress49(1u<<3,oversized,sizeof oversized,out,sizeof out,&n));
 puts("CERT_CODEC49 zlib+brotli+zstd exact-output all-cuts trailing unoffered wrong-length bounded-window recovery PASS");return 0;
}
