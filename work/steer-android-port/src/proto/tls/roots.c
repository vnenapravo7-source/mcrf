/* Корни для проверки сертификата: шов стенда и склейка хранилища телефона.
 *
 * Жило в клиенте VLESS (client.c), пока security=tls был только у него. Теперь корни
 * спрашивают двое — транспорт security=tls (proto/transport/trsec.c) и замер urltest по HTTPS
 * (urltls.c), — и оба обязаны доверять одному и тому же набору. Место им рядом с certverify.c,
 * которому они и отдаются: это политика хранилища, а не часть какого-либо протокола. Отсюда же
 * и слои: urltls.c больше не тянет заголовок клиента VLESS ради одной функции.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dirent.h>
#include <pthread.h>

#include "platform.h"
#include "roots.h"

/* Шов: ОТКУДА БРАТЬ КОРНИ при проверке сертификата (security=tls).
 *
 * В бою указатель NULL, и certverify.c берёт умолчание — файл пакета ca-bundle. Стенду
 * умолчание не годится по построению: цепочку он выпускает сам, на месте, и доверять ей
 * системное хранилище не может и не должно. Поле auth.roots для этого и существует с самого
 * появления security=tls, но не заполнялось никем — то есть путь «свои корни» в движке был
 * объявлен и мёртв.
 *
 * Почему шов, а не настройка узла. Путь к хранилищу, вынесенный в подписку, означал бы, что
 * узел вправе назвать, чем его проверять, — то есть проверку, которой распоряжается
 * проверяемый. Стенду же нужна ровно подмена на время процесса, и она здесь (R-118). Стенды
 * (tests/vlessmatch.c, tests/androidroots.c) включают этот файл, чтобы дотянуться до шва. */
static const char *g_cert_roots;

/* КОРНИ НА ТЕЛЕФОНЕ. certverify.c читает хранилище ОДНИМ файлом PEM — так его кладёт пакет
 * ca-bundle на роутере. У Android такого файла нет: корни лежат каталогом, по сертификату на
 * файл (текст `openssl x509 -text` и PEM за ним), и с Android 14 основная копия — в APEX
 * conscrypt, который обновляется через mainline отдельно от прошивки; /system/etc/security/
 * cacerts остаётся запасной. Без склейки проверка security=tls на телефоне кончалась бы
 * «хранилище корней не прочиталось» у каждого узла.
 *
 * Склеивается при первом TLS-соединении процесса, в файл состояния, и отдаётся certverify
 * тем же швом, что и стенду (auth.roots): так сам certverify.c (защищённый путь) не меняется
 * вовсе. Каталог читается живым, а не снимком на сборке, — иначе обновление корней через
 * mainline до движка не доходило бы. Текст между сертификатами разбору не мешает:
 * разбор PEM ищет в буфере границы BEGIN/END и всё вне их пропускает.
 *
 * Корни, выключенные человеком в настройках Android (cacerts-removed), здесь не учитываются:
 * движок доверяет системному набору, а не выбору пользователя в Java-хранилище. */
static char g_android_roots[512];
static pthread_once_t g_android_roots_once = PTHREAD_ONCE_INIT;

/* Склеить каталог dir в файл final. 0 — готово; -1 — каталога нет, он пуст или записать не
 * вышло (тогда времянки не остаётся). Ошибка записи — не повод отдавать certverify обрубок:
 * корней в нём меньше, чем в системе, и часть узлов security=tls отказала бы без причины. */
static int android_roots_glue(const char *dir, const char *final) {
    DIR *d = opendir(dir);
    if (!d) return -1;
    char tmp[600];
    snprintf(tmp, sizeof tmp, "%s.XXXXXX", final);
    int fd = mkstemp(tmp);
    FILE *out = fd >= 0 ? fdopen(fd, "w") : NULL;
    if (!out) {
        if (fd >= 0) { close(fd); unlink(tmp); }
        closedir(d);
        return -1;
    }
    int n = 0, bad = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') continue;
        char p[512];
        snprintf(p, sizeof p, "%s/%s", dir, e->d_name);
        FILE *in = fopen(p, "rb");
        if (!in) continue;
        char buf[4096];
        size_t got;
        while ((got = fread(buf, 1, sizeof buf, in)) > 0)
            if (fwrite(buf, 1, got, out) != got) bad = 1;
        fputc('\n', out);
        fclose(in);
        n++;
    }
    closedir(d);
    if (ferror(out)) bad = 1;
    if (fclose(out) != 0) bad = 1;
    if (bad || n == 0 || rename(tmp, final) != 0) { unlink(tmp); return -1; }
    return 0;
}

/* Каталоги хранилища — у платформы (ca_dirs, src/platform/android.c), по порядку
 * предпочтения: первый, где нашёлся хоть один файл, и есть хранилище. */
static void android_roots_build(void) {
    const char *const *dirs = plat()->ca_dirs;
    /* Куда класть: каталог состояния (с --state-dir, как у всего движка), а если туда не
     * пишется — каталог времянок. Без запасного места отказ mkstemp оставлял бы процесс без
     * корней до перезапуска: склейка делается один раз (pthread_once). */
    const char *places[] = { steer_state_dir(), plat()->tmp_dir };
    for (size_t w = 0; w < sizeof places / sizeof places[0]; w++) {
        char final[512];
        snprintf(final, sizeof final, "%s/ca-roots.pem", places[w]);
        for (size_t k = 0; dirs[k]; k++)
            if (android_roots_glue(dirs[k], final) == 0) {
                snprintf(g_android_roots, sizeof g_android_roots, "%s", final);
                return;
            }
    }
}

static const char *cert_roots(void) {
    if (g_cert_roots) return g_cert_roots;
    /* Платформа без системного хранилища (роутер) — корни у certverify свои. */
    if (!plat()->ca_dirs) return NULL;
    pthread_once(&g_android_roots_once, android_roots_build);
    return g_android_roots[0] ? g_android_roots : NULL;
}

const char *tls_cert_roots(void) { return cert_roots(); }
