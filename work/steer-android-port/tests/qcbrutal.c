/* Brutal (патч к ngtcp2, build/ngtcp2/patches/0001-*.patch) на модели: без сети и без TLS, только
 * колбэки алгоритма перегрузки и состояние соединения, которое он ведёт (шаг 7 выпуска 1.10).
 *
 * ЗАЧЕМ. В qcloop видно, что Brutal подключён и передача идёт; здесь проверяется то, что делает
 * его Brutal: скорость и окно по формулам эталона (hysteria core/internal/congestion/brutal) и
 * — главное — что потери скорость НЕ снижают. Модель задаёт сам стенд: подтверждения и потери
 * подаются тем же колбэкам, которые зовёт ngtcp2_rtb.c, время — числом.
 *
 * Эталон, из которого взяты ожидаемые числа (bps — байт/с):
 *   скорость отправки = bps / ack_rate;  окно = bps * RTT * 2 / ack_rate;
 *   ack_rate — доля подтверждённых пакетов за 5 последних секунд, но не ниже 0.8, и 1.0, пока
 *   пакетов меньше 50.
 * Единица pacing_interval_m в ngtcp2 — наносекунды на байт, умноженные на 1024.
 *
 * Внутренние заголовки ngtcp2 нужны стенду ради ngtcp2_conn_stat: -I на исходники (ext-test даёт).
 *
 *     cc -O2 -w -DHAVE_CONFIG_H -Ibuild/ngtcp2 -I<ngtcp2>/lib -I<ngtcp2>/lib/includes \
 *        -DNGTCP2_STATICLIB -Itests -o build/qcbrutal tests/qcbrutal.c <libngtcp2.a>
 */
#include <stdio.h>
#include <string.h>

#include "ngtcp2_brutal.h"
#include "ngtcp2_conn_stat.h"
#include "unit.h"

#define MTU 1200
#define S NGTCP2_SECONDS
#define MS NGTCP2_MILLISECONDS

static void model_init(ngtcp2_cc_brutal *b, ngtcp2_conn_stat *cs, uint64_t bps) {
    memset(cs, 0, sizeof *cs);
    cs->max_tx_udp_payload_size = MTU;
    cs->first_rtt_sample_ts = UINT64_MAX;
    cs->smoothed_rtt = 333 * MS;
    cs->cwnd = 10 * MTU;
    cs->ssthresh = UINT64_MAX;
    cs->congestion_recovery_start_ts = UINT64_MAX;
    ngtcp2_cc_brutal_init(b, NULL, cs, bps);
}

/* n подтверждений и m потерь в секунде t (мс), затем событие ACK. */
static void feed(ngtcp2_cc_brutal *b, ngtcp2_conn_stat *cs, uint64_t t_ms, int acked, int lost) {
    ngtcp2_cc_pkt pkt = { .pktlen = MTU };
    ngtcp2_cc_ack ack = { 0 };
    for (int i = 0; i < acked; i++) b->cc.on_pkt_acked(&b->cc, cs, &pkt, t_ms * MS);
    for (int i = 0; i < lost; i++) b->cc.on_pkt_lost(&b->cc, cs, &pkt, t_ms * MS);
    b->cc.on_ack_recv(&b->cc, cs, &ack, t_ms * MS);
}

static void rtt(ngtcp2_conn_stat *cs, uint64_t ms) {
    cs->first_rtt_sample_ts = 1;
    cs->smoothed_rtt = ms * MS;
}

