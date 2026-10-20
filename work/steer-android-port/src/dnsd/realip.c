#include "dnsd_int.h"

/* ---- real-ip: память выданных элементов со сроком -------------------------------------------
 *
 * ЗАЧЕМ. Канал в режиме realip кладёт в свой набор (и в парный «<набор>6») НАСТОЯЩИЕ адреса из
 * ответа DNS — элементом со сроком, равным TTL записи (proxy.c, ветка real-IP). apply, меняющий
 * набор правил, заменяет таблицу nftables целиком (src/daemon/apply.c, «ЗАМЕНА ТАБЛИЦЫ»), и
 * наборы каналов приходят в ней пустыми. Постоянные элементы fake-IP резолвер возвращает сразу
 * (fakeip_rehydrate, df46f82), а элементы real-ip возвращались только со следующим ответом DNS
 * на это имя: клиент с адресом в кэше до конца TTL шёл напрямую, мимо выхода канала. Проверка
 * 1.9 на QEMU-роутере.
 *
 * ЧТО ХРАНИТСЯ. По записи на (имя, семейство, адрес): имя нижним регистром — по нему после новой
 * таблицы заново выводятся каналы (как у fake-IP: набор каналов и его имена могли смениться, и
 * помнить номер канала бессмысленно) и сужение составного набора (dch_add, dch_boxes); адрес;
 * монотонный срок. Проход после таблицы ставит каждый живой элемент в каждый совпавший канал
 * real-ip с ОСТАВШИМСЯ сроком — ровно столько, сколько клиент ещё держит ответ в кэше. Элемент,
 * уцелевший в ядре, от повторной вставки не страдает.
 *
 * ГДЕ. Только в памяти, не на диске: срок — минуты, запись на флеш ради них — дороже пользы, а
 * перезапуск резолвера (stop службы) снимает таблицу nftables вместе с наборами всё равно.
 * Демон, упав, резолвер не уносит (adopt.c), поэтому память переживает и его перезапуск.
 *
 * ПРЕДЕЛ — REALIP_MAX записей: проход после таблицы стоит транзакцию nf_tables на запись и
 * канал, и на роутерном MIPS 2048 записей — доли секунды. Когда места нет, сначала уходят
 * истёкшие, затем та, что истекает раньше всех: её клиенты потеряют меньше всего. */
#define REALIP_MAX 2048

struct realip_ent {
    char *domain;
    uint32_t hash;
    uint8_t v6;
    uint8_t addr[16];           /* IPv4 — первые 4 байта, порядок сети */
    time_t expires;             /* CLOCK_MONOTONIC, секунды */
};

static struct realip_ent *g_ri;
static size_t g_ri_n, g_ri_cap;

static time_t ri_mono(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec;
}

static void ri_drop(size_t i) {
    free(g_ri[i].domain);
    g_ri[i] = g_ri[--g_ri_n];
}

static void ri_purge(time_t now) {
    for (size_t i = 0; i < g_ri_n;)
        if (g_ri[i].expires <= now) ri_drop(i);
        else i++;
}

/* Запомнить элемент (qname — уже нижним регистром, proxy.c). ttl — тот, с которым элемент лёг
 * в набор (set_ttl_clamp). Повторный ответ продлевает срок — клиент получил тот же адрес заново. */
static void ri_note(const char *qname, int v6, const uint8_t *addr, uint32_t ttl) {
    size_t alen = v6 ? 16 : 4;
    uint32_t h = sidx_hash(qname);
    time_t now = ri_mono();
    time_t exp = now + (time_t)ttl;
    for (size_t i = 0; i < g_ri_n; i++) {
        struct realip_ent *e = &g_ri[i];
        if (e->hash == h && e->v6 == v6 && !memcmp(e->addr, addr, alen) &&
            !strcmp(e->domain, qname)) {
            if (exp > e->expires) e->expires = exp;
            return;
        }
    }
    if (g_ri_n >= REALIP_MAX) ri_purge(now);
    if (g_ri_n >= REALIP_MAX) {
        size_t k = 0;
        for (size_t i = 1; i < g_ri_n; i++)
            if (g_ri[i].expires < g_ri[k].expires) k = i;
        ri_drop(k);
    }
    if (g_ri_n == g_ri_cap) {
        size_t c = g_ri_cap ? g_ri_cap * 2 : 64;
        if (c > REALIP_MAX) c = REALIP_MAX;
        struct realip_ent *q = realloc(g_ri, c * sizeof(*q));
        if (!q) return;
        g_ri = q;
        g_ri_cap = c;
    }
    char *d = strdup(qname);
    if (!d) return;
    struct realip_ent *e = &g_ri[g_ri_n++];
    memset(e, 0, sizeof(*e));
    e->domain = d;
    e->hash = h;
    e->v6 = (uint8_t)(v6 != 0);
    memcpy(e->addr, addr, alen);
    e->expires = exp;
}

void realip_note(const char *qname, uint32_t addr_host, uint32_t ttl) {
    uint32_t n = htonl(addr_host);
    ri_note(qname, 0, (const uint8_t *)&n, ttl);
}

void realip_note6(const char *qname, const uint8_t addr[16], uint32_t ttl) {
    ri_note(qname, 1, addr, ttl);
}

/* Вернуть в наборы каналов real-ip все живые элементы — с оставшимся сроком. Каналы выводятся
 * заново тем же правилом, что у ответа (proxy.c): IPv4 — во все совпавшие каналы real-ip с
 * половиной IPv4; IPv6 — только если у ВСЕХ совпавших каналов есть половина IPv6 (иначе на AAAA
 * имени теперь отвечают пустым ответом, и адресу в наборе делать нечего). Имя, больше не
 * входящее ни в один канал real-ip, из памяти уходит. Возвращает число вставок. */
size_t realip_reassert(void) {
    time_t now = ri_mono();
    ri_purge(now);
    size_t put = 0;
    for (size_t i = 0; i < g_ri_n;) {
        struct realip_ent *e = &g_ri[i];
        chm_t all = dch_match_mask(e->domain);
        chm_t m = 0;
        for (size_t c = 0; c < g_dch_n; c++) {
            if (!chm_has(all, c) || !g_dch[c].realip) continue;
            if (!(g_dch[c].fam & (e->v6 ? DCH_V6 : DCH_V4))) continue;
            m = chm_or(m, chm_one(c));
        }
        if (e->v6 && !dch_all_v6(all)) m = 0;
        if (!m) { ri_drop(i); continue; }
        uint32_t left = (uint32_t)(e->expires - now);
        if (left < 1) left = 1;
        for (size_t c = 0; c < g_dch_n; c++) {
            if (!chm_has(m, c)) continue;
            if (e->v6) {
                dch_add6(c, e->domain, e->addr, left);
            } else {
                uint32_t a;
                memcpy(&a, e->addr, 4);
                dch_add(c, e->domain, ntohl(a), left);
            }
            put++;
        }
        i++;
    }
    return put;
}
