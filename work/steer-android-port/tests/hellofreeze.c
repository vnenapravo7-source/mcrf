/* Байтовая неизменность ClientHello: страховка для правок в reality.c.
 *
 * ЗАЧЕМ. В reality.c написано прямо: отпечаток Chrome — это то, по чему сервер Reality
 * отличает нас от постороннего, и любая правка там проверяется перехватом рядом с
 * браузерным эталоном. Перехват требует сети, сервера и глаз; этот стенд требует только
 * компилятора. Он не заменяет перехват — он ловит НЕПРЕДНАМЕРЕННОЕ изменение байтов,
 * то есть тот случай, когда правка «рядом» поехала в собранный Hello и никто не заметил.
 *
 * КАК ЭТО ВОЗМОЖНО. Hello целиком выводится из случайности, времени и порядка шифров.
 * Случайность здесь подменяется детерминированным генератором (getrandom заменяется
 * макросом ДО включения исходника, поэтому подменяются и ключевая пара, и перемешивание
 * расширений, и весь шум), время фиксируется, порядок шифров пинится через STEER_CIPHER —
 * он зависит от процессора, и без пиннинга «эталон» отличался бы от машины к машине.
 *
 * ПОЧЕМУ НЕ В make test. Нужна настоящая криптобиблиотека: Hello здесь СОБИРАЕТСЯ (X25519 и
 * AES-GCM аутентификатора — через слой src/lib/scrypto.h). Стенд идёт в `make ext-test`
 * (tests/ext-test.sh) — прежде его собирали руками, и он полгода стоял красным незамеченным
 * (см. ниже). Разбор того же Hello проверяет tests/chellomatch.c, который берёт байты из
 * заморозки и потому библиотеки не требует, — он в make test входит.
 *
 *     make ext-test                      # соберёт и сверит с tests/chello-frozen.h
 *     ./build/hellofreeze --emit > tests/chello-frozen.h   # заново заморозить (после ext-test)
 *
 * Заново замораживать можно ТОЛЬКО вместе с перехватом рядом с браузером: заморозка
 * фиксирует то, что есть, и молча узаконит любую поломку отпечатка.
 *
 * ИСТОРИЯ ЗАМОРОЗКИ. Первая снята 17 августа (a33c86e). 8 сентября (7b194a5) версия клиента
 * Reality в session_id стала 26.9.8 — это входит в подписываемые байты, и зашифрованный
 * session_id (байты 44-75 Hello) разошёлся с заморозкой; стенд тогда никто не запускал. При
 * переезде на wolfSSL (выпуск 1.10, шаг 1) заморозка снята заново — с кода main ДО переезда,
 * на mbedtls 3.6.2 из образа сборки (и 2.28 системной — вышло то же), — и новый код даёт ровно
 * её, байт в байт. Отличие от августовской — только эти 32 байта session_id. */
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/random.h>
#include <time.h>

/* Детерминированная замена getrandom. Тот же генератор, что был при первой заморозке;
 * менять его — значит менять эталон, то есть заново замораживать. */
static uint64_t prng = 0x123456789ABCDEFull;
static unsigned g_calls;
static void prng_step(void) { prng = prng * 6364136223846793005ull + 1442695040888963407ull; }

/* СКОЛЬКО СЛУЧАЙНОСТИ БРАЛА mbedtls — И ПОЧЕМУ ЭТО ЗДЕСЬ.
 *
 * mbedtls умножала точку X25519 со «ослеплением» (рандомизацией проективных координат) и брала
 * для него байты из того же источника, что и весь Hello: по 64 на умножение, а умножений в
 * сборке Hello два — открытый ключ и секрет с pbk сервера. wolfCrypt за слоем scrypto считает
 * X25519 лестницей Монтгомери постоянного времени и ослепляет её (там, где ослепляет: переносимый
 * C, WOLFSSL_CURVE25519_BLINDING) из СВОЕГО генератора, а не из getrandom этого файла, — в поток
 * стенда эта случайность не попадает вовсе. В настоящей работе это ничего не меняет — случайные
 * байты случайны при любом их расходе, — но детерминированный поток стенда без поправки
 * сдвинулся бы на 128 байт, и каждый
 * байт Hello после ключа (random, GREASE, набивка ECH, перемешивание расширений) стал бы другим,
 * хотя сборщик не изменился ни на строку.
 *
 * Поэтому генератор проматывает те же 128 байт в том же месте: сразу после первого вызова
 * (32 байта приватного ключа, x25519_keypair), до второго. Число найдено перебором и сверено:
 * с ним новый код даёт Hello байт в байт тот же, что код main до переезда на mbedtls 3.6.2 и
 * 2.28. Сдвинется место вызовов в reality.c — стенд покраснеет, и это правильно: такая правка
 * требует того же разбора. */
