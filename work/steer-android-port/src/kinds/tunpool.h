/* Пул узлов туннеля по подписке глазами вида: проверка ключей выхода и файл состояния клиента.
 *
 * Ключи `active`, `by`, `interval`, `silence` (struct tun_pool в spec.h) разбирает читатель формата
 * (model/v2.c), а сочетание с выбором узлов проверяет вид — здесь, одной проверкой на vless и прокси.
 * Пул живёт в процессе клиента (src/tunnel/pool.c) и пишет файл <тег>-<выход> в каталог состояния:
 * какие узлы активны сейчас. status и diag читают его отсюда.
 *
 * Функции статические в заголовке, а не файл: вид vless входит и в статические профили (телефон),
 * а вид прокси — только в libsteer, и общий файл пришлось бы вписывать в каждый их состав. */
#ifndef STEER_KINDS_TUNPOOL_H
#define STEER_KINDS_TUNPOOL_H
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include "spec.h"

static inline int tun_pool_check(const struct output *o, const struct out_keys *k, struct err *e) {
    const struct tun_pool *p = &k->pool;
    char msg[240];
    if (k->by_set && p->active <= 1) {
        snprintf(msg, sizeof(msg), "outputs.%s: by — раздача соединений между активными узлами, она "
                 "есть только при active больше 1", o->name);
        return err_set(e, "%s", msg);
    }
    if (k->nodes_n && (size_t)p->active > k->nodes_n) {
        snprintf(msg, sizeof(msg), "outputs.%s: active %d, а узлов в nodes %zu — активных не бывает "
                 "больше выбранных", o->name, p->active, k->nodes_n);
        return err_set(e, "%s", msg);
    }
    return 0;
}

/* Файл состояния клиента: JSON одной строкой, верен, пока жив процесс из поля pid. Строка — в куче
 * (free — вызывающему), сколько бы активных узлов в ней ни было; NULL — файла нет, процесс мёртв или
 * строка не целая (обрубок JSON сломал бы весь вывод status). */
static inline char *tun_state_load(const char *tag, const char *out_name) {
    char path[256];
    snprintf(path, sizeof(path), "%s/%s-%.32s", steer_state_dir(), tag, out_name);
    FILE *f = fopen(path, "r");
    if (!f) return NULL;
    char *buf = NULL;
    size_t cap = 0;
    ssize_t r = getline(&buf, &cap, f);
    fclose(f);
    if (r <= 0 || buf[r - 1] != '\n' || buf[0] != '{') { free(buf); return NULL; }
    buf[r - 1] = '\0';
    const char *p = strstr(buf, "\"pid\":");
    long pid = 0;
    if (!p || sscanf(p + 6, "%ld", &pid) != 1 || pid <= 0 || kill((pid_t)pid, 0) != 0) {
        free(buf);
        return NULL;
    }
    return buf;
}

/* Активных узлов меньше, чем просили, — warn: выход работает, но на меньшем числе узлов. Пока все на
 * месте — ok, и только у пула больше одного узла (у одного об узле говорит здоровье выхода). */
static inline void tun_pool_diag(kind_diag_fn *put, const char *tag, const struct output *o,
                          const struct tun_pool *pc) {
    char what[160], hint[400];
    char *buf = pc->active > 1 ? tun_state_load(tag, o->name) : NULL;
    if (!buf) return;
    int have = 0, slots = pc->active;
    for (const char *p = buf; (p = strstr(p, "\"index\":")); p++) have++;
    const char *sl = strstr(buf, "\"slots\":");
    if (sl) sscanf(sl + 8, "%d", &slots);
    free(buf);
    snprintf(what, sizeof(what), "выход %.40s: активных узлов %d из %d", o->name, have, pc->active);
    if (slots < pc->active)
        snprintf(hint, sizeof(hint), "кандидатов в подписке всего %d — больше узлов сразу держать нечем; "
                 "уменьшите active или расширьте nodes", slots);
    else
        snprintf(hint, sizeof(hint), "остальные кандидаты не отвечают — клиент ищет им замену сам; какие "
                 "узлы живы, покажет steer %s-probe %.40s", tag, o->name);
    put("nodes", have >= pc->active ? "ok" : "warn", what, have >= pc->active ? "" : hint);
}

#endif
