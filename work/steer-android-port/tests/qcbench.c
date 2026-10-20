/* Клиент замера передачи QUIC (шаг 7 выпуска 1.10): льёт данные в один поток заданное время и
 * печатает, что видит клиент. Пара к tests/qcserver.c; запускает обоих tests/qcbench.sh.
 *
 *     qcbench HOST PORT СЕК [brutal БАЙТ/С | cubic] [insecure]
 *
 * Клиент — та же обёртка src/proto/quic, что войдёт в движок, а не отдельный код замера, поэтому
 * цифры здесь — цифры настоящего пути отправки (пачки, пейсинг, колбэки). Данные — нули: шифруются
 * они так же, как любые.
 *
 * Печатает: «client sent=N lost=M pkts=K cwnd=W rtt_us=R brutal=B» после завершения.
 *
 *     cc -O2 -w $(make -s print-inc) -Itests -o build/qcbench tests/qcbench.c \
 *        src/proto/quic/quic.c src/proto/quic/qcssl.c <ngtcp2.a> <wolfssl.a> -lpthread
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quic.h"
#include "qccert.h"

static int g_hs, g_closed, g_replied;

static uint64_t ms_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static void on_hs(void *u) { (void)u; g_hs = 1; }
static void on_stream(void *u, int64_t sid, const uint8_t *d, size_t n, int fin) {
    (void)u; (void)sid; (void)d;
    if (n && fin) g_replied = 1;
}
static void on_closed(void *u, int reason, const char *why) {
    (void)u;
    fprintf(stderr, "qcbench: закрыто (%d, %s)\n", reason, why ? why : "");
    g_closed = 1;
}

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "qcbench HOST PORT СЕК [brutal БАЙТ/С | cubic] [insecure]\n"); return 2; }
    struct qc_cfg cfg = {
        .host = argv[1], .port = (uint16_t)atoi(argv[2]), .alpn = "qcecho", .sni = "localhost",
        .ca_pem = (const uint8_t *)qc_cert_pem, .ca_pem_n = sizeof qc_cert_pem - 1,
        .max_data = 64u << 20, .max_stream_data = 32u << 20, .send_buf = 8u << 20,
        .idle_ms = 60000, .handshake_ms = 10000,
    };
    unsigned secs = (unsigned)atoi(argv[3]);
    if (argc > 5 && !strcmp(argv[4], "brutal")) cfg.brutal_bps = strtoull(argv[5], NULL, 10);
    struct qc_ops ops = { on_hs, on_stream, NULL, NULL, on_closed };
    struct qc *q;
    if (qc_open(&cfg, &ops, NULL, &q) != 0) { fprintf(stderr, "qcbench: не открылось\n"); return 1; }
    uint64_t end_hs = ms_now() + 10000;
    while (!g_hs && !g_closed && ms_now() < end_hs) qc_run(q, 5);
    if (!g_hs) { fprintf(stderr, "qcbench: рукопожатие не состоялось\n"); return 1; }
    int64_t sid;
    if (qc_stream_open(q, &sid) != 0) return 1;
    static uint8_t chunk[64 * 1024];
    uint64_t t0 = ms_now(), end = t0 + secs * 1000ull, total = 0;
    while (ms_now() < end && !g_closed) {
        ssize_t w;
        while ((w = qc_stream_send(q, sid, chunk, sizeof chunk, 0)) > 0) {
            total += (uint64_t)w;
            if ((size_t)w < sizeof chunk) break;
        }
        qc_run(q, 1);
    }
    /* Закончить передачу и дождаться ответа сервера (он приходит после всех данных). */
    uint64_t wait_end = ms_now() + 20000;
    while (qc_stream_send(q, sid, chunk, 0, 1) < 0 && ms_now() < wait_end) qc_run(q, 1);
    while (!g_replied && !g_closed && ms_now() < wait_end) qc_run(q, 5);
    struct qc_stats st;
    qc_stats_get(q, &st);
    printf("client queued=%llu sent=%llu lost=%llu pkts=%llu cwnd=%llu rtt_us=%llu brutal=%d replied=%d\n",
           (unsigned long long)total, (unsigned long long)st.bytes_sent, (unsigned long long)st.pkt_lost,
           (unsigned long long)st.pkt_sent, (unsigned long long)st.cwnd, (unsigned long long)st.rtt_us,
           st.brutal, g_replied);
    qc_close(q, 0);
    qc_free(q);
    return g_replied ? 0 : 1;
}
