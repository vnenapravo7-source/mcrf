/* Конфигурация libyaml для сборки без её autotools/CMake — наш файл, не из upstream.
 *
 * yaml_private.h подключает "config.h", когда задан HAVE_CONFIG_H (ключ -DHAVE_CONFIG_H идёт из
 * THIRD_DEFS в build/sources.mk во все пути сборки и из cflags libsteer_yaml в Android.bp). Больше
 * ничего библиотеке для разбора не нужно: api.c берёт отсюда только номер версии для
 * yaml_get_version(). Содержимое — ровно то, что CMake upstream выводит из cmake/config.h.in для
 * выпуска 0.2.5; при обновлении библиотеки числа меняются вместе с исходниками (см. UPSTREAM). */
#define YAML_VERSION_MAJOR 0
#define YAML_VERSION_MINOR 2
#define YAML_VERSION_PATCH 5
#define YAML_VERSION_STRING "0.2.5"
