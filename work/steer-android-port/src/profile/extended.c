/* Профиль extended — полный роутерный пакет steer-extended (и прошивка телефона, профиль
 * android): клиенты VLESS и xsteer, подписки, мост Telegram. Команды этих модулей есть в
 * бинарнике потому, что их файлы есть в профиле (build/sources.mk); здесь — только то, что
 * модулем не выражается (profile.h). */
#include "profile.h"

const struct profile steer_profile = {
    .build = "расширенная сборка, VLESS/Reality",
    .extended = 1,
};
