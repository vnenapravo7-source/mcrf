/* Откуда брать корни при проверке сертификата. Подробности — в roots.c. */
#ifndef STEER_ROOTS_H
#define STEER_ROOTS_H

/* Хранилище корней для auth.roots у tls13_handshake_auth: шов стенда, на телефоне — склейка
 * системного каталога, на роутере — NULL (умолчание certverify). Одни корни на весь движок:
 * их спрашивают и транспорт security=tls (proto/transport/trsec.c), и замер urltest по HTTPS
 * (urltls.c). */
const char *tls_cert_roots(void);

#endif
