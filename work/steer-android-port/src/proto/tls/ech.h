/* Encrypted Client Hello (RFC 9849, прежний draft-ietf-tls-esni) — клиентская половина для security=tls.
 *
 * ЧТО ЭТО. ClientHello с настоящим именем сервера (SNI) виден любому на пути. ECH прячет его: по проводу
 * идёт ClientHelloOuter с безобидным именем (public_name из ECHConfig, обычно имя провайдера ECH),
 * а настоящий ClientHelloInner лежит в его расширении зашифрованным открытым ключом сервера (HPKE).
 * Сервер, у которого есть закрытый ключ, расшифровывает Inner и проводит рукопожатие по нему; кто ключа не
 * имеет, видит только Outer. Принял ли сервер ECH, клиент узнаёт по восьми последним байтам
 * ServerHello.random (подтверждение принятия) — сверка лежит в tls13.c, здесь только построение Hello.
 *
 * ЧТО ЗДЕСЬ. Разбор ECHConfigList (ech_pick), HPKE (ech_hpke_seal: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256,
 * AES-128-GCM или ChaCha20-Poly1305 — ровно то, что предлагают серверы ECH на практике: Cloudflare, Go
 * crypto/tls, BoringSSL) и сборка пары Outer/Inner из уже построенного ClientHello (ech_wrap). Hello строит
 * прежний сборщик (reality.c) с настоящим SNI — он и становится Inner; ECH лишь заворачивает готовые байты,
 * поэтому облик Hello не зависит от ECH, а замороженные отпечатки (tests/hellofreeze.c) остаются прежними.
 *
 * ЧЕГО НЕТ: сжатия Inner через ech_outer_extensions (Inner идёт полностью — Hello выходит больше, зато проще и
 * принимается любым сервером), GREASE-ECH без конфигурации, HelloRetryRequest с ECH, получения ECHConfigList
 * из DNS (HTTPS-запись): список берётся из ссылки узла (`ech=`). */
#ifndef STEER_ECH_H
#define STEER_ECH_H
#include <stddef.h>
#include <stdint.h>

#define ECH_EPARSE    (-90)   /* ECHConfigList или ClientHello не разобрался */
#define ECH_ENOCONFIG (-91)   /* в списке нет записи, которую мы умеем (версия, KEM, набор шифров, расширения) */
#define ECH_ECRYPTO   (-92)
#define ECH_ETOOBIG   (-93)   /* результат не влез в буфер */

/* Полное handshake-сообщение ClientHelloInner (с четырьмя байтами заголовка) — для транскрипта, когда
 * сервер принял ECH. Hello с ML-KEM в key_share — около 1,8 КБ; запас на расширение и имя. */
#define ECH_INNER_MAX 3072

/* Выбранная запись ECHConfig. */
struct ech_cfg {
    uint8_t  config_id;
    uint16_t kdf_id, aead_id;       /* выбранный набор: HKDF-SHA256 (0x0001), AES-128-GCM (0x0001) или ChaCha20 (0x0003) */
    uint8_t  pk[32];                /* открытый ключ X25519 сервера */
    uint8_t  max_name;              /* maximum_name_length: Inner дополняется до этой длины имени */
    char     public_name[256];      /* имя в SNI внешнего Hello */
    uint8_t  raw[1024];             /* ECHConfig целиком (версия, длина, содержимое): входит в info HPKE */
    size_t   raw_n;
};

struct ech_state {
    uint8_t random[32];             /* random Inner: на нём считается подтверждение принятия */
    size_t  inner_n;
    uint8_t inner[ECH_INNER_MAX];
};

/* base64 (стандартный алфавит, `=` и переводы строк допустимы) в байты; длина или -1. */
int ech_b64_decode(const char *in, uint8_t *out, size_t cap);

/* Выбрать запись из ECHConfigList: первая версии 0xfe0d с KEM X25519, набором HKDF-SHA256 и AES-128-GCM либо
 * ChaCha20-Poly1305, допустимым public_name и без обязательных расширений (старший бит типа). 0 — выбрана. */
int ech_pick(const uint8_t *list, size_t n, struct ech_cfg *out);

/* HPKE, режим base: зашифровать pt под ключом cfg->pk. info = "tls ech\0" ‖ ECHConfig (RFC 9849, 6.1).
 * eph — закрытый эфемерный ключ (для тестов с известным ответом) или NULL — тогда случайный.
 * enc получает 32 байта (инкапсулированный ключ), ct — pt_n + 16 байт. */
int ech_hpke_seal(const struct ech_cfg *cfg, const uint8_t *eph,
                  const uint8_t *aad, size_t aad_n, const uint8_t *pt, size_t pt_n,
                  uint8_t enc[32], uint8_t *ct);

/* Из готового ClientHello (запись TLS целиком, с заголовком из пяти байт) с настоящим SNI собрать внешний
 * ClientHelloOuter в out (тоже запись целиком) и запомнить Inner в st. 0 — готово. */
int ech_wrap(const struct ech_cfg *cfg, const uint8_t *hello, size_t hello_n,
             uint8_t *out, size_t cap, size_t *out_n, struct ech_state *st);

#endif
