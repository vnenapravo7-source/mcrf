/* Разбор подписки: постквантовые поля Xray-core — encryption (VLESS encryption) и pqv / mldsa65Verify
 * (проверка подписи ML-DSA-65 у Reality). Ссылка vless:// и конфиг Xray в JSON, обе формы settings
 * (vnext/users и упрощённая), негодные значения и причины пропуска.
 *
 * Значения — настоящие, из Xray-core 26.9.9 (tests/sub-pq-samples.h). Криптографии стенду не нужно:
 * годность строки решает vencp.h, а сами обмены проверяет ext-test на настоящей библиотеке и против
 * настоящего Xray (tests/venc.sh). sub.c и vless_proto.c линкуются отдельными объектами (стендов с #include .c из src и так предел). */
#include <stdio.h>
#include <string.h>

#include "vless.h"
#include "vencp.h"
#include "sub-pq-samples.h"

static int g_pass, g_fail;
static void check(const char *what, int ok) {
    if (ok) g_pass++; else { g_fail++; printf("ПРОВАЛ: %s\n", what); }
}
static void check_s(const char *what, const char *want, const char *got) {
    int ok = got && !strcmp(want, got);
    if (!ok) printf("  ожидалось «%s», получено «%s»\n", want, got ? got : "(null)");
    check(what, ok);
}

#define UUID "b831381d-6324-4d53-ad4f-8cda48b30811"

static int url(const char *fmt_tail, struct vless_node *n) {
    static char buf[9000];
    snprintf(buf, sizeof buf, "vless://" UUID "@example.org:443?%s", fmt_tail);
    return vless_parse_url(buf, n);
}

