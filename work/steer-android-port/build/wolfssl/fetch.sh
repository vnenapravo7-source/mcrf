#!/bin/sh
# Исходники wolfSSL для сборки движка: скачать выпуск, СВЕРИТЬ СУММУ, распаковать.
#
#     sh build/wolfssl/fetch.sh <каталог>
#
# ВЕРСИЯ И СУММА ЗАПИСАНЫ ЗДЕСЬ И ТОЛЬКО ЗДЕСЬ. Этим же скриптом берут исходники образ сборки
# (build/Dockerfile копирует его и зовёт), стенды на настоящей библиотеке (tests/ext-test.sh) и
# проверка сборки под телефон (make ndk-check), поэтому версия у всех одна, а сменить её — это
# правка двух строк ниже, одним коммитом. Сверяет это tests/buildmatch.sh.
#
# ПОЧЕМУ СКАЧИВАНИЕ, А НЕ ИСХОДНИКИ В ДЕРЕВЕ. Выпуск wolfSSL — 35 МБ и больше тысячи файлов, из
# которых сборке нужно тридцать с небольшим (build/wolfssl/build.sh); класть их в src/third_party
# значило бы тащить чужой код в каждый клон и в каждую правку истории ради того, что проверяется
# одной суммой. Так же прежде приезжала mbedtls — в образ, со сверкой суммы (I-033).
#
# ПОЧЕМУ ИМЕННО ЭТА СУММА. Тарбол — архив тега выпуска на GitHub (так же его берёт пакет OpenWrt,
# package/libs/wolfssl), и wolfSSL подписывает именно его: рядом с выпуском лежит
# wolfssl-<версия>.tar.gz.asc. Подпись проверена ключом wolfSSL (A2A4 8E7B CB96 C5BE CB98 7314
# EBC8 0E41 5CA2 9677, «wolfSSL <secure@wolfssl.com>») при записи суммы сюда; сборка дальше
# сверяет сумму, а не подпись: gpg и сеть к серверу ключей в образе и на машине сборки не нужны.
#
# 5.9.4 — последний стабильный выпуск (25 сентября 2026); в его заметках (ChangeLog.md) закрыты
# шесть CVE, найденных в 5.9.2 и старше (CVE-2026-93302, -89102, -89136, -93304, -89133, -89134).
# Нашей конфигурации касаются не все, но выбирать выпуск с известными дырами незачем.
set -eu

WOLFSSL_VERSION=5.9.4
WOLFSSL_SHA256=7256bfc89b183a75183806c7debfa203443873b0b4a562e1b80d68e01b45ac57
URL="https://github.com/wolfSSL/wolfssl/archive/refs/tags/v${WOLFSSL_VERSION}-stable.tar.gz"

[ "${1:-}" = version ] && { echo "$WOLFSSL_VERSION"; exit 0; }
DEST="${1:?нужен каталог, куда распаковать исходники}"
if [ -f "$DEST/wolfssl/wolfcrypt/settings.h" ] && [ "$(cat "$DEST/.steer-wolfssl" 2>/dev/null)" = "$WOLFSSL_SHA256" ]; then
    exit 0
fi

mkdir -p "$DEST"
TGZ="$DEST.tar.gz"
# Готовый тарбол можно подложить (WOLFSSL_TARBALL) — сборка без сети; сверяется он так же.
if [ -n "${WOLFSSL_TARBALL:-}" ]; then
    TGZ="$WOLFSSL_TARBALL"
elif ! curl -fsSL -o "$TGZ" "$URL"; then
    echo "wolfssl: не скачать $URL" >&2
    rm -f "$TGZ"
    exit 1
fi
if ! echo "$WOLFSSL_SHA256  $TGZ" | sha256sum -c - >/dev/null 2>&1; then
    echo "wolfssl: сумма тарбола $TGZ не сошлась (ждали $WOLFSSL_SHA256) — не распаковываю" >&2
    [ -n "${WOLFSSL_TARBALL:-}" ] || rm -f "$TGZ"
    exit 1
fi
# Распаковка — в соседний каталог и подмена целиком: исходники прежней версии в том же месте
# иначе смешались бы с новыми (удалённый в новом выпуске файл остался бы и собрался).
NEW="$DEST.new"
rm -rf "$NEW"
mkdir -p "$NEW"
tar xzf "$TGZ" -C "$NEW" --strip-components=1
[ -n "${WOLFSSL_TARBALL:-}" ] || rm -f "$TGZ"
[ -f "$NEW/wolfssl/wolfcrypt/settings.h" ] || { echo "wolfssl: в тарболе нет исходников" >&2; exit 1; }
# Метка — сумма тарбола: исходники другой версии в том же каталоге будут заменены, а не взяты.
echo "$WOLFSSL_SHA256" > "$NEW/.steer-wolfssl"
rm -rf "$DEST"
mv "$NEW" "$DEST"
echo "wolfssl: исходники $WOLFSSL_VERSION в $DEST (sha256 сошлась)"
