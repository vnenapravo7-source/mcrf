/* Разбор адреса проверки задержки группы — устройство и доводы в grpurl.h. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "grpurl.h"

/* Определён в src/proto/tls/urltls.c — только там, где замер задержки группы действительно
 * ведётся (steerd и полный статический движок). Слабая ссылка: в сборке без него адрес символа —
 * ноль, и это и есть ответ «HTTPS нет». */
extern const int steer_urltls_present __attribute__((weak));

/* Процесс, который спеку только читает (модуль: steer-vless, steer-xsteer, steer-hysteria2, steer-proxy, obfs,
 * tgws). Замер групп ведёт один steerd, и есть ли в системе HTTPS для замера, решает он: символ
 * steer_urltls_present в бинарник модуля не попадает (urltls.c нужен только замеру), и слабая ссылка
 * там всегда нулевая — не потому, что TLS в системе нет, а потому, что модулю он ни к чему. Раньше
 * разбор в модуле на этом отказывал: спека с https-группой роняла каждый модуль кодом 2, а
 * steerd её при этом принимал. Свойство «https:// доступен» — свойство системы, не того бинарника,
 * где идёт разбор, поэтому читатель отказ не выносит, а отказывает тот, кто мерит. */
static int g_reader_only;

void urltest_reader_only(void) {
    g_reader_only = 1;
}

int urltest_https_ok(void) {
    return &steer_urltls_present != NULL && steer_urltls_present;
}

int urltest_https_refused(void) {
    return !g_reader_only && !urltest_https_ok();
}

static int fail(char *why, size_t wn, const char *msg) {
    if (why && wn) snprintf(why, wn, "%s", msg);
    return -1;
}

int urltest_url_parse(const char *url, struct urltest_url *u, char *why, size_t wn) {
    memset(u, 0, sizeof(*u));
    if (!url || !*url) return fail(why, wn, "пустой адрес");
    size_t len = strlen(url);
    if (len >= GROUP_URL_MAX) return fail(why, wn, "адрес длиннее 191 байта");
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char)url[i];
        if (c <= 0x20 || c == 0x7f || c == '"' || c == '\'' || c == '\\' || c >= 0x80)
            return fail(why, wn, "в адресе пробел, кавычка или недопустимый знак");
    }
    const char *p;
    if (!strncmp(url, "http://", 7)) { u->https = 0; p = url + 7; u->port = 80; }
    else if (!strncmp(url, "https://", 8)) { u->https = 1; p = url + 8; u->port = 443; }
    else return fail(why, wn, "адрес начинается не с http:// или https://");

    if (*p == '[') return fail(why, wn, "адрес IPv6 — ещё не поддерживается, нужно имя или IPv4");
    size_t hl = strcspn(p, ":/?#");
    if (hl == 0) return fail(why, wn, "в адресе нет хоста");
    if (hl >= sizeof(u->host)) return fail(why, wn, "имя хоста длиннее 127 байт");
    for (size_t i = 0; i < hl; i++) {
        char c = p[i];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
              c == '-' || c == '.' || c == '_'))
            return fail(why, wn, "в имени хоста недопустимый знак");
    }
    memcpy(u->host, p, hl);
    u->host[hl] = '\0';
    p += hl;
    if (*p == ':') {
        p++;
        size_t dl = strspn(p, "0123456789");
        if (dl == 0 || dl > 5 || (p[dl] && p[dl] != '/' && p[dl] != '?'))
            return fail(why, wn, "порт — число 1..65535");
        long v = strtol(p, NULL, 10);
        if (v < 1 || v > 65535) return fail(why, wn, "порт — число 1..65535");
        u->port = (unsigned short)v;
        p += dl;
    }
    if (*p == '#') return fail(why, wn, "якорь (#) в адресе проверки не нужен");
    if (!*p) {
        snprintf(u->path, sizeof(u->path), "/");
    } else if (*p == '/') {
        if (strchr(p, '#')) return fail(why, wn, "якорь (#) в адресе проверки не нужен");
        if (strlen(p) >= sizeof(u->path)) return fail(why, wn, "путь длиннее 159 байт");
        snprintf(u->path, sizeof(u->path), "%s", p);
    } else if (*p == '?') {
        if (strlen(p) + 1 >= sizeof(u->path)) return fail(why, wn, "путь длиннее 159 байт");
        snprintf(u->path, sizeof(u->path), "/%s", p);
    } else {
        return fail(why, wn, "адрес не разобрать");
    }
    return 0;
}
