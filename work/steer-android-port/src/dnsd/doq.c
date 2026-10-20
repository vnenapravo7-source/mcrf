/* Кадры DNS по QUIC (RFC 9250): запрос и ответ на потоке. Соединение — в dup.c, устройство и доводы
 * — в шапке doq.h. */

#include <string.h>
#include "doq.h"

size_t doq_frame_query(uint8_t *dst, size_t cap, const uint8_t *q, size_t qn) {
    if (qn < 12 || qn > DOQ_MSG_MAX || cap < 2 + qn) return 0;
    dst[0] = (uint8_t)(qn >> 8);
    dst[1] = (uint8_t)qn;
    memcpy(dst + 2, q, qn);
    dst[2] = 0;                                        /* ID — 0 (RFC 9250, 4.2.1) */
    dst[3] = 0;
    return 2 + qn;
}

int doq_take_answer(const uint8_t *rx, size_t rn, const uint8_t **msg, size_t *mlen) {
    if (rn < 2) return 0;
    size_t len = ((size_t)rx[0] << 8) | rx[1];
    /* Заголовок DNS — двенадцать байт: короче сообщением не бывает, и ждать дальше нечего. */
    if (len < 12) return -1;
    if (rn < 2 + len) return 0;
    if (rn > 2 + len) return -1;
    *msg = rx + 2;
    *mlen = len;
    return 1;
}

const char *doq_err_name(uint64_t code) {
    switch (code) {
    case DOQ_NO_ERROR: return "DOQ_NO_ERROR";
    case DOQ_INTERNAL_ERROR: return "DOQ_INTERNAL_ERROR";
    case DOQ_PROTOCOL_ERROR: return "DOQ_PROTOCOL_ERROR";
    case DOQ_REQUEST_CANCELLED: return "DOQ_REQUEST_CANCELLED";
    case DOQ_EXCESSIVE_LOAD: return "DOQ_EXCESSIVE_LOAD";
    case DOQ_UNSPECIFIED_ERROR: return "DOQ_UNSPECIFIED_ERROR";
    default: return "DOQ_?";
    }
}
