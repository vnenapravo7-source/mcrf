#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <time.h>
#include "spec.h"
#include "probe.h"

/* ---- подъём выхода vless: ход перебора узлов --------------------------------------------
 *
 * Зачем это вообще есть и почему устаревание обезврежено двумя разными способами — в шапке
 * объявлений в spec.h. Здесь только формат и его разбор.
 *
 * Файл: одна строка «состояние узел всего pid время». Позиционно, а не ключами: строку читают
 * ровно два места в этом же дереве, а лишний разборщик на роутере — лишние байты. Лежит рядом
 * с реестром меток, в state_dir: на OpenWrt это tmpfs, поэтому перебор узлов не пишет во флеш
 * и не переживает перезагрузку — ровно то, чего от него и надо.
 */
#define PROBE_FAILED_TTL 120   /* «ни один не ответил» верно, пока свежо: procd пробует снова
                                * каждые пять секунд, значит запись старше двух минут означает,
                                * что никто больше не пробует. */

static void probe_path(char *buf, size_t n, const char *out_name) {
    snprintf(buf, n, "%s/probe-%.32s", steer_state_dir(), out_name);
}

void probe_report(const char *out_name, enum probe_state st, int node, int total) {
    char path[256];
    probe_path(path, sizeof(path), out_name);
    mkdir(steer_state_dir(), 0755);
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "%s %d %d %ld %ld\n",
            st == PROBE_RUNNING ? "probing" : st == PROBE_NO_SUCH_NODE ? "nonode"
            : st == PROBE_ALL_EXCLUDED ? "excluded" : "failed",
            node, total, (long)getpid(), (long)time(NULL));
    fclose(f);
}

void probe_clear(const char *out_name) {
    char path[256];
    probe_path(path, sizeof(path), out_name);
    unlink(path);
}

/* Источник в памяти демона — см. probe.h. Один на процесс: процесс демона один, и спрашивает
 * его только status, который отвечает в этом же процессе. */
static int (*g_probe_mem)(const char *, struct probe_status *);

void probe_source(int (*fn)(const char *out_name, struct probe_status *st)) {
    g_probe_mem = fn;
}

void probe_mem_escape(const char *why, char *dst, size_t n) {
    static const char hex[] = "0123456789ABCDEF";
    size_t w = 0;
    if (!n) return;
    for (const unsigned char *s = (const unsigned char *)why; *s; s++) {
        int esc = *s <= ' ' || *s == '%' || *s == ':' || *s == 0x7f;
        if (w + (esc ? 3 : 1) >= n) break;
        if (esc) {
            dst[w++] = '%';
            dst[w++] = hex[*s >> 4];
            dst[w++] = hex[*s & 15];
        } else {
            dst[w++] = (char)*s;
        }
    }
    /* Не влезло — не оставлять в конце недописанный знак UTF-8. */
    size_t i = w, k = 0;
    while (i > 0 && k < 4 && ((unsigned char)dst[i - 1] & 0xC0) == 0x80) { i--; k++; }
    if (i > 0 && why[0]) {
        unsigned char lead = (unsigned char)dst[i - 1];
        size_t need = lead >= 0xF0 ? 3 : lead >= 0xE0 ? 2 : lead >= 0xC0 ? 1 : 0;
        if (need != k && lead >= 0xC0) w = i - 1;
    }
    dst[w] = '\0';
}

static int hexv(char c) {
    return c >= '0' && c <= '9' ? c - '0' : c >= 'A' && c <= 'F' ? c - 'A' + 10
         : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
}

/* Обратно к probe_mem_escape; не влезшее обрезается по границе знака. */
static void probe_mem_unescape(const char *s, char *dst, size_t n) {
    size_t w = 0;
    while (*s && w + 1 < n) {
        int a, b;
        if (s[0] == '%' && (a = hexv(s[1])) >= 0 && (b = hexv(s[2])) >= 0) {
            dst[w++] = (char)(a * 16 + b);
            s += 3;
        } else {
            dst[w++] = *s++;
        }
    }
    if (*s) {
        size_t i = w, k = 0;
        while (i > 0 && k < 4 && ((unsigned char)dst[i - 1] & 0xC0) == 0x80) { i--; k++; }
        if (i > 0) {
            unsigned char lead = (unsigned char)dst[i - 1];
            size_t need = lead >= 0xF0 ? 3 : lead >= 0xE0 ? 2 : lead >= 0xC0 ? 1 : 0;
            if (need != k && lead >= 0xC0) w = i - 1;
        }
    }
    dst[w] = '\0';
}

