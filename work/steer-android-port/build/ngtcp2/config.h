/* config.h для ngtcp2 в сборке steer — вместо порождаемого configure.
 *
 * ngtcp2 собирается нашим рецептом (build/ngtcp2/build.sh: список .c и ключи, без autotools),
 * поэтому config.h пишется здесь один раз и на все цели: девять архитектур роутера (musl, zig) и
 * телефон (bionic, NDK). Ключи configure, которых нет ниже, ngtcp2 обходит переносимым кодом:
 *
 *   HAVE_DECL_BE64TOH, HAVE_DECL_BSWAP_64  не заданы: 64-битный порядок байт считается через
 *                       ntohl двух половин (lib/ngtcp2_net.h), работает везде, на mips и arm — тоже;
 *   HAVE_EXPLICIT_BZERO, HAVE_MEMSET_S  не заданы: обнуление ключей через volatile-указатель на
 *                       memset (lib/ngtcp2_str.c) — на bionic и musl explicit_bzero есть, но
 *                       ради одной функции ключ, зависящий от версии libc, не нужен;
 *   HAVE_BYTESWAP_H, HAVE_ENDIAN_H, HAVE_SYS_ENDIAN_H  не заданы по той же причине.
 *
 * Порядок байт берётся у компилятора: mips (не mipsel) — big-endian, и WORDS_BIGENDIAN на нём
 * обязателен, иначе ngtcp2_ntohl64 поменяет байты там, где их менять нельзя.
 */
#ifndef STEER_NGTCP2_CONFIG_H
#define STEER_NGTCP2_CONFIG_H

#define HAVE_ARPA_INET_H 1
#define HAVE_NETINET_IN_H 1
#define HAVE_UNISTD_H 1

#if defined(__BYTE_ORDER__) && defined(__ORDER_BIG_ENDIAN__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
#define WORDS_BIGENDIAN 1
#endif

#endif
