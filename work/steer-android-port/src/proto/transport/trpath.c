/* Путь запроса Upgrade у ws и httpupgrade — как у Xray. Зачем и что принимается — в trpath.h.
 *
 * Здесь повторены четыре кусочка net/url из Go (1.22 — та же логика стоит и в новых выпусках):
 * shouldEscape для пути и для значения запроса, unescape, validEncoded с EscapedPath и
 * url.Values.Encode. Названия функций ниже — их имена, чтобы сверять построчно. Повторены
 * ровно те ветки, по которым проходит путь из настройки узла: хост, пользователь, фрагмент и
 * «непрозрачные» адреса сюда не доходят — такой путь отбраковывается раньше (trpath.h).
 *
 * Как проверено. Перехват запросов настоящего Xray 26.3.27 (клиент в docker, сервер —
 * слушающий сокет): путь `/p/q?x=1&ed=2048` у ws даёт `GET /p/q?x=1`, у httpupgrade —
 * `GET /p/q%3Fx=1`. Остальные случаи (пересборка запроса по алфавиту, `+` вместо пробела,
 * процентные последовательности, путь без ведущего слэша) — стенд tests/wsmatch.c против
 * значений, которые печатает net/url Go 1.22 на тех же входах. */
#include <string.h>
#include <stdint.h>

#include "trpath.h"

static const char HEX[] = "0123456789ABCDEF";

static int is_alnum(unsigned char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
}

static int hexval(unsigned char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* shouldEscape(c, encodePath). Из «зарезервированных» в пути экранируется только `?`: Go
 * оставляет `/ ; ,` ради сегментов пути, а `: @ & = + $` разрешает RFC 3986. Всё прочее, кроме
 * букв, цифр и `- _ . ~`, — экранируется (в том числе `! ' ( ) * [ ]` и всё не-ASCII). */
static int esc_path(unsigned char c) {
    if (is_alnum(c)) return 0;
    switch (c) {
    case '-': case '_': case '.': case '~':
    case '$': case '&': case '+': case ',': case '/': case ':': case ';': case '=': case '@':
        return 0;
    default:
        return 1;
    }
}

/* shouldEscape(c, encodeQueryComponent): в значении запроса экранируется всё, кроме букв, цифр и
 * `- _ . ~` (пробел — особо: он становится `+`, см. put_qesc). */
static int esc_qc(unsigned char c) {
    if (is_alnum(c)) return 0;
    return !(c == '-' || c == '_' || c == '.' || c == '~');
}

struct sb { char *p; size_t n, cap; int over; };

static void put_c(struct sb *b, char c) {
    if (b->n + 1 < b->cap) b->p[b->n++] = c;
    else b->over = 1;
}

static void put_s(struct sb *b, const char *s, size_t n) {
    for (size_t i = 0; i < n; i++) put_c(b, s[i]);
}

static void put_pct(struct sb *b, unsigned char c) {
    put_c(b, '%');
    put_c(b, HEX[c >> 4]);
    put_c(b, HEX[c & 15]);
}

/* unescape(s, mode). qc — режим значения запроса: `+` читается как пробел. Длина раскодированного
 * либо -1: неполная или не шестнадцатеричная последовательность — у Go это ошибка разбора. */
static long unesc(const char *s, size_t n, char *out, size_t cap, int qc) {
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c == '%') {
            if (i + 2 >= n) return -1;
            int h = hexval((unsigned char)s[i + 1]), l = hexval((unsigned char)s[i + 2]);
            if (h < 0 || l < 0) return -1;
            c = (unsigned char)(h * 16 + l);
            i += 2;
        } else if (qc && c == '+') {
            c = ' ';
        }
        if (o + 1 > cap) return -1;
        out[o++] = (char)c;
    }
    return (long)o;
}

/* EscapedPath для пути, разобранного url.Parse: если исходная запись — законное кодирование
 * (validEncoded), она и печатается, как есть; иначе путь раскодируется и кодируется заново. То
 * есть `%41` остаётся `%41`, а пробел становится `%20`. Путь с неполной последовательностью сюда
 * не доходит: его отвергает разбор (tr_upgrade_target). */
static int escaped_path(struct sb *b, const char *p, size_t n) {
    int valid = 1;
    for (size_t i = 0; i < n && valid; i++) {
        unsigned char c = (unsigned char)p[i];
        switch (c) {
        case '!': case '$': case '&': case '\'': case '(': case ')': case '*': case '+':
        case ',': case ';': case '=': case ':': case '@': case '[': case ']': case '%':
            break;
        default:
            if (esc_path(c)) valid = 0;
        }
    }
    if (valid) { put_s(b, p, n); return 0; }
    char tmp[1024];
    long tn = unesc(p, n, tmp, sizeof(tmp), 0);
    if (tn < 0) return -1;
    for (long k = 0; k < tn; k++) {
        unsigned char c = (unsigned char)tmp[k];
        if (esc_path(c)) put_pct(b, c);
        else put_c(b, (char)c);
    }
    return 0;
}

