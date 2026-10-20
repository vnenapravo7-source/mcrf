/* Заглушки для стендов `make test`, которые компонуют transport.c, но без криптографии (wsmatch, xhupmatch):
 * связь у них голая, и ярус VLESS encryption (trvenc.c) до них не доходит. Настоящий ярус проверяется на
 * настоящей библиотеке и против настоящего Xray — tests/vencprobe.c и tests/venc.sh в `make ext-test`. */
#include <stddef.h>
#include "transport.h"
#include "ech.h"

/* То же для ECH (ech.c — HPKE на криптобиблиотеке): trsec.c зовёт его только у узла с `ech`, которого у этих
 * стендов нет. Настоящий ECH — tests/echmatch.c и tests/ech.sh в `make ext-test`. */
int ech_b64_decode(const char *in, uint8_t *out, size_t cap) { (void)in; (void)out; (void)cap; return -1; }
int ech_pick(const uint8_t *list, size_t n, struct ech_cfg *out) { (void)list; (void)n; (void)out; return ECH_ENOCONFIG; }
int ech_wrap(const struct ech_cfg *cfg, const uint8_t *hello, size_t hello_n, uint8_t *out, size_t cap,
             size_t *out_n, struct ech_state *st)
    { (void)cfg; (void)hello; (void)hello_n; (void)out; (void)cap; (void)out_n; (void)st; return ECH_ENOCONFIG; }

int tr_venc_open(struct transport *t, const struct tr_node *n, int timeout_s)
    { (void)t; (void)n; (void)timeout_s; return TR_EVENC; }
int tr_venc_write(struct transport *t, const unsigned char *d, size_t n)
    { (void)t; (void)d; (void)n; return TR_EVENC; }
int tr_venc_read(struct transport *t, unsigned char *d, size_t cap, size_t *got)
    { (void)t; (void)d; (void)cap; *got = 0; return TR_EVENC; }
int tr_venc_pending(const struct transport *t) { (void)t; return 0; }
void tr_venc_close(struct transport *t) { (void)t; }
const char *tr_venc_reason(void) { return ""; }