/* Запись выхода out_name в STEER_PROBE_MEM. 1 — нашлась (*st заполнено). */
static int probe_env(const char *out_name, struct probe_status *st) {
    const char *p = getenv("STEER_PROBE_MEM");
    size_t ol = strlen(out_name);
    while (p && *p) {
        while (*p == ' ') p++;
        const char *e = strchr(p, ' ');
        size_t len = e ? (size_t)(e - p) : strlen(p);
        if (len > ol && !strncmp(p, out_name, ol) && p[ol] == ':') {
            char word[16] = "";
            int node = 0, total = 0;
            long since = 0;
            /* Запись lost несёт причину (до 159 байт, в худшем случае каждый втрое длиннее —
             * %XX), поэтому буфер не 96, как хватало трём полям. */
            char rec[640];
            size_t rl = len - ol - 1;
            if (rl >= sizeof(rec)) rl = sizeof(rec) - 1;
            memcpy(rec, p + ol + 1, rl);
            rec[rl] = '\0';
            /* Поля через «:»: состояние, узел, всего, а у lost ещё время и причина. Причина —
             * последняя и своих «:» не содержит (закодированы), так что делится по первым
             * четырём. */
            char *f[5] = { rec, NULL, NULL, NULL, NULL };
            for (int k = 1; k < 5; k++) {
                char *c = strchr(f[k - 1], ':');
                if (!c) break;
                *c = '\0';
                f[k] = c + 1;
            }
            if (!f[2] || sscanf(f[0], "%15s", word) != 1 || sscanf(f[1], "%d", &node) != 1 ||
                sscanf(f[2], "%d", &total) != 1)
                return 0;
            st->node = node;
            st->total = total;
            st->since = 0;
            st->why[0] = '\0';
            st->state = !strcmp(word, "probing") ? PROBE_RUNNING
                      : !strcmp(word, "failed")  ? PROBE_FAILED
                      : !strcmp(word, "nonode")  ? PROBE_NO_SUCH_NODE
                      : !strcmp(word, "excluded") ? PROBE_ALL_EXCLUDED
                      : !strcmp(word, "lost")    ? PROBE_LOST : PROBE_NONE;
            if (st->state == PROBE_NONE) st->node = st->total = 0;
            if (st->state == PROBE_LOST) {
                if (f[3] && sscanf(f[3], "%ld", &since) == 1) st->since = since;
                if (f[4]) probe_mem_unescape(f[4], st->why, sizeof(st->why));
            }
            return 1;
        }
        p = e;
    }
    return 0;
}

struct probe_status probe_read(const char *out_name) {
    struct probe_status out = { PROBE_NONE, 0, 0, 0, "" };
    if (g_probe_mem && g_probe_mem(out_name, &out) == 0) return out;
    if (probe_env(out_name, &out)) return out;
    out = (struct probe_status){ PROBE_NONE, 0, 0, 0, "" };
    char path[256];
    probe_path(path, sizeof(path), out_name);
    FILE *f = fopen(path, "r");
    if (!f) return out;
    char word[16] = "";
    int node = 0, total = 0;
    long pid = 0, at = 0;
    int got = fscanf(f, "%15s %d %d %ld %ld", word, &node, &total, &pid, &at);
    fclose(f);
    if (got != 5) return out;

    if (!strcmp(word, "probing")) {
        /* Верно, только пока жив написавший. Иначе перебор, прерванный на середине (движок
         * остановили, процесс убили), навсегда оставлял бы на экране «проверяю узлы» — то
         * есть обещание работы, которой никто не делает. */
        char proc[64];
        snprintf(proc, sizeof(proc), "/proc/%ld", pid);
        if (pid <= 0 || access(proc, F_OK) != 0) return out;
        out.state = PROBE_RUNNING;
        out.node = node;
        out.total = total;
        return out;
    }
    if (!strcmp(word, "failed") || !strcmp(word, "nonode") || !strcmp(word, "excluded")) {
        /* А это переживает смерть процесса намеренно: клиент выходит с кодом 1 именно потому,
         * что ни один узел не ответил, и приговор нужен ПОСЛЕ него. Живёт, пока свеж.
         *
         * `nonode` — то же по времени жизни и другое по смыслу: номер узла вне подписки.
         * Сохраняется ИМЕННО номер, который написал человек: без него приговор снова
         * превратился бы в «узлов нет», то есть во враньё про чужую подписку. */
        long now = (long)time(NULL);
        if (at <= 0 || now - at > PROBE_FAILED_TTL) return out;
        if (!strcmp(word, "nonode")) {
            out.state = PROBE_NO_SUCH_NODE;
            out.node = node;
        } else if (!strcmp(word, "excluded")) {
            out.state = PROBE_ALL_EXCLUDED;
            out.node = 0;
        } else {
            out.state = PROBE_FAILED;
            out.node = 0;
        }
        out.total = total;
        return out;
    }
    /* Незнакомое слово читается как «не знаем», а не как отказ: запись мог оставить движок
     * другой версии (в /var она переживает подмену бинарника, но не перезагрузку), и жёлтая
     * метка на исправном выходе тут была бы хуже молчания. */
    return out;
}
