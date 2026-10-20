/* ABI между libsteer.so и libsteer-wolfssl.so (шаг 4 выпуска 1.10, docs/architecture.md,
 * «Криптография»). Собирается ТОЛЬКО в libsteer-wolfssl.so — теми же опциями и заголовками
 * wolfSSL, что сама библиотека, — и рассказывает, из чего она собрана.
 *
 * ЧТО ЗА ABI. Контексты слоя примитивов (src/lib/scrypto.h) — непрозрачные буферы фиксированного
 * размера (SC_HASH_CTX_SIZE, SC_AEAD_CTX_SIZE, SC_AESCTR_CTX_SIZE), внутри которых лежат
 * структуры wolfSSL: их размещает libsteer, а заполняет и читает libsteer-wolfssl. Значит,
 * размеры этих структур в библиотеке — часть ABI между двумя файлами: раньше они были ровно
 * одним бинарником, собранным целиком, и разойтись им было негде; теперь это два пакета, и
 * пакет библиотеки другой сборки (иные опции, иная версия wolfSSL) тихо писал бы мимо поля.
 *
 * ДВЕ ПРОВЕРКИ, И ОБЕ НУЖНЫ.
 *   1. В СБОРКЕ (ниже, _Static_assert): хранилища слоя не меньше настоящих структур. Тот же довод
 *      и те же проверки, что в src/lib/scrypto.c, но для библиотеки: собрать libsteer-wolfssl.so,
 *      которой слой в libsteer не хватает, нельзя.
 *   2. ПРИ ЗАГРУЗКЕ (scrypto.c, sc_abi_check): libsteer сверяет steer_wolfssl_abi с тем, из чего
 *      собран он сам. Проверка сборки не видит пакет, установленный на роутер позже и от другого
 *      выпуска, — эта видит: расхождение = отказ с понятной строкой, а не порча памяти.
 * Массив читают оба конца, поэтому порядок полей здесь и в scrypto.c один (ABI_FIELDS). */
#include <stddef.h>
#include <wolfssl/wolfcrypt/settings.h>
#include <wolfssl/version.h>
#include <wolfssl/ssl.h>
#include <wolfssl/wolfcrypt/sha256.h>
#include <wolfssl/wolfcrypt/sha512.h>
#include <wolfssl/wolfcrypt/sha.h>
#include <wolfssl/wolfcrypt/md5.h>
#include <wolfssl/wolfcrypt/sha3.h>
#include <wolfssl/wolfcrypt/aes.h>
#include <wolfssl/wolfcrypt/chacha.h>
#include <wolfssl/wolfcrypt/poly1305.h>

#include "scrypto.h"

struct chachapoly_abi {
    ChaCha   chacha;
    Poly1305 poly;
};

_Static_assert(sizeof(wc_Sha256) <= SC_HASH_CTX_SIZE && _Alignof(wc_Sha256) <= 16, "SC_HASH_CTX_SIZE мал для wc_Sha256");
_Static_assert(sizeof(wc_Sha512) <= SC_HASH_CTX_SIZE && _Alignof(wc_Sha512) <= 16, "SC_HASH_CTX_SIZE мал для wc_Sha512");
_Static_assert(sizeof(wc_Sha384) <= SC_HASH_CTX_SIZE && _Alignof(wc_Sha384) <= 16, "SC_HASH_CTX_SIZE мал для wc_Sha384");
_Static_assert(sizeof(Aes) <= SC_AEAD_CTX_SIZE && _Alignof(Aes) <= 16, "SC_AEAD_CTX_SIZE мал для Aes");
_Static_assert(sizeof(Aes) <= SC_AESCTR_CTX_SIZE, "SC_AESCTR_CTX_SIZE мал для Aes");
_Static_assert(sizeof(struct chachapoly_abi) <= SC_AEAD_CTX_SIZE && _Alignof(struct chachapoly_abi) <= 16,
               "SC_AEAD_CTX_SIZE мал для ChaCha+Poly1305");
/* Хеши протоколов steer-proxy (wc_Sha224 — тот же тип, что wc_Sha256) и поток SHAKE128 VMess. */
_Static_assert(sizeof(wc_Sha) <= SC_HASH_CTX_SIZE && sizeof(wc_Md5) <= SC_HASH_CTX_SIZE, "SC_HASH_CTX_SIZE мал для wc_Sha/wc_Md5");
_Static_assert(sizeof(wc_Shake) <= SC_SHAKE_CTX_SIZE && _Alignof(wc_Shake) <= 16, "SC_SHAKE_CTX_SIZE мал для wc_Shake");

/* Поля: версия wolfSSL, потом размеры структур в порядке scrypto.c (sc_abi_expect). Массив
 * данных, а не функция: ему не нужно ни вызова, ни библиотеки для чтения. */
__attribute__((visibility("default")))
const unsigned long steer_wolfssl_abi[SC_ABI_N] = {
    LIBWOLFSSL_VERSION_HEX,
    sizeof(wc_Sha256), sizeof(wc_Sha512), sizeof(wc_Sha384),
    sizeof(Aes), sizeof(struct chachapoly_abi),
    SC_HASH_CTX_SIZE, SC_AEAD_CTX_SIZE, SC_AESCTR_CTX_SIZE,
    /* Хранилище корней: scrypto.c берёт у WOLFSSL_X509_STORE поле cm напрямую (sc_roots_load), то
     * есть смещение поля тоже часть ABI. */
    sizeof(WOLFSSL_X509_STORE), offsetof(WOLFSSL_X509_STORE, cm),
    /* Поток SHAKE128 (struct sc_shake): wc_Shake лежит в хранилище, которое размещает libsteer. */
    sizeof(wc_Shake), SC_SHAKE_CTX_SIZE,
};
