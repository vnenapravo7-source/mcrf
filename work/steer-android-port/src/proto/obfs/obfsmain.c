/* Модуль обфускатора: подкоманды `steer obfs` и `steer obfs-server`.
 *
 * Клиент и сервер сами (obfs.c) — общий код: их зовёт и клиент xsteer в режиме потока (поддельный
 * TCP), поэтому файл obfs.c остаётся в libsteer. Здесь — только точка входа помощника obfs,
 * которой раньше была ветка в src/daemon/main.c, и она же — main бинарника steer-obfs
 * (src/proto/obfs/modmain.c): демон запускает его ребёнком для каждого выхода `interface` с
 * обфускацией. В статических сборках (телефон, стенды) файл входит в сам steerd, и подкоманды
 * живут там, как жили. */
#include <stdio.h>
#include <stdlib.h>

#include "spec.h"
#include "obfs.h"
#include "registry.h"

int cmd_obfs(const char *spec_path, const char *out_name) {
    /* Спека — значение, а не глобал (правило 6): выделяется по требованию и живёт до конца
     * процесса. struct spec — под 300 КБ, на стеке ему не место. */
    struct spec *cfg = calloc(1, sizeof(*cfg));
    if (!cfg) die("нет памяти под спеку", NULL);
    struct err e = {0};
    if (load_spec(spec_path, cfg, &e) < 0) err_die(&e);
    struct output *o = out_by_name(cfg, out_name);
    if (!o) die("нет такого выхода: %s", out_name);
    const struct out_obfs *ob = iface_obfs(o);
    if (!ob) die("у выхода %s не настроен obfs", out_name);
    /* Метка сокета к серверу обфускации — out_underlay_mark (см. «вложенные выходы» в
     * spec.h). При via она — метка выхода-цели, а та появляется только в реестре: без
     * registry_assign функция вернула бы ноль, то есть «напрямую», молча. Без via реестр
     * не нужен и не трогается — у этого процесса его прежде не было. */
    if (o->over[0] && registry_assign(cfg, &e) < 0) err_die(&e);
    obfs_set_sock_mark(out_underlay_mark(cfg, o), o->over[0] != 0);
    return obfs_client(o->name, ob->server, ob->server_port, ob->listen, ob->listen_port);
}

int cmd_obfs_server(int listen_port, const char *forward) {
    if (!listen_port) die("нужен --listen ПОРТ (порт поддельного TCP)", NULL);
    if (!forward) die("нужен --forward АДРЕС:ПОРТ (куда отдавать датаграммы)", NULL);
    char host[80];
    int fport = 0;
    if (obfs_split_hostport(forward, host, sizeof(host), &fport) != 0)
        die("--forward должен быть вида адрес:порт, а не %s", forward);
    return obfs_server(listen_port, host, fport);
}
