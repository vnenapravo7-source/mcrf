# MCRF

VPN и прокси для Android с гибкой маршрутизацией и настраиваемым интерфейсом. Версия для iOS в разработке.

[![Все файлы](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fvnenapravo7-source%2Fmcrf%2Fmain%2F.github%2Fdownload-counts.json&query=%24.total&label=downloads%20total&color=555&style=flat-square)](https://github.com/vnenapravo7-source/mcrf/releases)
[![Android](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fvnenapravo7-source%2Fmcrf%2Fmain%2F.github%2Fdownload-counts.json&query=%24.android&label=downloads%20Android&color=3ddc84&style=flat-square)](https://github.com/vnenapravo7-source/mcrf/releases)
[![Движки](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fvnenapravo7-source%2Fmcrf%2Fmain%2F.github%2Fdownload-counts.json&query=%24.engines&label=downloads%20engines&color=8a2be2&style=flat-square)](https://github.com/vnenapravo7-source/mcrf/releases/tag/android-engines-v2.0.0)

## Скачать

| Платформа | Версия |
| --- | --- |
| Android 10+ | [2.0 — стабильный выпуск](https://github.com/vnenapravo7-source/mcrf/releases/tag/android-v2.0.0) · [универсальный APK](https://github.com/vnenapravo7-source/mcrf/releases/download/android-v2.0.0/mcrf-android-universal-release.apk) |
| iOS | В разработке |


Универсальный APK поддерживает ARM64, ARMv7 и x86_64. При первом запуске приложение предложит скачать sing-box или Steer. Дополнительные движки загружаются по мере необходимости под архитектуру устройства. [Опубликованный каталог движков](https://github.com/vnenapravo7-source/mcrf/releases/tag/android-engines-v2.0.0).


### Движки и протоколы

| Движок | Протоколы и назначение | Размер, МБ |
| --- | --- | --- |
| [sing-box-lx](https://github.com/Leadaxe/sing-box-lx)<br><sub>Кастомная сборка MCRF</sub> | VLESS / REALITY, VMess, Trojan, Shadowsocks, Hysteria / Hysteria 2<br>TUIC, AnyTLS, NaiveProxy, SSH, HTTP / HTTPS, SOCKS5, WireGuard / AmneziaWG | 30,5 |
| [Steer 0.3.3](https://github.com/splify2/steer)<br><sub>Доработанный порт MCRF, модуль 18</sub> | VLESS / REALITY, VMess, Trojan, Shadowsocks, Hysteria 2<br>HTTP / HTTPS, SOCKS4 / 4a / 5 | 10,4 |
| [Компактный WireGuard / AmneziaWG](ports/android/transports/wg-backend/)<br><sub>Модуль MCRF на базе wireguard-go</sub> | WireGuard, AmneziaWG 3.0 / 3.1 | 3,5 |
| [Telegram-прокси через WebSocket](https://github.com/y0sy4/telegram-proxy)<br><sub>Сторонний, адаптирован для MCRF</sub> | SOCKS5 → WebSocket для Telegram | 4,3 |
| **Общий пакет MCRF для белых списков**<br><sub>Один общий исполняемый модуль и транспортная библиотека вместо повторной загрузки для каждого варианта</sub> | CSQTT 2.0 / 2.1.5 / 2.1.9; панель SWG 2.5.1 / 2.5.2 / 2.5.3; QWDTT | 10,6 arm64 |
| [Отдельный пакет WDTT](ports/android/transports/transport-lock.json)<br><sub>Единственный отдельный пакет для белых списков; остальные варианты входят в общий пакет</sub> | WDTT | 9,2 |
| [OpenFlux 0.17.1](https://github.com/p1neappleXpress/OpenFlux)<br><sub>Модуль из оригинального репозитория; JS-транспорты обновляются из [OpenFluxTransports](https://github.com/p1neappleXpress/OpenFluxTransports)</sub> | Зашифрованный Session/Direct и документные транспорты; транспорт FluxFast через протокол пользовательских датаграмм | — |
| [DNSTT](ports/android/transports/dnstt/) / [VayDNS](https://github.com/net2share/vaydns) / [SlipStream](https://github.com/EndPositive/slipstream)<br><sub>Отдельные адаптированные модули</sub> | Туннели системы доменных имён; VayDNS 0.2.8; SlipStream — совместимый модуль Mygod/slipstream-rust 0.1.1 | DNSTT: 5,8<br>VayDNS: 5,7<br>SlipStream: 7,6 |
| [MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN) / [SSTP](https://github.com/ttrroyy/qThroneMobile/tree/8463d3397b0a8522f92811f5c0698679ab8efc77/carriers/sstp)<br><sub>Отдельные адаптированные модули</sub> | Туннель системы доменных имён / защищённый туннель SSTP | 4,0 / 5,4 |
| [Дополнительные протоколы — установочный пакет для Android, ARM64](https://github.com/vnenapravo7-source/mcrf/releases/download/engines-2026-10-13-1/mcrf-engine-protocols-arm64-v8a-release.apk)<br><sub>Отдельный пакет MCRF</sub> | TUIC, AnyTLS, NaiveProxy, SSH | 21,1 |
| [Xray](https://github.com/throneproj/xray-core) / [TLScore](https://github.com/throneproj/sing-box)<br><sub>Сторонние форки с адаптацией MCRF</sub> | Xray: VLESS / REALITY, VMess, Trojan, Shadowsocks, HTTP / SOCKS<br>TLScore: VLESS, VMess, Trojan, HTTP / SOCKS и обход фильтрации TLS | Xray: 14,1<br>TLScore: 15,3 |
| [WarpScout](https://github.com/vernette/warpscout)<br><sub>Сторонний</sub> | Поиск WARP-подключений | 7,0 |


## Возможности

- **Протоколы и автономные движки:** VLESS, VMess, Trojan, Shadowsocks, Hysteria 2, WG/AWG, прокси и БС-транспорты — CSQTT, WDTT, QWDTT, OLCRTC, OpenFlux и другие. Каждый движок работает независимо.
- **Облегчённый пакет БС:** одна установка для CSQTT 2.0 / 2.1.5 / 2.1.9, SWG 2.5.1–2.5.3 и QWDTT с автоопределением поколения. Автоматическое переключение между VPN и БС.
- **Anubis:** включение и отключение VPN для выбранных приложений, заморозка приложений через Shizuku или встроенные инструменты MCRF.
- **Гибкая маршрутизация:** домены, IP, GeoIP/GeoSite и приложения — через VPN, напрямую или в блокировку. Отдельный DNS, готовый список российских приложений и предупреждение об их работе через VPN с настройкой в один клик. Отдельные серверы и движки для маршрутов — в планах.
- **Конфигурации и резервирование:** Happ, Incy, Clash/Mihomo, резервные цепочки и исключения автовыбора.
- **Android и Windows:** Telegram WS и WARP на Android; Zapret и ByeTube с автоподбором и мастером настройки на Windows.
- **Android TV:** тот же универсальный APK, управление пультом и передача подключений с телефона по коду.
- **Контроль и диагностика:** статистика серверов, монитор нагрузки, энергосбережение, лимиты трафика, захват IP, диагностика соединения и история изменений.

## Android в действии

| Запуск и серверы | Российские приложения и маршруты | Оформление |
| --- | --- | --- |
| <img src="docs/screenshots/android-startup-full.gif" alt="Запуск приложения и выбор сервера" width="260"> | <img src="docs/screenshots/android-ruapp-full.gif" alt="Предупреждение о российских приложениях и настройка маршрутов" width="260"> | <img src="docs/screenshots/android-appearance-full.gif" alt="Настройки оформления" width="260"> |

## Версия для ПК

[Отдельный репозиторий MCRF для Windows](https://github.com/vnenapravo7-source/mcrf-desktop).

## Документация

[Android](docs/ANDROID-GUIDE.md) · [Исходники](docs/DEVELOPMENT.md) · [Сообщить об ошибке](https://github.com/vnenapravo7-source/mcrf/issues)

Исходники, лицензии и контрольные суммы приложены к релизам.
