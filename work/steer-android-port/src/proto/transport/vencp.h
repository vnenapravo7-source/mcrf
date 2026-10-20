/* Разбор строки `encryption` узла VLESS: mlkem768x25519plus.<режим>.<время>.<набивка>.<ключ>[.<ключ>…]
 *
 * Только строки, без криптографии, поэтому это заголовок со static-функциями: его включают и разбор
 * подписки (sub.c, стенды `make test` без библиотеки), и само шифрование (trvenc.c). Правило — то, что
 * делает Xray-core при сборке конфига (infra/conf/vless.go, VLessOutboundConfig.Build) и клиента
 * (proxy/vless/outbound/outbound.go, New):
 *
 *     mlkem768x25519plus . native|xorpub|random . 1rtt|0rtt . [набивка .]… ключ [. ключ]…
 *
 *   - режим: native — записи как есть; xorpub — открытые ключи рукопожатия (X25519, шифротекст
 *     ML-KEM) замаскированы гаммой, чтобы не отличаться от случайных байт; random — сверх того
 *     маскируются заголовки всех записей, так что поток целиком неотличим от шума;
 *   - 0rtt разрешает клиенту продолжать по билету, выданному сервером (без нового обмена ключами);
 *     1rtt — всегда полное рукопожатие. Клиент, заявивший 0rtt, может и не воспользоваться билетом;
 *   - набивка — токены короче 20 знаков вида «100-111-1111» (вероятность, от, до), чередуются: длина,
 *     пауза, длина…; их сервер выдаёт клиенту в ссылке. Нет — умолчание Xray;
 *   - ключ — токен от 20 знаков, base64url без выравнивания: 32 байта (X25519, открытый ключ
 *     реле) либо 1184 (ML-KEM-768, ключ инкапсуляции). Реле идут цепочкой, в порядке записи.
 *
 * Токен 20 знаков и длиннее, не разбирающийся в 32 или 1184 байта, делает строку негодной — так Xray
 * отвергает конфиг («unsupported encryption»), а не молча пропускает. */
#ifndef STEER_VENCP_H
#define STEER_VENCP_H
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define VENC_MAX_KEYS 6
#define VENC_MAX_PAD  8            /* пар (длина, пауза) набивки */
#define VENC_PREFIX "mlkem768x25519plus"

struct venc_cfg {
    uint8_t xor_mode;              /* 0 native, 1 xorpub, 2 random */
    uint8_t zero_rtt;              /* 0rtt: клиенту разрешено брать билет */
    uint8_t nkeys;
    uint8_t key_kind[VENC_MAX_KEYS];             /* 32 или 0 (ML-KEM: 1184) — по длине токена */
    const char *key[VENC_MAX_KEYS];              /* начало токена в исходной строке */
    uint16_t key_len[VENC_MAX_KEYS];             /* длина токена в знаках */
    /* Набивка: тройки (вероятность 0..100, от, до). lens[i] — длина, gaps[i] — пауза в мс. */
    uint8_t npad_lens, npad_gaps;
    uint16_t lens[VENC_MAX_PAD][3];
    uint16_t gaps[VENC_MAX_PAD][3];
};

static inline int vencp_b64url_len(const char *s, size_t n) {
    for (size_t i = 0; i < n; i++) {
        char c = s[i];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
              c == '-' || c == '_')) return -1;
    }
    if (n % 4 == 1) return -1;
    return (int)(n * 6 / 8);
}

/* Одна тройка «p-a-b»; 0 — разобрана. Как Xray: минимум три части, числа десятичные. */
static inline int vencp_triple(const char *s, size_t n, uint16_t out[3]) {
    size_t p = 0;
    for (int k = 0; k < 3; k++) {
        if (p >= n || s[p] < '0' || s[p] > '9') return -1;
        unsigned v = 0;
        while (p < n && s[p] >= '0' && s[p] <= '9') { v = v * 10 + (unsigned)(s[p] - '0'); p++; if (v > 65535) return -1; }
        out[k] = (uint16_t)v;
        if (k < 2) { if (p >= n || s[p] != '-') return -1; p++; }
    }
    /* Хвост после третьей части («-…») Xray отбрасывает: берёт первые три поля. */
    return 0;
}

/* Разобрать строку. 0 — годна; иначе -1 и why (короткая причина, статическая строка). */
static inline int vencp_parse(const char *s, struct venc_cfg *c, const char **why) {
    memset(c, 0, sizeof(*c));
    const char *w = "";
    size_t pl = strlen(VENC_PREFIX);
    if (strncmp(s, VENC_PREFIX, pl) != 0 || s[pl] != '.') { w = "encryption не mlkem768x25519plus"; goto bad; }
    const char *p = s + pl + 1;
    if (!strncmp(p, "native.", 7)) c->xor_mode = 0;
    else if (!strncmp(p, "xorpub.", 7)) c->xor_mode = 1;
    else if (!strncmp(p, "random.", 7)) c->xor_mode = 2;
    else { w = "encryption: режим не native/xorpub/random"; goto bad; }
    p += 7;
    if (!strncmp(p, "1rtt.", 5)) c->zero_rtt = 0;
    else if (!strncmp(p, "0rtt.", 5)) c->zero_rtt = 1;
    else { w = "encryption: не 1rtt/0rtt"; goto bad; }
    p += 5;
    int tok_i = 0;
    while (*p) {
        const char *e = strchr(p, '.');
        size_t n = e ? (size_t)(e - p) : strlen(p);
        if (n == 0) { w = "encryption: пустой токен"; goto bad; }
        if (n < 20) {
            /* Набивка. Ключи бывают только после неё: Xray считает набивкой ровно токены короче 20
             * знаков в начале, и ключ короче 20 знаков быть не может. */
            if (c->nkeys) { w = "encryption: набивка после ключа"; goto bad; }
            uint16_t t3[3];
            if (vencp_triple(p, n, t3) != 0) { w = "encryption: набивка не разобралась"; goto bad; }
            if (tok_i % 2 == 0) {
                if (c->npad_lens >= VENC_MAX_PAD) { w = "encryption: набивка слишком длинная"; goto bad; }
                memcpy(c->lens[c->npad_lens++], t3, sizeof t3);
                if (c->npad_lens == 1 && (t3[0] < 100 || t3[1] < 18 + 17 || t3[2] < 18 + 17)) {
                    w = "encryption: первая набивка меньше 35"; goto bad;
                }
            } else {
                if (c->npad_gaps >= VENC_MAX_PAD) { w = "encryption: набивка слишком длинная"; goto bad; }
                memcpy(c->gaps[c->npad_gaps++], t3, sizeof t3);
            }
            tok_i++;
        } else {
            int bl = vencp_b64url_len(p, n);
            if (bl != 32 && bl != 1184) { w = "encryption: ключ не 32 и не 1184 байта"; goto bad; }
            if (c->nkeys >= VENC_MAX_KEYS) { w = "encryption: слишком много ключей"; goto bad; }
            c->key[c->nkeys] = p;
            c->key_len[c->nkeys] = (uint16_t)n;
            c->key_kind[c->nkeys] = bl == 32 ? 32 : 0;
            c->nkeys++;
        }
        if (!e) break;
        p = e + 1;
    }
    if (!c->nkeys) { w = "encryption без ключа"; goto bad; }
    return 0;
bad:
    if (why) *why = w;
    return -1;
}

#endif
