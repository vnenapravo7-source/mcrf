#ifndef MCRF_STEER_ROUTE_SLOTS_H
#define MCRF_STEER_ROUTE_SLOTS_H
#include <stdatomic.h>
#include <stdint.h>
/* Active synthetic flow ports must be unique even when another flow exits
 * during admission. A failed CAS never owns (and must never clear) its bit. */
static inline int mcrf_route_slot_acquire(atomic_uint *occupied){
    unsigned old=atomic_load(occupied),bit,slot;
    do {
        if(old==UINT32_MAX)return -1;
        slot=(unsigned)__builtin_ctz(~old);bit=1u<<slot;
    }while(!atomic_compare_exchange_weak(occupied,&old,old|bit));
    return (int)slot;
}
static inline void mcrf_route_slot_release(atomic_uint *occupied,int slot){
    if(slot>=0&&slot<32)atomic_fetch_and(occupied,~(1u<<(unsigned)slot));
}
#endif