int main(void) {
    ngtcp2_cc_brutal b;
    ngtcp2_conn_stat cs;
    const uint64_t BPS = 12500000;      /* 100 Мбит/с */

    /* ---- начало: скорость задана сразу, окно — начальное, пока нет RTT ----------------------- */
    model_init(&b, &cs, BPS);
    check("старт: ack_rate 1.0", 1000, (long)b.ack_rate_pm);
    check("старт: пейсинг = 81920 (нс/байт << 10 при 12.5 МБ/с)", 81920, (long)cs.pacing_interval_m);
    check("старт: окно — начальное (10 пакетов), RTT не измерен", 10 * MTU, (long)cs.cwnd);
    check("старт: пачка — 12500 байт (миллисекунда)", 12500, (long)cs.send_quantum);

    /* ---- RTT 100 мс: окно = bps * RTT * 2 ------------------------------------------------------ */
    rtt(&cs, 100);
    feed(&b, &cs, 1000, 10, 0);
    check("RTT 100 мс: окно = 12.5 МБ/с * 0.1 с * 2 = 2 500 000", 2500000, (long)cs.cwnd);
    check("RTT 100 мс: пейсинг прежний", 81920, (long)cs.pacing_interval_m);

    /* ---- меньше 50 пакетов за окно — поправки нет, даже если половина потеряна --------------- */
    feed(&b, &cs, 1100, 5, 30);
    check("35 потерь из 45 пакетов (< 50): ack_rate остался 1.0", 1000, (long)b.ack_rate_pm);

    /* ---- 100 подтверждено, 100 потеряно: 50% → зажим до 0.8; окно и скорость растут ----------- */
    feed(&b, &cs, 1200, 100, 70);
    check("потери 50%: ack_rate зажат до 0.8 (800 промилле)", 800, (long)b.ack_rate_pm);
    check("потери 50%: пейсинг 65536 (скорость выросла в 1.25 раза: 15.6 МБ/с)", 65536,
          (long)cs.pacing_interval_m);
    check("потери 50%: окно 3 125 000 (bps * RTT * 2 / 0.8)", 3125000, (long)cs.cwnd);
    check("потери 50%: окно НЕ упало ниже начального значения", 1, cs.cwnd >= 2500000);

    /* ---- 10% потерь: ack_rate 0.9 -------------------------------------------------------------- */
    model_init(&b, &cs, BPS);
    rtt(&cs, 100);
    feed(&b, &cs, 5000, 900, 100);
    check("потери 10%: ack_rate 900 промилле", 900, (long)b.ack_rate_pm);
    check("потери 10%: пейсинг = 81920 * 0.9 = 73728", 73728, (long)cs.pacing_interval_m);
    check("потери 10%: окно = 2 500 000 / 0.9 = 2 777 777", 2777777, (long)cs.cwnd);

    /* ---- главное свойство Brutal: потери скорость не снижают ---------------------------------- */
    {
        ngtcp2_cc_ack ack = { .bytes_lost = 100000 };
        uint64_t cw = cs.cwnd, pi = cs.pacing_interval_m;
        check("нет реакции на congestion_event: колбэк задан только для пересчёта ack_rate", 1,
              b.cc.congestion_event != NULL);
        b.cc.congestion_event(&b.cc, &cs, 5000 * MS, &ack, 5001 * MS);
        check("congestion_event: окно не режется", 1, cs.cwnd >= cw);
        check("congestion_event: скорость не падает (интервал не растёт)", 1, cs.pacing_interval_m <= pi);
        check("нет on_persistent_congestion (окно не схлопывается)", 1, b.cc.on_persistent_congestion == NULL);
        check("нет on_spurious_congestion", 1, b.cc.on_spurious_congestion == NULL);
        check("ssthresh не тронут (нет выхода из slow start)", 1, cs.ssthresh == UINT64_MAX);
        check("recovery не объявлялся", 1, cs.congestion_recovery_start_ts == UINT64_MAX);
    }

    /* ---- слоты по секундам: окно памяти пять секунд ------------------------------------------- */
    model_init(&b, &cs, BPS);
    rtt(&cs, 100);
    feed(&b, &cs, 10000, 50, 50);
    check("50/50 в момент 10 с: ack_rate 800", 800, (long)b.ack_rate_pm);
    feed(&b, &cs, 13000, 10, 0);
    check("через 3 с память держит потери (60 из 110 → 545 → зажим 800)", 800, (long)b.ack_rate_pm);
    feed(&b, &cs, 17000, 60, 0);
    check("через 7 с слот с потерями вышел из окна: 70 подтверждений, потерь нет → 1.0", 1000,
          (long)b.ack_rate_pm);
    feed(&b, &cs, 40000, 200, 0);
    check("через 30 с только подтверждения: ack_rate 1.0", 1000, (long)b.ack_rate_pm);

    /* ---- reset (смена пути): счёт сбрасывается, скорость прежняя ----------------------------- */
    model_init(&b, &cs, BPS);
    rtt(&cs, 100);
    feed(&b, &cs, 2000, 100, 100);
    check("перед reset: ack_rate 800", 800, (long)b.ack_rate_pm);
    b.cc.reset(&b.cc, &cs, 3000 * MS);
    check("reset: ack_rate 1.0", 1000, (long)b.ack_rate_pm);
    check("reset: скорость на месте", 81920, (long)cs.pacing_interval_m);

    /* ---- границы -------------------------------------------------------------------------------- */
    model_init(&b, &cs, 0);
    check("bps = 0 не даёт деления на нуль: зажат до 1 байт/с", 1, (long)b.bps);
    model_init(&b, &cs, 1250000000ULL);     /* 10 Гбит/с */
    rtt(&cs, 300);
    feed(&b, &cs, 1000, 10, 0);
    check("10 Гбит/с, RTT 300 мс: окно 750 000 000 (без переполнения)", 750000000, (long)cs.cwnd);
    check("10 Гбит/с: пачка ограничена 64 КиБ", 65536, (long)cs.send_quantum);
    check("10 Гбит/с: пейсинг 819 (нс/байт << 10)", 819, (long)cs.pacing_interval_m);
    model_init(&b, &cs, 1000);              /* 8 кбит/с */
    rtt(&cs, 50);
    feed(&b, &cs, 1000, 10, 0);
    check("8 кбит/с: окно не меньше одного пакета", MTU, (long)cs.cwnd);
    check("8 кбит/с: пачка — не меньше десяти пакетов", 10 * MTU, (long)cs.send_quantum);

    /* ---- длинный прогон: за полсекунды время, потери 5% — скорость отправки не меньше bps ---- */
    {
        model_init(&b, &cs, BPS);
        rtt(&cs, 40);
        for (int sec = 0; sec < 8; sec++)
            feed(&b, &cs, (uint64_t)sec * 1000 + 500, 950, 50);
        /* скорость отправки, байт/с = 1e9 * 1024 / pacing_interval_m */
        uint64_t rate = (uint64_t)1000000000ULL * 1024 / cs.pacing_interval_m;
        check("потери 5% за 8 секунд: ack_rate 950", 950, (long)b.ack_rate_pm);
        check("потери 5%: скорость отправки не ниже заданной", 1, rate >= BPS);
        check("потери 5%: и не выше заданной более чем на 6% (поправка на потери)", 1,
              rate <= BPS * 106 / 100);
    }
    return unit_done("qcbrutal");
}