int main(void) {
    struct vless_node n, n2;
    char q[8000];

    /* --- encryption в ссылке --- */
    snprintf(q, sizeof q, "encryption=%s&type=tcp&security=none#a", ENC_X25519);
    check("encryption X25519 (0rtt): узел пригоден", url(q, &n) == 0);
    check_s("encryption хранится строкой как есть", ENC_X25519, n.encryption);

    snprintf(q, sizeof q, "encryption=%s&type=tcp&security=none#a", ENC_MLKEM);
    check("encryption ML-KEM (1579 знаков ключа): узел пригоден", url(q, &n) == 0);
    check_s("длинный ключ не обрезан", ENC_MLKEM, n.encryption);
    check("одинаковое значение — один экземпляр в таблице", url(q, &n2) == 0 && n.encryption == n2.encryption);

    {   /* режимы и набивка, разбор vencp */
        static const char *modes[] = { "native", "xorpub", "random" };
        for (int i = 0; i < 3; i++) {
            char e[400];
            snprintf(e, sizeof e, "mlkem768x25519plus.%s.1rtt.100-111-1111.75-0-111.50-0-3333.YHD4th3rx6hr8R22ZLUA6ivhJdHWNt8bsAcNYrNKxkQ", modes[i]);
            snprintf(q, sizeof q, "encryption=%s&type=tcp&security=none#m", e);
            int rc = url(q, &n);
            struct venc_cfg c;
            int pr = n.encryption ? vencp_parse(n.encryption, &c, NULL) : -1;
            char w[80];
            snprintf(w, sizeof w, "режим %s с набивкой: пригоден и разобран", modes[i]);
            check(w, rc == 0 && pr == 0 && c.xor_mode == i && !c.zero_rtt && c.nkeys == 1 &&
                     c.npad_lens == 2 && c.npad_gaps == 1 && c.lens[0][2] == 1111 && c.gaps[0][0] == 75);
        }
    }

    check("encryption=none — шифрования нет", url("encryption=none&type=tcp&security=none#n", &n) == 0 && n.encryption == NULL);
    check("encryption пусто — шифрования нет", url("encryption=&type=tcp&security=none#n", &n) == 0 && n.encryption == NULL);
    check("параметра нет — шифрования нет", url("type=tcp&security=none#n", &n) == 0 && n.encryption == NULL);

    check("encryption чужого вида — узел пропущен", url("encryption=aes-128-gcm&type=tcp&security=none#n", &n) == 1);
    check_s("причина названа", "encryption не поддержан", n.skip_reason);
    check("режим не из списка — пропущен",
          url("encryption=mlkem768x25519plus.weird.0rtt.YHD4th3rx6hr8R22ZLUA6ivhJdHWNt8bsAcNYrNKxkQ&type=tcp&security=none#n", &n) == 1);
    check("ключ не 32 и не 1184 байта — пропущен",
          url("encryption=mlkem768x25519plus.native.0rtt.YHD4th3rx6hr8R22ZLUA6ivhJdHWNt8bsAcNYrNKx&type=tcp&security=none#n", &n) == 1);
    check("без ключа — пропущен", url("encryption=mlkem768x25519plus.native.0rtt&type=tcp&security=none#n", &n) == 1);
    check("первая набивка короче 35 — пропущен",
          url("encryption=mlkem768x25519plus.native.0rtt.100-10-20.YHD4th3rx6hr8R22ZLUA6ivhJdHWNt8bsAcNYrNKxkQ&type=tcp&security=none#n", &n) == 1);

    /* --- pqv --- */
    snprintf(q, sizeof q, "type=tcp&security=reality&pbk=K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng&sid=01&sni=example.com&fp=chrome&pqv=%s#p", PQV_KEY);
    check("reality с pqv: пригоден", url(q, &n) == 0);
    check_s("pqv хранится целиком (2603 знака)", PQV_KEY, n.pqv);
    snprintf(q, sizeof q, "type=tcp&security=reality&pbk=K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng&sid=01&sni=example.com&pqv=%.100s#p", PQV_KEY);
    check("pqv короткий — пропущен", url(q, &n) == 1);
    check_s("причина pqv названа", "pqv: не ключ ML-DSA-65", n.skip_reason);

    /* --- flow --- */
    check("flow vision-udp443 приводится к vision",
          url("type=tcp&security=reality&pbk=K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng&sid=01&sni=e.com&flow=xtls-rprx-vision-udp443#f", &n) == 0 &&
          !strcmp(n.flow, "xtls-rprx-vision"));

    /* --- конфиг Xray: vnext/users --- */
    {
        static char js[12000];
        snprintf(js, sizeof js,
            "[{\"remarks\":\"PQ\",\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"proxy\","
            "\"settings\":{\"vnext\":[{\"address\":\"example.org\",\"port\":443,\"users\":[{\"id\":\"" UUID "\","
            "\"flow\":\"xtls-rprx-vision\",\"encryption\":\"none\"}]}]},"
            "\"streamSettings\":{\"network\":\"raw\",\"security\":\"reality\",\"realitySettings\":{"
            "\"serverName\":\"example.com\",\"fingerprint\":\"chrome\",\"publicKey\":\"K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng\","
            "\"shortId\":\"01\",\"mldsa65Verify\":\"%s\"}}}]},"
            "{\"remarks\":\"ENC\",\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"proxy\","
            "\"settings\":{\"vnext\":[{\"address\":\"example.net\",\"port\":8443,\"users\":[{\"id\":\"" UUID "\","
            "\"encryption\":\"%s\"}]}]},\"streamSettings\":{\"network\":\"tcp\",\"security\":\"none\"}}]},"
            "{\"remarks\":\"SIMPLE\",\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"proxy\","
            "\"settings\":{\"address\":\"example.io\",\"port\":\"9443\",\"id\":\"" UUID "\",\"encryption\":\"%s\"},"
            "\"streamSettings\":{\"network\":\"tcp\",\"security\":\"none\"}}]}]",
            PQV_KEY, ENC_X25519, ENC_MLKEM);
        struct vless_node out[8];
        struct vless_sub_stats st;
        memset(&st, 0, sizeof st);
        size_t cnt = vless_parse_sub(js, out, 8, &st);
        check("JSON: три узла пригодны", cnt == 3);
        if (cnt == 3) {
            check("JSON: mldsa65Verify → pqv", out[0].pqv && !strcmp(out[0].pqv, PQV_KEY));
            check("JSON: encryption none — нет шифрования", out[0].encryption == NULL);
            check_s("JSON: users[].encryption", ENC_X25519, out[1].encryption);
            check("JSON: упрощённая форма settings — адрес, порт, id", !strcmp(out[2].host, "example.io") && out[2].port == 9443 && !strcmp(out[2].uuid, UUID));
            check_s("JSON: упрощённая форма — encryption", ENC_MLKEM, out[2].encryption);
        }
        check("JSON: пропущенных нет", st.skipped == 0);
    }

    /* --- подписка ссылками: арифметика сходится --- */
    {
        static char sub[20000];
        snprintf(sub, sizeof sub,
                 "vless://" UUID "@a.example:443?encryption=%s&type=tcp&security=none#ok1\n"
                 "vless://" UUID "@b.example:443?encryption=bogus&type=tcp&security=none#bad1\n"
                 "vless://" UUID "@c.example:443?encryption=%s&type=tcp&security=none#ok2\n",
                 ENC_MLKEM, ENC_X25519);
        struct vless_node out[8];
        struct vless_sub_stats st;
        memset(&st, 0, sizeof st);
        size_t cnt = vless_parse_sub(sub, out, 8, &st);
        check("подписка: 2 пригодных, 1 пропущен", cnt == 2 && st.skipped == 1);
        check("подписка: причина в счётчиках", st.reasons_n == 1 && !strcmp(st.reasons[0].reason, "encryption не поддержан") &&
              !strcmp(st.reasons[0].example, "bad1"));
    }


    /* --- то, что клиент не умеет, но сервер требует --- */
    check("tcp headerType=http — пропущен", url("type=tcp&security=none&headerType=http#h", &n) == 1);
    check_s("причина headerType", "tcp headerType=http не поддержан", n.skip_reason);
    check("tcp headerType=none — пригоден", url("type=tcp&security=none&headerType=none#h", &n) == 0);
    check("xhttp с downloadSettings в extra — пропущен",
          url("type=xhttp&security=none&path=/x&extra=%7B%22downloadSettings%22%3A%7B%22address%22%3A%22d.example%22%7D%7D#x", &n) == 1);
    check_s("причина xhttp", "xhttp: обфускация не поддержана", n.skip_reason);
    check("xhttp с xPaddingBytes в extra — пригоден",
          url("type=xhttp&security=none&path=/x&extra=%7B%22xPaddingBytes%22%3A%22100-200%22%7D#x", &n) == 0 && n.pad_from == 100);
    check("xhttp с xPaddingObfsMode=true — пропущен",
          url("type=xhttp&security=none&path=/x&extra=%7B%22xPaddingObfsMode%22%3Atrue%7D#x", &n) == 1);

    /* --- sing-box: outbounds --- */
    {
        static char js[6000];
        snprintf(js, sizeof js,
            "{\"log\":{},\"outbounds\":[{\"type\":\"selector\",\"tag\":\"sel\",\"outbounds\":[\"a\"]},"
            "{\"type\":\"vless\",\"tag\":\"sb-reality\",\"server\":\"example.org\",\"server_port\":443,\"uuid\":\"" UUID "\","
            "\"flow\":\"xtls-rprx-vision\",\"tls\":{\"enabled\":true,\"server_name\":\"example.com\","
            "\"utls\":{\"enabled\":true,\"fingerprint\":\"chrome\"},\"reality\":{\"enabled\":true,"
            "\"public_key\":\"K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng\",\"short_id\":\"0123456789abcdef\"}}},"
            "{\"type\":\"vless\",\"tag\":\"sb-ws\",\"server\":\"cdn.example.org\",\"server_port\":443,\"uuid\":\"" UUID "\","
            "\"tls\":{\"enabled\":true,\"server_name\":\"cdn.example.org\"},"
            "\"transport\":{\"type\":\"ws\",\"path\":\"/ws\",\"headers\":{\"Host\":[\"front.example.org\"]},"
            "\"max_early_data\":2048,\"early_data_header_name\":\"Sec-WebSocket-Protocol\"}},"
            "{\"type\":\"trojan\",\"tag\":\"t\",\"server\":\"x\",\"server_port\":1}]}");
        struct vless_node out[8];
        struct vless_sub_stats st;
        size_t cnt = vless_parse_sub(js, out, 8, &st);
        check("sing-box: два узла vless", cnt == 2);
        if (cnt == 2) {
            check("sing-box: reality — pbk, sid, sni, fp",
                  !strcmp(out[0].security, "reality") && !strcmp(out[0].pbk, "K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng") &&
                  !strcmp(out[0].sid, "0123456789abcdef") && !strcmp(out[0].sni, "example.com") && !strcmp(out[0].fp, "chrome") &&
                  out[0].port == 443 && !strcmp(out[0].name, "sb-reality") && !strcmp(out[0].flow, "xtls-rprx-vision"));
            check_s("sing-box: ws — путь с ранними данными", "/ws?ed=2048", out[1].path);
            check_s("sing-box: ws — Host из массива", "front.example.org", out[1].http_host);
            check("sing-box: tls и тип ws", !strcmp(out[1].security, "tls") && !strcmp(out[1].type, "ws"));
        }
    }

    /* --- Clash / Mihomo --- */
    {
        static char y[9000];
        snprintf(y, sizeof y,
            "port: 7890\nmixed-port: 7891\nproxies:\n"
            "  - name: \"clash-block\"   # комментарий\n"
            "    type: vless\n    server: example.org\n    port: 443\n    uuid: " UUID "\n"
            "    network: tcp\n    tls: true\n    udp: true\n    flow: xtls-rprx-vision\n"
            "    servername: example.com\n    client-fingerprint: chrome\n    encryption: \"\"\n"
            "    alpn:\n      - h2\n      - http/1.1\n"
            "    reality-opts:\n      public-key: K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng\n      short-id: 0123456789abcdef\n"
            "  - {name: clash-flow, type: vless, server: cdn.example.org, port: 8443, uuid: " UUID ", network: ws, tls: true, servername: cdn.example.org, "
            "ws-opts: {path: /ws, headers: {Host: front.example.org}, max-early-data: 2048, early-data-header-name: Sec-WebSocket-Protocol}}\n"
            "  - name: clash-enc\n    type: vless\n    server: enc.example.org\n    port: 9443\n    uuid: " UUID "\n    network: tcp\n    encryption: %s\n"
            "  - name: ss-node\n    type: ss\n    server: s\n    port: 1\n    cipher: aes-128-gcm\n    password: p\n"
            "  - name: grpc-node\n    type: vless\n    server: g.example.org\n    port: 443\n    uuid: " UUID "\n    network: grpc\n    tls: true\n"
            "    grpc-opts:\n      grpc-service-name: svc\n"
            "proxy-groups:\n  - name: auto\n    type: select\n    proxies:\n      - clash-block\n", ENC_X25519);
        struct vless_node out[8];
        struct vless_sub_stats st;
        size_t cnt = vless_parse_sub(y, out, 8, &st);
        check("Clash: четыре узла vless, один чужой", cnt == 4 && st.foreign == 1);
        if (cnt == 4) {
            check("Clash (блок): reality, sni, fp, flow, имя без комментария",
                  !strcmp(out[0].security, "reality") && !strcmp(out[0].sni, "example.com") && !strcmp(out[0].fp, "chrome") &&
                  !strcmp(out[0].pbk, "K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng") && !strcmp(out[0].sid, "0123456789abcdef") &&
                  !strcmp(out[0].flow, "xtls-rprx-vision") && !strcmp(out[0].name, "clash-block") && out[0].encryption == NULL);
            check("Clash (поток): ws, путь, Host, ранние данные",
                  !strcmp(out[1].type, "ws") && !strcmp(out[1].path, "/ws?ed=2048") && !strcmp(out[1].http_host, "front.example.org") &&
                  !strcmp(out[1].security, "tls") && out[1].port == 8443);
            check_s("Clash: encryption", ENC_X25519, out[2].encryption);
            check("Clash: grpc-service-name", !strcmp(out[3].type, "grpc") && !strcmp(out[3].service, "svc"));
        }
        char dec[64];
        check("Clash распознан как текст, а не base64", vless_sub_text(y, strlen(y), dec, sizeof dec) == y);
    }

    /* ---- проверка сертификата узла: pcs / vcn / allowInsecure (Xray-core, sing-box, Clash) ---- */
    {
        static const char H1[] = "f8c71232f26e142a9ec780c297bd500dcbfadd2bde91f4a97876ab4d73631c12";
        static const char H2[] = "a0cf13f5063002c9f3cf982cda1fdebcb9f1277a1f56969cfa1b1d7837becfc7";
        struct vless_node n;
        char q[400];

        snprintf(q, sizeof q, "security=tls&sni=t.example&pcs=%s", H1);
        check("pcs: узел пригоден", url(q, &n) == 0);
        check_s("pcs: отпечаток сохранён", H1, n.pcs);
        snprintf(q, sizeof q, "security=tls&sni=t.example&pcs=F8:C7:12:32:F2:6E:14:2A:9E:C7:80:C2:97:BD:50:0D:CB:FA:DD:2B:DE:91:F4:A9:78:76:AB:4D:73:63:1C:12,%s&vcn=a.example,%%20b.example", H2);
        check("pcs с двоеточиями и списком, vcn с пробелом", url(q, &n) == 0);
        char want2[140];
        snprintf(want2, sizeof want2, "%s,%s", H1, H2);
        check_s("pcs: двоеточия сняты, регистр приведён, список сохранён", want2, n.pcs);
        check_s("vcn: список имён", "a.example,b.example", n.vcn);
        check("pcs: не SHA-256 — узел непригоден", url("security=tls&sni=t.example&pcs=abcd", &n) == 1);
        check_s("  причина названа", "pcs: не SHA-256 в hex", n.skip_reason);
        check("pcs у reality ничего не значит", url("security=reality&pbk=K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng&sid=0123&pcs=abcd", &n) == 0);

        /* allowInsecure: подписка проверку сама не выключает. */
        vless_set_insecure(0);
        check("allowInsecure=1 при tls без ключа выхода — узел непригоден", url("security=tls&sni=t.example&allowInsecure=1", &n) == 1);
        check_s("  причина названа", "allowInsecure: включите insecure у выхода явно", n.skip_reason);
        check("insecure=1 (как пишут панели) — то же", url("security=tls&sni=t.example&insecure=1", &n) == 1);
        check("allowInsecure=0 — обычный узел", url("security=tls&sni=t.example&allowInsecure=0", &n) == 0 && !n.insecure);
        check("allowInsecure у reality не мешает", url("security=reality&pbk=K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng&sid=0123&allowInsecure=1", &n) == 0);
        vless_set_insecure(1);
        check("с ключом выхода insecure узел пригоден и помечен", url("security=tls&sni=t.example&allowInsecure=1", &n) == 0 && n.insecure);
        vless_set_insecure(0);

        /* Конфиг Xray. */
        {
            static const char *js =
                "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"x.example\",\"port\":443,"
                "\"users\":[{\"id\":\"" UUID "\"}]}]},\"streamSettings\":{\"network\":\"tcp\",\"security\":\"tls\","
                "\"tlsSettings\":{\"serverName\":\"x.example\",\"pinnedPeerCertSha256\":\"f8c71232f26e142a9ec780c297bd500dcbfadd2bde91f4a97876ab4d73631c12\","
                "\"verifyPeerCertByName\":\"v.example\"}}}]}";
            struct vless_node out[2];
            struct vless_sub_stats st;
            size_t cnt = vless_parse_sub(js, out, 2, &st);
            check("Xray JSON: pinnedPeerCertSha256 и verifyPeerCertByName", cnt == 1 && out[0].pcs && !strcmp(out[0].pcs, H1) &&
                  out[0].vcn && !strcmp(out[0].vcn, "v.example"));
            static const char *js2 =
                "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"x.example\",\"port\":443,"
                "\"users\":[{\"id\":\"" UUID "\"}]}]},\"streamSettings\":{\"network\":\"tcp\",\"security\":\"tls\","
                "\"tlsSettings\":{\"serverName\":\"x.example\",\"allowInsecure\":true}}}]}";
            check("Xray JSON: allowInsecure — узел пропущен", vless_parse_sub(js2, out, 2, &st) == 0 && st.skipped == 1);
        }
        /* sing-box. */
        {
            static const char *sb =
                "{\"outbounds\":[{\"type\":\"vless\",\"tag\":\"a\",\"server\":\"x.example\",\"server_port\":443,\"uuid\":\"" UUID "\","
                "\"tls\":{\"enabled\":true,\"server_name\":\"x.example\","
                "\"certificate_public_key_sha256\":[\"1hD3S62x74la2vO7hT4FuMdtQwscW6lAZrzE1Ua3dn0=\"]}}]}";
            struct vless_node out[2];
            struct vless_sub_stats st;
            size_t cnt = vless_parse_sub(sb, out, 2, &st);
            check("sing-box: certificate_public_key_sha256 — в hex",
                  cnt == 1 && out[0].pks && !strcmp(out[0].pks, "d610f74badb1ef895adaf3bb853e05b8c76d430b1c5ba94066bcc4d546b7767d"));
            static const char *sb2 =
                "{\"outbounds\":[{\"type\":\"vless\",\"tag\":\"a\",\"server\":\"x.example\",\"server_port\":443,\"uuid\":\"" UUID "\","
                "\"tls\":{\"enabled\":true,\"server_name\":\"x.example\",\"insecure\":true}}]}";
            check("sing-box: insecure — узел пропущен", vless_parse_sub(sb2, out, 2, &st) == 0 && st.skipped == 1);
        }
        /* Clash. */
        {
            static const char *y =
                "proxies:\n  - name: c1\n    type: vless\n    server: x.example\n    port: 443\n    uuid: " UUID "\n"
                "    network: tcp\n    tls: true\n    servername: x.example\n"
                "    fingerprint: f8c71232f26e142a9ec780c297bd500dcbfadd2bde91f4a97876ab4d73631c12\n"
                "  - name: c2\n    type: vless\n    server: y.example\n    port: 443\n    uuid: " UUID "\n"
                "    network: tcp\n    tls: true\n    servername: y.example\n    skip-cert-verify: true\n";
            struct vless_node out[3];
            struct vless_sub_stats st;
            size_t cnt = vless_parse_sub(y, out, 3, &st);
            check("Clash: fingerprint — pcs; skip-cert-verify — узел пропущен",
                  cnt == 1 && out[0].pcs && !strcmp(out[0].pcs, H1) && st.skipped == 1);
        }
    }

    /* ---- ECH: echConfigList / ech= (Xray), ech-opts (Clash), ech (sing-box) ---- */
    {
        static const char ECH[] = "AEX+DQBBNwAgACANG785NbYxf2vAoHiUugO7PDLchnWNz2f+95epg2DJewAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA=";
        struct vless_node n;
        char q[400];
        snprintf(q, sizeof q, "security=tls&sni=t.example&ech=%s", "AEX%2BDQBBNwAgACANG785NbYxf2vAoHiUugO7PDLchnWNz2f%2B95epg2DJewAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA%3D");
        check("ech= в ссылке: узел пригоден", url(q, &n) == 0);
        check_s("  значение раскодировано из %-формы", ECH, n.ech);
        check("ech= не ECHConfigList — узел непригоден", url("security=tls&sni=t.example&ech=AAAA", &n) == 1);
        check_s("  причина названа", "ech: не ECHConfigList в base64", n.skip_reason);
        check("ech= в виде «домен+https://…» (запрос из DNS) — узел непригоден",
              url("security=tls&sni=t.example&ech=cloudflare-ech.com%2Bhttps://1.1.1.1/dns-query", &n) == 1);
        check_s("  причина названа", "ech: запрос записи из DNS не поддержан", n.skip_reason);
        check("ech у reality ничего не значит",
              url("security=reality&pbk=K4ALTVxNnrDTywBj_Stb5bomQ21QlSWOlGGT44n9Nng&sid=0123&ech=AAAA", &n) == 0);

        char js[1200];
        snprintf(js, sizeof js,
            "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"x.example\",\"port\":443,"
            "\"users\":[{\"id\":\"" UUID "\"}]}]},\"streamSettings\":{\"network\":\"tcp\",\"security\":\"tls\","
            "\"tlsSettings\":{\"serverName\":\"x.example\",\"echConfigList\":\"%s\"}}}]}", ECH);
        struct vless_node out[2];
        struct vless_sub_stats st;
        size_t cnt = vless_parse_sub(js, out, 2, &st);
        check("Xray JSON: echConfigList", cnt == 1 && out[0].ech && !strcmp(out[0].ech, ECH));
        char y[900];
        snprintf(y, sizeof y,
            "proxies:\n  - name: c1\n    type: vless\n    server: x.example\n    port: 443\n    uuid: " UUID "\n"
            "    network: tcp\n    tls: true\n    servername: x.example\n    ech-opts:\n      enable: true\n      config: %s\n", ECH);
        cnt = vless_parse_sub(y, out, 2, &st);
        check("Clash: ech-opts.config", cnt == 1 && out[0].ech && !strcmp(out[0].ech, ECH));
        char sb[1300];
        snprintf(sb, sizeof sb,
            "{\"outbounds\":[{\"type\":\"vless\",\"tag\":\"a\",\"server\":\"x.example\",\"server_port\":443,\"uuid\":\"" UUID "\","
            "\"tls\":{\"enabled\":true,\"server_name\":\"x.example\",\"ech\":{\"enabled\":true,\"config\":["
            "\"-----BEGIN ECH CONFIGS-----\",\"%s\",\"-----END ECH CONFIGS-----\"]}}}]}", ECH);
        cnt = vless_parse_sub(sb, out, 2, &st);
        check("sing-box: ech.config (строки PEM)", cnt == 1 && out[0].ech && !strcmp(out[0].ech, ECH));
        static const char *sb2 =
            "{\"outbounds\":[{\"type\":\"vless\",\"tag\":\"a\",\"server\":\"x.example\",\"server_port\":443,\"uuid\":\"" UUID "\","
            "\"tls\":{\"enabled\":true,\"server_name\":\"x.example\",\"ech\":{\"enabled\":true}}}]}";
        check("sing-box: ech без config (запрос из DNS) — узел пропущен", vless_parse_sub(sb2, out, 2, &st) == 0 && st.skipped == 1);
    }

    printf(g_fail ? "ПРОВАЛОВ: %d (прошло %d)\n" : "subpq: всё совпало (%d проверок)\n", g_fail ? g_fail : g_pass, g_pass);
    return g_fail ? 1 : 0;
}
