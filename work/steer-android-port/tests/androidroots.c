/* Корни сертификатов на Android: склейка каталога в файл для certverify.
 *
 * ЗАЧЕМ. На телефоне нет файла ca-bundle, который certverify.c читает на роутере: корни лежат
 * каталогом, по сертификату на файл, и каждый файл — это текст `openssl x509 -text`, за
 * которым идёт PEM. Движок на телефоне склеивает первый найденный каталог в файл
 * состояния и отдаёт его certverify швом auth.roots (см. cert_roots в src/proto/tls/roots.c). Если склейка
 * молча не работает, проверка security=tls на телефоне отвергает КАЖДЫЙ узел как «хранилище
 * корней не прочиталось», а на роутере этого не видно никогда — там путь другой.
 *
 * ЧТО ПРОВЕРЯЕТСЯ. Первый каталог списка отсутствует, второй пуст — берётся третий; в нём файлы в формате
 * Android (текст перед PEM), и из склейки разбирается ровно столько сертификатов, сколько
 * файлов, а хранилище корней слоя (sc_roots_load — тем же путём его грузит certverify.c)
 * склейку принимает; скрытые файлы пропускаются; повторный вызов отдаёт тот же путь, не
 * пересобирая; в каталоге состояния не остаётся времянок; заданный шов стенда (g_cert_roots)
 * главнее склейки.
 *
 * Сертификаты стенд выпускает сам (tests/certgen.c, wolfCrypt с WOLFSSL_CERT_GEN) — как
 * vlessmatch; собранный без STEER_HAVE_X509WRITE стенд — громкий пропуск.
 *
 * Сборка — tests/ext-test.sh. */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <dirent.h>
#include <sys/stat.h>

/* Платформа — телефон, каталоги корней — свои, во временном месте: и то и другое задаёт сборка
 * в tests/ext-test.sh (-DSTEER_DEFAULT_PLATFORM=android и STEER_ANDROID_CA_DIRS, их читает
 * src/platform/android.c). Каталог состояния стенд задаёт сам, ниже, тем же швом, что --state-dir.
 * Склейка и шов g_cert_roots живут в src/proto/tls/roots.c (до шага 2 выпуска 1.10 — в
 * client.c), и включается ровно он: cert_roots статическая. */
#include "../src/proto/tls/roots.c"
#include <sys/random.h>

#include "scrypto.h"

static int g_fail, g_pass;
static void check(const char *what, long want, long got) {
    if (want == got) { g_pass++; printf("%-58s ok\n", what); }
    else { g_fail++; printf("%-58s FAIL (ожидалось %ld, получено %ld)\n", what, want, got); }
}

#ifdef STEER_HAVE_X509WRITE
#include "certgen.h"

/* Самоподписанный сертификат в PEM — корень, как в хранилище Android. */
static int make_root(const char *cn, char *pem, size_t cap) {
    struct tcg_key *key = tcg_key_new();
    if (!key) return -1;
    unsigned char der[2048];
    size_t dn = 0;
    int rc = tcg_issue(key, cn, "steer", 1, NULL, NULL, 0, der, sizeof(der), &dn);
    if (rc == 0) rc = tcg_der_to_pem(der, dn, pem, cap);
    tcg_key_free(key);
    return rc;
}
#endif

int main(void) {
#ifndef STEER_HAVE_X509WRITE
    printf("androidroots: собрано без выпуска X.509 (STEER_HAVE_X509WRITE) — ПРОПУСК\n");
    return 0;
#else
    (void)system("rm -rf /tmp/steer-androidroots");
    mkdir("/tmp/steer-androidroots", 0700);
    mkdir("/tmp/steer-androidroots/state", 0700);
    mkdir("/tmp/steer-androidroots/cacerts", 0700);
    mkdir("/tmp/steer-androidroots/empty", 0700);      /* есть, но пуст — пропустить */

    /* Три корня в формате Android: текст перед PEM и хвостовая строка SHA1, как в
     * system/ca-certificates/files. Плюс скрытый файл, который читать нельзя. */
    const int N = 3;
    for (int i = 0; i < N; i++) {
        char pem[4096], cn[32], path[128];
        snprintf(cn, sizeof cn, "root-%d", i);
        if (make_root(cn, pem, sizeof pem) != 0) { printf("выпуск корня не удался\n"); return 1; }
        snprintf(path, sizeof path, "/tmp/steer-androidroots/cacerts/%08x.0", 0x1000 + i);
        FILE *f = fopen(path, "w");
        fprintf(f, "Certificate:\n    Data:\n        Version: 3 (0x2)\n        Subject: O=steer, CN=%s\n"
                   "        Subject Public Key Info: ...\n%s"
                   "SHA1 Fingerprint=00:11:22:33\n", cn, pem);
        fclose(f);
    }
    FILE *h = fopen("/tmp/steer-androidroots/cacerts/.hidden", "w");
    fputs("-----BEGIN CERTIFICATE-----\nбрак\n-----END CERTIFICATE-----\n", h);
    fclose(h);

    /* Каталог состояния — как его задаёт --state-dir (steer_set_state_dir, src/platform). */
    check("платформа — телефон, с системным хранилищем корней", 1, plat()->ca_dirs != NULL);
    steer_set_state_dir("/tmp/steer-androidroots/state");
    const char *p = cert_roots();
    check("склейка: путь выдан", 1, p != NULL);
    check("склейка: из третьего каталога (первого нет, второй пуст), в каталоге состояния", 0,
          p ? strcmp(p, "/tmp/steer-androidroots/state/ca-roots.pem") : -1);

    static char glued[65536];
    size_t gn = 0;
    FILE *g = p ? fopen(p, "rb") : NULL;
    if (g) { gn = fread(glued, 1, sizeof(glued) - 1, g); fclose(g); }
    glued[gn] = '\0';
    struct sc_roots *roots = NULL;
    int rc = gn ? sc_roots_load(&roots, (const unsigned char *)glued, gn) : -1;
    check("склейка: хранилище корней её принимает (текст между PEM не мешает)", 0, rc);
    check("склейка: сертификатов столько же, сколько файлов", N,
          tcg_count_pem_certs(glued, gn));
    sc_roots_free(roots);

    check("повторный вызов: тот же путь", 1, cert_roots() == p);

    int left = 0;
    DIR *d = opendir("/tmp/steer-androidroots/state");
    for (struct dirent *e; d && (e = readdir(d));)
        if (strstr(e->d_name, ".XXXXXX") || strstr(e->d_name, "ca-roots.pem."))
            left++;
    if (d) closedir(d);
    check("времянок в каталоге состояния нет", 0, left);

    /* Шов стенда главнее: заданный g_cert_roots отдаётся как есть, склейка не зовётся. */
    g_cert_roots = "/свой/путь.pem";
    check("заданный шов отдаётся как есть", 0, strcmp(cert_roots(), "/свой/путь.pem"));
    g_cert_roots = NULL;

    (void)system("rm -rf /tmp/steer-androidroots");
    printf("\n%d проверок пройдено%s\n", g_pass, g_fail ? "" : "\nвсе проверки прошли");
    return g_fail ? 1 : 0;
#endif
}
