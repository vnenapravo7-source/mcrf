/* Профиль server — хаб xsteer для VPS (архив steer-hub). Клиентской половины в нём нет: на VPS
 * нет ни спеки, ни выходов, и клиентские подкоманды отказывают той же заглушкой, что в базовой
 * сборке (src/daemon/main.c), — потому что их файлов нет в профиле (build/sources.mk). Здесь —
 * только имя варианта сборки. */
#include "profile.h"

const struct profile steer_profile = {
    .build = "серверная сборка, хаб xsteer",
};
