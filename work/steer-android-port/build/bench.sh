#!/bin/sh
# Собрать замер шифров (tests/xsbench.c) и векторы слоя (tests/scryptomatch.c) под цель — тем же
# zig, той же wolfSSL и с теми же опциями, что расширенная сборка (build/build-ext.sh). Запускается
# в образе сборки:
#
#     docker run --rm -v "$PWD:/src" -w /src --entrypoint sh steer-builder:wolfssl \
#         /src/build/bench.sh aarch64-linux-musl cortex_a53 /src/build/bench
#
# Получится <выход>-xsbench-* и <выход>-scryptomatch-* — статические бинарники, которые копируются
# на роутер (или гоняются под qemu-user) как есть. Векторы едут вместе с замером нарочно: скорость
# ассемблерного пути, который на этом процессоре считает неверно, ничего не стоит, а проверить
# ответ можно только на том же железе, где мерили.
#
# ДВА ВАРИАНТА на aarch64 и x86_64 — с ускорением (ассемблер wolfSSL) и без него (переносимый C,
# STEER_WOLFSSL_NO_ARMASM / без STEER_WOLFSSL_ASM): вопрос стоит именно так — что даёт ускорение
# на этом железе. Прежде здесь было три варианта конфигурации mbedtls (без AES-NI/AESCE, с ним, с
# большой таблицей GHASH); вместе с mbedtls сняты и они.
set -eu
TARGET="${1:-aarch64-linux-musl}"
MCPU="${2:-cortex_a53}"
OUT="${3:-/src/build/bench}"
WOLFSSL_DIR="${WOLFSSL_DIR:-/opt/wolfssl}"

export SOURCES_MK=/src/build/sources.mk
. /src/build/sources.sh
STEER_INC="$(for d in $(profile_var INC_DIRS); do printf -- '-I/src/%s ' "$d"; done)"

build() {  # СУФФИКС asm|noasm ДОП_КЛЮЧИ_БИБЛИОТЕКИ
    suffix="$1"; asm="$2"; defs="$3"
    tag=$(echo "$TARGET$MCPU$suffix" | tr -c 'a-zA-Z0-9' '_')
    lib="/src/build/wolfssl-obj/bench-$tag/libwolfssl.a"
    # shellcheck disable=SC2086
    CC="zig cc -target $TARGET ${MCPU:+-mcpu=$MCPU}" AR="zig ar" CFLAGS="-O2 -ffunction-sections -fdata-sections" \
        STEER_WOLFSSL_DEFS="$defs" sh /src/build/wolfssl/build.sh "$WOLFSSL_DIR" "$lib" "$asm"
    wcf=$(cat "$lib.cflags")
    # shellcheck disable=SC2086
    zig cc -target "$TARGET" ${MCPU:+-mcpu=$MCPU} -static -O2 -s -Wl,--gc-sections $STEER_INC $wcf \
        -o "$OUT-xsbench-$suffix" /src/tests/xsbench.c /src/src/proto/xsteer/xswire.c \
        /src/src/proto/tls/reality.c /src/src/proto/tls/certverify.c /src/src/lib/evline.c \
        /src/src/lib/jsonw.c /src/src/lib/scrypto.c "$lib"
    # shellcheck disable=SC2086
    zig cc -target "$TARGET" ${MCPU:+-mcpu=$MCPU} -static -O2 -s -Wl,--gc-sections $STEER_INC \
        -I/src/tests $wcf -o "$OUT-scryptomatch-$suffix" /src/tests/scryptomatch.c \
        /src/src/lib/scrypto.c "$lib"
}

build asm asm ""
case "$TARGET" in
    aarch64-*) build noasm asm "-DSTEER_WOLFSSL_NO_ARMASM" ;;
    x86_64-*) build noasm noasm "" ;;
esac
ls -la "$OUT"-*