#define MBEDTLS_BLINDING_BYTES 128
static ssize_t det_getrandom(void *buf, size_t n, unsigned int flags) {
    (void)flags;
    if (++g_calls == 2)
        for (int i = 0; i < MBEDTLS_BLINDING_BYTES; i++) prng_step();
    unsigned char *p = buf;
    for (size_t i = 0; i < n; i++) {
        prng_step();
        p[i] = (unsigned char)(prng >> 33);
    }
    return (ssize_t)n;
}
static time_t det_time(time_t *p) { (void)p; return (time_t)1700000000; }

#define getrandom(b, n, f) det_getrandom((b), (n), (f))
#define time(p) det_time(p)
#include "../src/proto/tls/reality.c"
#undef getrandom
#undef time

#include "chello-frozen.h"
#include "chello-frozen-pq.h"

static const struct reality_cfg CFG = {
    .sni = "www.example.com",
    .pbk = "xNlHRs0RY8mJhMhOVWRxg8ykpZmqHrjKQqm3-1lQF3E",
    .sid = "0123456789abcdef",
    .fp  = "chrome",
    .alpn = "h2",
};

/* То же с настоящим гибридом X25519MLKEM768 (reality_cfg.pq): облик Chrome 131+. Ключ ML-KEM делается из
 * seed, взятого у того же детерминированного источника, поэтому байты воспроизводимы. */
static const struct reality_cfg CFG_PQ = {
    .sni = "www.example.com",
    .pbk = "xNlHRs0RY8mJhMhOVWRxg8ykpZmqHrjKQqm3-1lQF3E",
    .sid = "0123456789abcdef",
    .fp  = "chrome",
    .alpn = "h2",
    .pq = 1,
};

static size_t build_cfg(const struct reality_cfg *cfg, const char *cipher, unsigned char *out, size_t cap) {
    setenv("STEER_CIPHER", cipher, 1);
    prng = 0x123456789ABCDEFull;
    g_calls = 0;
    struct reality_state st;
    size_t n = 0;
    if (reality_build_hello(cfg, &st, out, cap, &n) != 0) return 0;
    return n;
}
static size_t build(const char *cipher, unsigned char *out, size_t cap) {
    return build_cfg(&CFG, cipher, out, cap);
}

static void emit(const char *name, const unsigned char *b, size_t n) {
    printf("static const char %s[] =\n    \"", name);
    for (size_t i = 0; i < n; i++) {
        printf("\\x%02x", b[i]);
        if ((i + 1) % 16 == 0 && i + 1 < n) printf("\"\n    \"");
    }
    printf("\";\n\n");
}

