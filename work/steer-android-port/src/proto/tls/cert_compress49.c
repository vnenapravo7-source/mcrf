#define ZSTD_STATIC_LINKING_ONLY
#include <zstd.h>
#include <brotli/decode.h>
#include <zlib.h>
#include <stddef.h>
#include <stdint.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include "cert_compress49.h"

#define PER_CODEC_MAX49 (16u*1024u*1024u)
#define ALL_CODECS_MAX49 (32u*1024u*1024u)
struct budget49 {size_t live;};
union allocation49 {max_align_t align;struct {size_t size;struct budget49 *owner;} meta;};
static pthread_mutex_t codec_lock49=PTHREAD_MUTEX_INITIALIZER;
static size_t codecs_live49;
static void *codec_alloc49(void *opaque,size_t size){
    struct budget49 *budget=opaque;
    if(!size)size=1;
    if(size>PER_CODEC_MAX49||size>SIZE_MAX-sizeof(union allocation49))return NULL;
    size_t total=size+sizeof(union allocation49);
    pthread_mutex_lock(&codec_lock49);
    if(total>PER_CODEC_MAX49-budget->live||total>ALL_CODECS_MAX49-codecs_live49){pthread_mutex_unlock(&codec_lock49);return NULL;}
    budget->live+=total;codecs_live49+=total;pthread_mutex_unlock(&codec_lock49);
    union allocation49 *header=malloc(total);
    if(!header){pthread_mutex_lock(&codec_lock49);budget->live-=total;codecs_live49-=total;pthread_mutex_unlock(&codec_lock49);return NULL;}
    header->meta.size=total;header->meta.owner=budget;return header+1;
}
static void codec_free49(void *opaque,void *memory){
    (void)opaque;if(!memory)return;
    union allocation49 *header=(union allocation49*)memory-1;
    pthread_mutex_lock(&codec_lock49);header->meta.owner->live-=header->meta.size;codecs_live49-=header->meta.size;pthread_mutex_unlock(&codec_lock49);
    free(header);
}
static voidpf zalloc49(voidpf opaque,uInt items,uInt size){
    if(size&&items>SIZE_MAX/size)return NULL;return codec_alloc49(opaque,(size_t)items*size);
}
int cert_decompress49(unsigned offered,const unsigned char *body,size_t body_n,unsigned char *out,size_t cap,size_t *out_n){
    if(!out_n)return -1;
    *out_n=0;
    if(!body||!out||body_n<9)return -1;
    unsigned algorithm=(unsigned)body[0]*256+body[1];
    size_t expected=((size_t)body[2]<<16)|((size_t)body[3]<<8)|body[4];
    if(algorithm<1||algorithm>3||!(offered&(1u<<algorithm))||!expected||expected>cap||expected>40960)return -1;
    /* RFC8879 opaque compressed_certificate_message<1..2^24-1> has its
     * OWN uint24 length. Do not accept our former abbreviated mock layout. */
    size_t input_n=((size_t)body[5]<<16)|((size_t)body[6]<<8)|body[7];
    if(!input_n||input_n!=body_n-8)return -1;
    const unsigned char *input=body+8;struct budget49 budget={0};int result=-1;
    if(algorithm==1){
        if(input_n>UINT_MAX||expected>UINT_MAX)return -1;
        z_stream stream={0};stream.zalloc=zalloc49;stream.zfree=codec_free49;stream.opaque=&budget;
        stream.next_in=(Bytef*)input;stream.avail_in=(uInt)input_n;stream.next_out=out;stream.avail_out=(uInt)expected;
        if(inflateInit(&stream)==Z_OK){
            int rc=inflate(&stream,Z_FINISH);
            if(rc==Z_STREAM_END&&!stream.avail_in&&stream.total_out==expected)result=0;
            inflateEnd(&stream);
        }
    }else if(algorithm==2){
        BrotliDecoderState *decoder=BrotliDecoderCreateInstance(codec_alloc49,codec_free49,&budget);
        if(decoder){
            const uint8_t *next_in=input;uint8_t *next_out=out;size_t available_in=input_n,available_out=expected,total=0;
            if(BrotliDecoderSetParameter(decoder,BROTLI_DECODER_PARAM_LARGE_WINDOW,0)){
                BrotliDecoderResult rc=BrotliDecoderDecompressStream(decoder,&available_in,&next_in,&available_out,&next_out,&total);
                if(rc==BROTLI_DECODER_RESULT_SUCCESS&&!available_in&&total==expected)result=0;
            }
            BrotliDecoderDestroyInstance(decoder);
        }
    }else{
        ZSTD_customMem memory={codec_alloc49,codec_free49,&budget};ZSTD_DCtx *decoder=ZSTD_createDCtx_advanced(memory);
        if(decoder){
            size_t frame=ZSTD_findFrameCompressedSize(input,input_n);
            if(!ZSTD_isError(frame)&&frame==input_n&&!ZSTD_isError(ZSTD_DCtx_setParameter(decoder,ZSTD_d_windowLogMax,23))){
                size_t actual=ZSTD_decompressDCtx(decoder,out,expected,input,input_n);
                if(!ZSTD_isError(actual)&&actual==expected)result=0;
            }
            ZSTD_freeDCtx(decoder);
        }
    }
    if(budget.live)result=-1;
    if(!result)*out_n=expected;else memset(out,0,expected);
    return result;
}
