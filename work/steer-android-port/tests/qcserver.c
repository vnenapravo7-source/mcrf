/* Сервер QUIC для замеров и сверки клиента (шаг 7 выпуска 1.10): принимает одно соединение,
 * считает байты в потоках и эхом возвращает датаграммы. Собирается на той же обёртке, что и клиент
 * (QC_WITH_SERVER), поэтому проверяет её обе роли, а с tests/qcbench.c и tests/qcbench.sh —
 * скорость Brutal против CUBIC на канале с потерями (ip netns + netem).
 *
 *     qcserver [--port P] [--bind ADDR] [--brutal БАЙТ/С] [--dg МАКС] [--idle СЕК]
 *
 * Печатает на stdout: «listening PORT» (когда готов), раз в секунду «t=N bytes=M mbit=X» по
 * принятому в потоках, и в конце «total bytes=N secs=S mbit=X». Поток закрывается ответом, когда
 * клиент закончил передачу (fin): байт «K». Сертификат — tests/qccert.h.
 *
 *     cc -O2 -w -DQC_WITH_SERVER $(make -s print-inc) -Itests -o build/qcserver tests/qcserver.c \
 *        src/proto/quic/quic.c src/proto/quic/qcssl.c <ngtcp2.a> <wolfssl.a> -lpthread
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quic.h"
#include "qccert.h"

static struct qc *g_q;
static int g_closed;
static uint64_t g_bytes, g_first_ms, g_last_ms;

static uint64_t ms_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static void on_stream(void *u, int64_t sid, const uint8_t *d, size_t n, int fin) {
    (void)u; (void)d;
    if (n && !g_first_ms) g_first_ms = ms_now();
    g_bytes += n;
    if (n) g_last_ms = ms_now();
    if (fin) {
        uint8_t k = 'K';
        qc_stream_send(g_q, sid, &k, 1, 1);
    }
}
static void on_dg(void *u, const uint8_t *d, size_t n) {
    (void)u;
    (void)qc_datagram_send(g_q, d, n);
}
static void on_closed(void *u, int reason, const char *why) {
    (void)u;
    fprintf(stderr, "qcserver: соединение закрыто (%d, %s)\n", reason, why ? why : "");
    g_closed = 1;
}

int main(int argc, char **argv) {
    struct qc_srv_cfg sc = {
        .bind_host = "127.0.0.1", .alpn = "qcecho",
        .cert_der = qc_cert_der, .cert_n = sizeof qc_cert_der,
        .key_der = qc_key_der, .key_n = sizeof qc_key_der,
        .max_data = 64u << 20, .max_stream_data = 32u << 20,
    };
    unsigned idle = 30;
    for (int i = 1; i + 1 < argc; i += 2) {
        if (!strcmp(argv[i], "--port")) sc.port = (uint16_t)atoi(argv[i + 1]);
        else if (!strcmp(argv[i], "--bind")) sc.bind_host = argv[i + 1];
        else if (!strcmp(argv[i], "--brutal")) sc.brutal_bps = strtoull(argv[i + 1], NULL, 10);
        else if (!strcmp(argv[i], "--dg")) sc.datagram_max = (size_t)atoi(argv[i + 1]);
        else if (!strcmp(argv[i], "--idle")) idle = (unsigned)atoi(argv[i + 1]);
        else { fprintf(stderr, "qcserver: неизвестный ключ %s\n", argv[i]); return 2; }
    }
    struct qc_ops ops = { NULL, on_stream, NULL, on_dg, on_closed };
    if (qc_listen(&sc, &ops, NULL, &g_q) != 0) { fprintf(stderr, "qcserver: не поднялся\n"); return 1; }
    printf("listening %u\n", qc_local_port(g_q));
    fflush(stdout);
    uint64_t t0 = ms_now(), next = t0 + 1000, quiet = t0;
    uint64_t seen = 0;
    while (!g_closed && ms_now() - quiet < idle * 1000ull) {
        qc_run(g_q, 5);
        uint64_t now = ms_now();
        if (g_bytes != seen) { seen = g_bytes; quiet = now; }
        if (now >= next && g_bytes) {
            static uint64_t prev, tsec;
            tsec++;
            printf("t=%llu bytes=%llu mbit=%.1f\n", (unsigned long long)tsec, (unsigned long long)g_bytes,
                   (double)(g_bytes - prev) * 8 / 1e6);
            fflush(stdout);
            prev = g_bytes;
            next += 1000;
        } else if (now >= next) {
            next = now + 1000;
        }
    }
    double secs = g_last_ms > g_first_ms ? (double)(g_last_ms - g_first_ms) / 1000.0 : 0;
    printf("total bytes=%llu secs=%.2f mbit=%.1f\n", (unsigned long long)g_bytes, secs,
           secs > 0 ? (double)g_bytes * 8 / 1e6 / secs : 0.0);
    fflush(stdout);
    qc_free(g_q);
    return 0;
}
