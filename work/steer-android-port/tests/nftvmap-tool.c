/* Обёртка над src/lib/nftvmap.c для стенда tests/nftvmapmatch.sh.
 *
 *   nftvmap-tool read  ТАБЛИЦА КАРТА СЛОТОВ            — «i цепочка» по строкам, затем «n N»
 *   nftvmap-tool write ТАБЛИЦА КАРТА СЛОТОВ i=цепь…    — прочитать карту и привести её к have с
 *                                                        заменами (i= — снять элемент)
 * Семейство — inet. Код выхода 0 — ядро ответило/приняло, 1 — отказ (errno в stderr). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#include "nftvmap.h"

#define SLOTS_MAX 512

int main(int argc, char **argv) {
    if (argc < 5) {
        fprintf(stderr, "usage: %s read|write TABLE MAP SLOTS [i=chain ...]\n", argv[0]);
        return 2;
    }
    size_t slots = strtoul(argv[4], NULL, 10);
    if (!slots || slots > SLOTS_MAX) return 2;
    static char have[SLOTS_MAX][NFV_CHAIN_MAX], want[SLOTS_MAX][NFV_CHAIN_MAX];
    int n = nfv_map_read(1, argv[2], argv[3], have, slots);
    if (!strcmp(argv[1], "read")) {
        if (n < 0) { fprintf(stderr, "read: %s\n", strerror(errno)); return 1; }
        for (size_t i = 0; i < slots; i++)
            if (have[i][0]) printf("%zu %s\n", i, have[i]);
        printf("n %d\n", n);
        return 0;
    }
    if (strcmp(argv[1], "write") != 0) return 2;
    if (n < 0) { fprintf(stderr, "read: %s\n", strerror(errno)); return 1; }
    memcpy(want, have, sizeof(want));
    for (int a = 5; a < argc; a++) {
        char *eq = strchr(argv[a], '=');
        if (!eq) return 2;
        size_t i = strtoul(argv[a], NULL, 10);
        if (i >= slots) return 2;
        snprintf(want[i], NFV_CHAIN_MAX, "%s", eq + 1);
    }
    if (nfv_map_write(1, argv[2], argv[3], (const char (*)[NFV_CHAIN_MAX])want,
                      (const char (*)[NFV_CHAIN_MAX])have, slots) != 0) {
        fprintf(stderr, "write: %s\n", strerror(errno));
        return 1;
    }
    return 0;
}