/* QueryEscape. */
static void put_qesc(struct sb *b, const char *s, size_t n) {
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c == ' ') put_c(b, '+');
        else if (esc_qc(c)) put_pct(b, c);
        else put_c(b, (char)c);
    }
}

/* Вырезать ранние данные из запроса — то, что делает Build у Xray:
 *
 *   if q := u.Query(); q.Get("ed") != "" { q.Del("ed"); u.RawQuery = q.Encode() }
 *
 * u.Query() — это ParseQuery с проглоченной ошибкой: пары через `&`, пара с `;` и пара с
 * негодной процентной последовательностью пропускаются, ключ и значение раскодируются (`+` —
 * пробел). Get берёт ПЕРВОЕ значение `ed`: `?ed=` без числа запрос не трогает. Encode печатает
 * ключи по алфавиту (байтово), значения одного ключа — в порядке появления, каждое через
 * QueryEscape. *stripped — вырезано ли; тогда в out новый запрос (может быть пустым). */
#define QP_MAX 64

/* Ed, как его считает Build: `Ed, _ := strconv.Atoi(q.Get("ed")); ed = uint32(Ed)`. Atoi — знак и
 * цифры, ничего больше; ошибка (буквы, пусто после знака, выход за int) — ноль. int у Xray на
 * роутере 32-битный на mips и arm и 64-битный на arm64 и x86_64; повторено 64-битное — отличие
 * только у чисел за 2^31, которых в ссылках не бывает. Отрицательное становится огромным
 * uint32 — у Xray тоже: `ed=-1` значит «ранние данные при любой длине первой записи». */
static uint32_t go_atoi_u32(const char *s, size_t n) {
    size_t i = 0;
    int neg = 0;
    if (i < n && (s[i] == '+' || s[i] == '-')) { neg = s[i] == '-'; i++; }
    if (i == n) return 0;
    uint64_t v = 0;
    for (; i < n; i++) {
        if (s[i] < '0' || s[i] > '9') return 0;
        v = v * 10 + (uint64_t)(s[i] - '0');
        if (v > (uint64_t)INT64_MAX + (uint64_t)neg) return 0;
    }
    int64_t sv = neg ? (int64_t)(0 - v) : (int64_t)v;
    return (uint32_t)sv;
}

static int strip_ed(const char *q, size_t qn, struct sb *out, int *stripped, uint32_t *ed) {
    struct { size_t ko, kl, vo, vl; } pr[QP_MAX];
    size_t np = 0;
    char dec[1024];
    size_t dn = 0;
    *stripped = 0;

    size_t i = 0;
    while (i <= qn) {
        const char *s = q + i;
        const char *amp = memchr(s, '&', qn - i);
        size_t sl = amp ? (size_t)(amp - s) : qn - i;
        i += sl + 1;
        if (!sl || memchr(s, ';', sl)) continue;
        const char *eq = memchr(s, '=', sl);
        size_t kl = eq ? (size_t)(eq - s) : sl;
        if (np >= QP_MAX) return -1;
        long k = unesc(s, kl, dec + dn, sizeof(dec) - dn, 1);
        if (k < 0) continue;
        long v = eq ? unesc(eq + 1, sl - kl - 1, dec + dn + (size_t)k, sizeof(dec) - dn - (size_t)k, 1) : 0;
        if (v < 0) continue;
        pr[np].ko = dn; pr[np].kl = (size_t)k;
        pr[np].vo = dn + (size_t)k; pr[np].vl = (size_t)v;
        dn += (size_t)k + (size_t)v;
        np++;
    }

    size_t first_ed = np;
    for (size_t a = 0; a < np; a++)
        if (pr[a].kl == 2 && !memcmp(dec + pr[a].ko, "ed", 2)) { first_ed = a; break; }
    if (first_ed == np || pr[first_ed].vl == 0) return 0;
    *ed = go_atoi_u32(dec + pr[first_ed].vo, pr[first_ed].vl);

    /* Устойчивая сортировка по ключу вставками: пар единицы, а устойчивость здесь и есть правило
     * Encode (значения одного ключа — в порядке появления). */
    size_t ord[QP_MAX], no = 0;
    for (size_t a = 0; a < np; a++) {
        if (pr[a].kl == 2 && !memcmp(dec + pr[a].ko, "ed", 2)) continue;
        size_t j = no++;
        while (j > 0) {
            size_t p = ord[j - 1];
            size_t m = pr[p].kl < pr[a].kl ? pr[p].kl : pr[a].kl;
            int c = memcmp(dec + pr[p].ko, dec + pr[a].ko, m);
            if (c < 0 || (c == 0 && pr[p].kl <= pr[a].kl)) break;
            ord[j] = p;
            j--;
        }
        ord[j] = a;
    }
    for (size_t j = 0; j < no; j++) {
        size_t a = ord[j];
        if (j) put_c(out, '&');
        put_qesc(out, dec + pr[a].ko, pr[a].kl);
        put_c(out, '=');
        put_qesc(out, dec + pr[a].vo, pr[a].vl);
    }
    *stripped = 1;
    return 0;
}