int main(int argc, char **argv) {
    unsigned char a[4096], c[4096];
    if (argc > 2 && !strcmp(argv[1], "--raw-pq")) {           /* для сверки с uTLS (tests/hello-diff.py) */
        size_t n = build_cfg(&CFG_PQ, "aes", a, sizeof(a));
        FILE *f = fopen(argv[2], "wb");
        if (!n || !f) return 2;
        fwrite(a, 1, n, f);
        fclose(f);
        return 0;
    }
    if (argc > 1 && !strcmp(argv[1], "--emit-pq")) {
        size_t pa = build_cfg(&CFG_PQ, "aes", a, sizeof(a)), pc = build_cfg(&CFG_PQ, "chacha", c, sizeof(c));
        if (!pa || !pc) { puts("сборка Hello с гибридом отказала"); return 2; }
        puts("/* Сгенерировано tests/hellofreeze.c --emit-pq. Hello с гибридом X25519MLKEM768 (reality_cfg.pq). */");
        puts("#ifndef STEER_CHELLO_FROZEN_PQ_H\n#define STEER_CHELLO_FROZEN_PQ_H\n");
        emit("FROZEN_PQ_AES", a, pa);
        emit("FROZEN_PQ_CHACHA", c, pc);
        printf("#define FROZEN_PQ_N %zu\n\n#endif\n", pa);
        return 0;
    }
    size_t an = build("aes", a, sizeof(a));
    size_t cn = build("chacha", c, sizeof(c));
    if (!an || !cn) { puts("сборка Hello отказала"); return 2; }

    if (argc > 1 && !strcmp(argv[1], "--emit")) {
        puts("/* Сгенерировано tests/hellofreeze.c --emit. Пояснения — в его шапке. */");
        puts("#ifndef STEER_CHELLO_FROZEN_H\n#define STEER_CHELLO_FROZEN_H\n");
        emit("FROZEN_AES", a, an);
        emit("FROZEN_CHACHA", c, cn);
        printf("#define FROZEN_N %zu\n\n#endif\n", an);
        return 0;
    }

    int fails = 0;
    if (an != FROZEN_N || memcmp(a, FROZEN_AES, an) != 0) {
        printf("ПРОВАЛ: Hello (aes) отличается от заморозки (%zu против %d байт)\n",
               an, FROZEN_N);
        for (size_t i = 0; i < an && i < FROZEN_N; i++)
            if (a[i] != (unsigned char)FROZEN_AES[i]) {
                printf("  первое расхождение на байте %zu: %02x против %02x\n",
                       i, a[i], (unsigned char)FROZEN_AES[i]);
                break;
            }
        fails++;
    } else {
        printf("%-62s ok\n", "Hello (aes): байт в байт как в заморозке");
    }
    if (cn != FROZEN_N || memcmp(c, FROZEN_CHACHA, cn) != 0) {
        printf("ПРОВАЛ: Hello (chacha) отличается от заморозки\n");
        fails++;
    } else {
        printf("%-62s ok\n", "Hello (chacha): байт в байт как в заморозке");
    }
    {
        unsigned char pa[4096], pc[4096];
        size_t pan = build_cfg(&CFG_PQ, "aes", pa, sizeof(pa)), pcn = build_cfg(&CFG_PQ, "chacha", pc, sizeof(pc));
        if (pan != FROZEN_PQ_N || memcmp(pa, FROZEN_PQ_AES, pan) != 0) {
            printf("ПРОВАЛ: Hello с гибридом (aes) отличается от заморозки (%zu против %d байт)\n", pan, FROZEN_PQ_N);
            fails++;
        } else printf("%-62s ok\n", "Hello с гибридом (aes): байт в байт как в заморозке");
        if (pcn != FROZEN_PQ_N || memcmp(pc, FROZEN_PQ_CHACHA, pcn) != 0) {
            printf("ПРОВАЛ: Hello с гибридом (chacha) отличается от заморозки\n");
            fails++;
        } else printf("%-62s ok\n", "Hello с гибридом (chacha): байт в байт как в заморозке");
        /* Без гибрида Hello короче ровно на 4 (заголовок группы в key_share) + 1216 + 2 (supported_groups). */
        if (pan != an + 4 + 1216 + 2) {
            printf("ПРОВАЛ: размер Hello с гибридом %zu, ожидалось %zu\n", pan, an + 4 + 1216 + 2);
            fails++;
        } else printf("%-62s ok\n", "гибрид добавляет ровно ключ (1216) и группу");
    }
    /* Порядок наборов ОБЯЗАН различаться: если он одинаков, значит cpu_has_aes перестал
     * влиять на Hello, и роутер на MIPS получит шифр в шесть раз медленнее. */
    if (memcmp(a, c, an) == 0) {
        printf("ПРОВАЛ: aes и chacha дали одинаковый Hello — порядок шифров не действует\n");
        fails++;
    } else {
        printf("%-62s ok\n", "порядок наборов шифров зависит от процессора");
    }
    printf("\n%s\n", fails ? "ЕСТЬ ПРОВАЛЫ" : "все проверки прошли");
    return fails ? 1 : 0;
}
