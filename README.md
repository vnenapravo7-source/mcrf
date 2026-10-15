# MCRF

MCRF — мультиплатформенный клиент с широкой поддержкой транспортов.

| Платформа | Скачать / статус | Основной VPN / прокси | Обход белых списков · доп. движки | Дополнительно |
| --- | --- | --- | --- | --- |
| Windows x64 | [v1.0.11](https://github.com/vnenapravo7-source/mcrf/releases/tag/v1.0.11) | VLESS, VMess, Trojan, Shadowsocks, Hysteria/2, TUIC, AnyTLS, NaiveProxy, SSH, HTTP/SOCKS5, WireGuard/AmneziaWG | CSQTT, WDTT, QWDTT, OpenFlux | Zapret, ByeTube, Telegram WS Proxy, WARP, автодеплой |
| Android | [v0.1.0 · тестовая версия](https://github.com/vnenapravo7-source/mcrf/releases/tag/android-v0.1.0-preview.20261015.1) | Через sing-box: VLESS, VMess, Trojan, Shadowsocks, Hysteria/2, TUIC, AnyTLS, NaiveProxy, SSH, HTTP/SOCKS5, WireGuard/AmneziaWG; Steer — ограниченный набор | CSQTT, WDTT, QWDTT, OLCRTC, DNSTT; OpenFlux — в доработке | Telegram WS Proxy, WARP, Xray/TLS-модули, раздача HTTP/SOCKS5, автодеплой |
| Linux | В разработке; опубликованного выпуска пока нет | Набор протоколов проходит приёмку | Набор транспортов проходит приёмку | Возможности будут перечислены после проверки |

Наличие протокола не означает проверку всех серверов и сетей. Steer пока ограничен IPv4 и не заменяет весь набор sing-box; рабочий HTTPS через OpenFlux Android ещё не подтверждён.

## Основное

- Импорт серверов и подписок, проверка задержки, VPN и локальный прокси.
- Маршруты по доменам, IP и приложениям; DNS, GeoIP/GeoSite и готовые пресеты.
- WARP, Telegram WS, автодеплой и дополнительные транспорты.
- На Windows также доступны Zapret и ByeTube.

Возможности зависят от платформы и движка. Android — промежуточная сборка: параллельная маршрутизация через разные ядра ещё не готова; работа OpenFlux пока не подтверждена.

## Движки отдельно

На Android sing-box и Steer устанавливаются отдельно от основного APK. Выбор и установка — в меню движков; используйте пакет своей архитектуры.

[Пакеты движков — Android и Windows](https://github.com/vnenapravo7-source/mcrf/releases/tag/engines-2026-10-13-1)

## Интерфейс

![Windows — главная](docs/screenshots/home-wpf-1.0.11.png)

<details>
<summary>Android</summary>

<img src="docs/screenshots/android-tour.gif" alt="Android — реальные переходы и прокрутка интерфейса" width="360">

Демонстрационные серверы, без подключения к сети. [Расширенный обзор Android](docs/ANDROID-GUIDE.md).

</details>

## Документация

[Windows: подробное руководство](docs/WINDOWS-GUIDE.md) · [Android: расширенный обзор](docs/ANDROID-GUIDE.md) · [Сборка](docs/BUILD.md) · [Исходники](docs/DEVELOPMENT.md) · [Сообщить о проблеме](https://github.com/vnenapravo7-source/mcrf/issues)

Контрольные суммы, исходники и лицензии компонентов — рядом с пакетами в релизах.