int tr_upgrade_target(const char *path, int ws, char *out, size_t cap, const char **why) {
    return tr_upgrade_target_ed(path, ws, out, cap, why, NULL);
}

int tr_upgrade_target_ed(const char *path, int ws, char *out, size_t cap, const char **why,
                         uint32_t *ed_out) {
    const char *dummy;
    uint32_t ed_dummy;
    if (!why) why = &dummy;
    if (!ed_out) ed_out = &ed_dummy;
    *why = "";
    *ed_out = 0;
    size_t n = strlen(path);

    /* Что Xray не разобрал бы однозначно — отказ с причиной (см. trpath.h). */
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)path[i];
        if (c < 0x20 || c == 0x7f) { *why = "управляющий знак в path"; return -1; }
        if (c == '#') { *why = "# в path"; return -1; }
    }
    if (n >= 2 && path[0] == '/' && path[1] == '/') { *why = "path начинается с //"; return -1; }
    const char *qm = memchr(path, '?', n);
    size_t pn = qm ? (size_t)(qm - path) : n;
    if (n && path[0] != '/') {
        const char *sl = memchr(path, '/', pn);
        size_t seg = sl ? (size_t)(sl - path) : pn;
        if (memchr(path, ':', seg)) { *why = "path с : без ведущего /"; return -1; }
    }
    char tmp[1024];
    int path_ok = unesc(path, pn, tmp, sizeof(tmp), 0) >= 0;
    /* У ws путь ещё раз разбирает url.Parse в gorilla — и на неполной последовательности
     * отказывает, то есть Xray такой узел не открыл бы никогда. У httpupgrade второго разбора нет:
     * путь уезжает экранированным целиком, и `%zz` становится `%25zz` — узел рабочий. */
    if (!path_ok && ws) { *why = "битый %XX в path"; return -1; }

    /* Шаг Build: ранние данные. Разбор первого шага не удался — путь остаётся как есть, как у
     * Xray (ошибка url.Parse там молча оставляет строку). */
    char p1[1024];
    struct sb b1 = { p1, 0, sizeof(p1), 0 };
    int stripped = 0;
    if (path_ok && qm) {
        char qb[1024];
        struct sb bq = { qb, 0, sizeof(qb), 0 };
        if (strip_ed(qm + 1, n - pn - 1, &bq, &stripped, ed_out) != 0 || bq.over) {
            *why = "path слишком длинный";
            return -1;
        }
        if (stripped) {
            if (escaped_path(&b1, path, pn) != 0) { *why = "битый %XX в path"; return -1; }
            if (bq.n) { put_c(&b1, '?'); put_s(&b1, qb, bq.n); }
        }
    }
    if (!stripped) put_s(&b1, path, n);

    /* GetNormalizedPath: пустой — «/», без ведущего слэша — со слэшем. */
    char p2[1040];
    struct sb b2 = { p2, 0, sizeof(p2), 0 };
    if (!b1.n || p1[0] != '/') put_c(&b2, '/');
    put_s(&b2, p1, b1.n);
    if (b1.over || b2.over) { *why = "path слишком длинный"; return -1; }

    struct sb o = { out, 0, cap, 0 };
    if (ws) {
        /* gorilla: url.Parse("ws://хост" + путь) и RequestURI — EscapedPath и запрос как есть. */
        const char *q2 = memchr(p2, '?', b2.n);
        size_t pn2 = q2 ? (size_t)(q2 - p2) : b2.n;
        if (escaped_path(&o, p2, pn2) != 0) { *why = "битый %XX в path"; return -1; }
        if (q2) put_s(&o, q2, b2.n - pn2);
    } else {
        /* httpupgrade: URL.Path = путь целиком, RequestURI = escape(Path, encodePath). */
        for (size_t i = 0; i < b2.n; i++) {
            unsigned char c = (unsigned char)p2[i];
            if (esc_path(c)) put_pct(&o, c);
            else put_c(&o, (char)c);
        }
    }
    if (o.over || !cap) { *why = "path слишком длинный"; return -1; }
    out[o.n] = '\0';
    return 0;
}
