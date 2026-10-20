/* Стек туннеля: пакеты из TUN в потоки к узлу через дайлер (dialer.h). Границы — в stack.c. */
#ifndef STEER_STACK_H
#define STEER_STACK_H
#include "dialer.h"
/* Нужен struct output: имя устройства и номер таблицы (из неё — адрес устройства) живут в
 * выходе. Объявления вперёд здесь недостаточно — поля читаются. */
#include "spec.h"

/* Устройство поднято, у него есть адрес, и оно готово нести трафик: зовётся один раз, из потока
 * stack_run, до запуска потоков цикла. Модуль протокола говорит здесь демону up с именем
 * устройства (evline.h, поле dev), и маршрут выхода к устройству привязывает демон. */
typedef void (*stack_ready_fn)(void *arg, const char *dev);

/* Поднять TUN выхода и нести через него трафик дайлером d, пока не кончатся очереди.
 *
 * Выход передаётся целиком, а не именем устройства: туннель сам поднимает устройство и сам даёт
 * ему адрес, а адрес берётся из таблицы выхода (реестр). Маршрут выхода к устройству стек НЕ
 * привязывает (с 1.10, шаг 3): это работа демона, рядом со стражем правил и postrouting_guard, а
 * стек о моменте готовности только сообщает — ready (может быть NULL).
 *
 * Возвращает всегда 1: успешного выхода у цикла нет (см. конец stack_run). */
int stack_run(struct output *o, const struct dialer *d, stack_ready_fn ready, void *arg);

/* Набор активных узлов изменился (пул узлов, pool.c): соединения, чей узел больше не активен
 * (dialer_ops.stale), стек сбрасывает — RST клиенту, — чтобы приложения переподключались сразу, а не
 * ждали своего таймаута на повисшем соединении. Зовётся из любого потока; поток цикла замечает это на
 * ближайшем витке (не позже секунды). */
void stack_nodes_changed(void);

/* Поднять устройство и дать ему адрес из таблицы выхода. Каждый отказ `ip` называется в
 * журнале своим тоном (I-114). Отдельно от stack_run — ради стенда devupmatch. */
void tun_bring_up(const char *dev, int table);

/* Host-configured raw IP TUN, MTU TUN_MTU, no PI/virtio headers. Duplicates fd;
 * never runs ip or creates an interface. Host retains its descriptor. One run
 * per process; stop by terminating the isolated hosting process. */
int stack_run_fd(struct output *o, const struct dialer *d, int fd,
                 stack_ready_fn ready, void *arg);
/* Install before workers start, keep context alive until they finish. Nonzero
 * rejects a new TCP/UDP flow. NULL keeps existing router behavior. */
typedef int (*stack_flow_filter_fn)(void *ctx,const struct flow_key *key,int udp);
void stack_set_flow_filter(stack_flow_filter_fn filter,void *ctx);
struct stack_counters { uint64_t tx_payload,rx_payload,blocked_flows; };
/* Opt-in counters; payload excludes proxy headers and TCP retransmission. */
void stack_enable_counters(int enabled);
void stack_get_counters(struct stack_counters *out);
#endif
