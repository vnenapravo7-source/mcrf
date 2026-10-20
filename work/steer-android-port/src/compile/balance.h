/* pick: balance в дереве набора правил — устройство и доводы в balance.c. */
#ifndef STEER_BALANCE_H
#define STEER_BALANCE_H

struct nft_table;
struct spec;
struct groups;
struct output;

/* Цепочки, карты и цепочки меток всех групп balance, в которые ведут каналы gr. 0 — готово;
 * -1 — нет памяти в арене дерева. */
int nft_emit_balance(struct nft_table *t, const struct spec *sp, const struct groups *gr);
/* Выход — группа pick: balance: правило канала переходит в её цепочку, а не ставит метку. */
int out_balanced(const struct output *o);

#endif
