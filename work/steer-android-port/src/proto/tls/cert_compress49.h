#ifndef MCRF_CERT_COMPRESS49_H
#define MCRF_CERT_COMPRESS49_H
#include <stddef.h>
/* RFC8879 body: algorithm16, uncompressed_length24, compressed_length24, data.
 * Only advertised algorithms are admitted; no chain/name/authentication bypass.
 * Memory limits are independent of the peer's declared output length. */
int cert_decompress49(unsigned offered,const unsigned char *body,size_t body_n,
    unsigned char *out,size_t cap,size_t *out_n);
#endif
