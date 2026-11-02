# MCRF

VPN и прокси для Android и Windows с гибкой маршрутизацией и настраиваемым интерфейсом. Linux и iOS версия в разработке.

[![Все файлы](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fvnenapravo7-source%2Fmcrf%2Fmain%2F.github%2Fdownload-counts.json&query=%24.total&label=downloads%20total&color=555&style=flat-square)](https://github.com/vnenapravo7-source/mcrf/releases)
[![Windows](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fvnenapravo7-source%2Fmcrf%2Fmain%2F.github%2Fdownload-counts.json&query=%24.windows&label=downloads%20Windows&color=0078d4&style=flat-square)](https://github.com/vnenapravo7-source/mcrf/releases)
[![Android](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fvnenapravo7-source%2Fmcrf%2Fmain%2F.github%2Fdownload-counts.json&query=%24.android&label=downloads%20Android&color=3ddc84&style=flat-square)](https://github.com/vnenapravo7-source/mcrf/releases)
[![Движки](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fvnenapravo7-source%2Fmcrf%2Fmain%2F.github%2Fdownload-counts.json&query=%24.engines&label=downloads%20engines&color=8a2be2&style=flat-square)](https://github.com/vnenapravo7-source/mcrf/releases/tag/engines-2026-10-13-1)

## Скачать

| Платформа | Версия |
| --- | --- |
| Windows x64 | [1.2](https://github.com/vnenapravo7-source/mcrf/releases/tag/v1.2) · [Скачать EXE](https://vnenapravo7.ru/mcrf-downloads/d/678292801) |
| Android 10+ | [0.7.3 — текущий опубликованный выпуск](https://github.com/vnenapravo7-source/mcrf/releases/tag/android-v0.7.3) |
| iOS | В разработке |
| Linux | В разработке |


### Движки и протоколы

| Движок | Протоколы и назначение | Размер, МБ |
| --- | --- | --- |
| [sing-box-lx](https://github.com/Leadaxe/sing-box-lx)<br><sub>Кастомная сборка MCRF</sub> | VLESS / REALITY, VMess, Trojan, Shadowsocks, Hysteria / Hysteria 2<br>TUIC, AnyTLS, NaiveProxy, SSH, HTTP / HTTPS, SOCKS5, WireGuard / AmneziaWG | 30,5 |
| [Steer 0.3.3](https://github.com/splify2/steer)<br><sub>Доработанный порт MCRF, модуль 16</sub> | VLESS / REALITY, VMess, Trojan, Shadowsocks, Hysteria 2<br>HTTP / HTTPS, SOCKS4 / 4a / 5 | 10,4 |
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
| [Zapret](native/zapret-engine/README.md) / [ByeDPI](https://github.com/hufrea/byedpi) · ByeTube<br><sub>Windows: Zapret — расширение MCRF; ByeDPI — сторонний</sub> | Обход глубокой фильтрации трафика: TCP / UDP и локальный SOCKS-прокси | В приложении Windows |


## Возможности

- **Автоопределение БС на Android:** регулярная проверка доступности сети и автоматическое переключение VPN ↔ белые списки с уведомлением. Включается отдельно.
- **Гибкий дизайн:** светлая и тёмная темы, свои цвета, узоры, значки, минимализм, рандомайзер и анимации подключения.
- **Маршрутизация под свои задачи:** домены, IP, приложения, GeoIP/GeoSite, сервисные списки, приоритеты и отдельный DNS. Действия — напрямую, VPN или блокировка.
- **Большой выбор протоколов и движков:** VLESS, VMess, Trojan, Shadowsocks, Hysteria/2, WireGuard/AmneziaWG, HTTP/SOCKS5; дополнительные TURN- и DNS-транспорты. Возможности конкретного протокола зависят от выбранного движка.
- **Telegram WS-прокси**, WARP и локальный HTTP/SOCKS5 с авторизацией.
- **На Windows:** Zapret/ByeTube, редактор и генератор стратегий, игровые профили и маршрутизация обхода по приложениям.

## Android в действии

| Подключение и отключение | Оформление | Функции | Разделы |
| --- | --- | --- | --- |
| <img src="docs/screenshots/android-connection.gif" alt="Подключение и отключение" width="220"> | <img src="docs/screenshots/android-appearance.gif" alt="Оформление и смена анимации" width="220"> | <img src="docs/screenshots/android-functionality.gif" alt="БС, серверы и маршруты" width="220"> | <img src="docs/screenshots/android-sections.gif" alt="Настройки, DNS, прокси и движки" width="220"> |

Записи настоящего приложения с существующими серверами. [Разделы и скриншоты подробнее](docs/ANDROID-GUIDE.md).

## Windows

![Windows — главная](docs/screenshots/home-windows-1.1.png)

![Windows — конструктор](docs/screenshots/constructor-windows-1.1.png)

[Все разделы Windows](docs/WINDOWS-GUIDE.md).

## Документация

[Android](docs/ANDROID-GUIDE.md) · [Windows](docs/WINDOWS-GUIDE.md) · [Сборка](docs/BUILD.md) · [Исходники](docs/DEVELOPMENT.md) · [Сообщить об ошибке](https://github.com/vnenapravo7-source/mcrf/issues)

Исходники, лицензии и контрольные суммы приложены к релизам.
