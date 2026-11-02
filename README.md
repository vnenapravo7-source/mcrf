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

## Пул реализации Android

<details>
<summary>Показать все задачи и статусы по приоритетам</summary>

Актуализация: **2 ноября 2026**. Сначала перечислены ранее реализованные задачи, затем незавершённые пункты выпуска 2.0; внутри групп сохранён приоритет П0 → П4.

«Исходники готовы» — код подготовлен, итоговая приёмка открыта. «Проверяется» и «Частично» описывают незавершённую проверку указанного объёма; «Нужен аудит» — статус ещё требуется установить. Общая приёмка 2.0 не переоткрывает прежние закрытые задачи.

<a id="доработки-после-review-steer"></a>

| № | Приоритет | Область / задача | Статус | Примечание |
| --- | --- | --- | --- | --- |
| 1 | П0 | Подключение и локальный HTTP/SOCKS5-прокси | Готово | Выбор движка, фоновый запуск, смена сети, состояние и счётчики. |
| 2 | П0 | Маршрутизация по доменам, IP и приложениям | Готово | Маршруты по доменам, IP и приложениям; GeoIP/GeoSite. |
| 3 | П0 | Серверы и подписки | Готово | Импорт, экспорт и обновление подписок. |
| 4 | П0 | Автоопределение БС и проверка перед подключением | Готово | Проверка доступности перед подключением. |
| 5 | П0 | Локальные зашифрованные копии и восстановление | Готово | Зашифрованное резервное копирование и восстановление. |
| 6 | П0 | Обновления приложения и отдельных движков | Готово | Проверка подписи и целостности, установка и удаление движков. |
| 7 | П0 | Оформление, настройки и первый запуск | Готово | Темы, оформление, поиск и настройки. |
| 8 | П0 | VK/TURN и Telegram WS: подключение и настройки | Готово | Подключение VK/TURN и Telegram-прокси. |
| 9 | П0 | Логи и отчёт об ошибке | Готово | Фильтры и отчёт без секретов. |
| 10 | П1 | Компактный WireGuard/AmneziaWG 3.0/3.1 | Готово | Отдельный компактный модуль. |
| 11 | П2 | Автопул и отдельные серверы маршрутов | Готово | Ручной выбор и автоматические проверки серверов. |
| 12 | П2 | Расширенные правила, группы и DNS | Готово | Группы, приоритеты, GeoIP/GeoSite и DNS. |
| 13 | П2 | Редактор параметров соединения | Готово | Настройки TLS, MUX, uTLS и QUIC. |
| 14 | П2 | CSQTT, SWG Panel и Rebirth: импорт и параметры TURN | Готово | Импорт и настройка TURN. |
| 15 | П2 | Панели, подключения и именованные установки | Готово | Панели и именованные установки. |
| 16 | П2 | WARP: регистрация, поиск и WG/AWG-профили | Готово | Регистрация и поиск WARP-серверов. |
| 17 | П2 | Счётчики и снижение фоновой активности | Готово | Счётчики и экономия ресурсов в фоне. |
| 18 | П3 | OpenFlux 0.16: Direct, FluxFast и переподключение | Готово | Direct, FluxFast и переподключение. |
| 19 | П3 | Дополнительные модули и архитектуры | Готово | Дополнительные модули, импорт и обновления. |
| 20 | П3 | Восстановление после пробуждения, запасной движок и Kill switch | Готово | Восстановление подключения и Kill switch. |
| 21 | П3 | Steer: IPv4 TCP VLESS через host-owned TUN | Готово | IPv4/TCP, protect/bind, GET/POST и STOP. |
| 22 | П0 | Интерфейс 2.0 и обновление каталога в фоне | Проверяется | Нижняя навигация, карточки, диалоги и редакторы маршрутов/DNS, сворачиваемые настройки; снимки каталога при старте/возврате. Исходники готовы; итоговая компиляция и визуальная приёмка открыты, ускорение не измерено. |
| 23 | П0 | Steer: маршруты, DNS, IPv6, локальный прокси и безопасная работа с сокетами | Частично | Принятые маршруты, DNS, IPv6, локальный прокси, callbacks и локальный IPv4 TUN сохранены. Полный аудит вспомогательных путей и защиты от повторного закрытия/повторного использования файловых дескрипторов ещё требуется. |
| 24 | П1 | Steer: расширенная DNS/negative/load-матрица | Частично | A/AAAA, SERVFAIL/no-fallback, proxy-auth и TLS refusal проверены в своём объёме. TTL/CNAME/очистка synthetic IPv6, очереди/overflow, нагрузка и полная DNS/.srs/regex/handshake-матрица требуют проверки. |
| 25 | П2 | Общий BS и совместимость сохранённых CSQTT | Проверяется | CSQTT 2.0/2.1.5/2.1.9, SWG 2.5.1/2.5.2/2.5.3 и QWDTT; WDTT отдельно. BS2.0-bs.4 собран/установлен. Автоподбор, миграция ссылок/подписок/правил, VK/Flood backoff, TURN-подсети и ручные потоки подготовлены; полная серверная/process-death-приёмка и доставка открыты. |
| 26 | П2 | Установка одного/всех движков и общие карточки VPN/БС | Исходники готовы | Общий BS скачивается один раз; итоговая UI/installer-приёмка ожидается. |
| 27 | П2 | Автодеплой актуальной официальной версии SWG | Исходники готовы | Одна закреплённая версия на установку. Компиляция и локальные проверки пройдены; дополнительная проверка исправления и удалённая установка открыты. |
| 28 | П2 | Steer: событийное ожидание и повторное использование DNS/.srs через C/JNI | Нужен аудит | Свежего подтверждения завершения этих замечаний review нет. |
| 29 | П3 | Все реальные переходы VPN ↔ БС и сквозная IPv4/IPv6-матрица | Частично | Ранее принятые сценарии не переоткрываются; все сети и движки ещё не проверены. |
| 30 | П3 | Полная совместимость провайдеров, серверов и физических ABI | Частично | VK/CAPTCHA/MASQUE, OLCRTC/DNSTT, SSTP/MasterDnsVPN, пары CSQTT/форков, ARMv7/x86_64. Наличие UI или APK не закрывает эту матрицу. |
| 31 | П3 | Установка на конкретном проблемном Huawei | Проверяется | Исправления установщика готовы; подтверждения на этом OEM нет. |
| 32 | П3 | OpenFlux: внешние документные провайдеры и экспериментальные режимы | Частично | Ранее принятые Direct/FluxFast не доказывают все NAT/provider/ownHTTP/inline/script-сценарии. |
| 33 | П4 | Простой и экспертный режимы | Исходники готовы | Скрытие сложных настроек без потери значений; отдельные наборы правил. Итоговая приёмка открыта. |
| 34 | П4 | Профили поведения и расписания | Исходники готовы | Wi-Fi, мобильная сеть и SSID; реальные сетевые переходы требуют приёмки. |
| 35 | П4 | Автовосстановление с учётом причины отказа | Проверяется | Защита от частых переключений; не переоткрывает готовый запасной движок. |
| 36 | П4 | Проверка конфликтов и временные маршруты | Исходники готовы | Пересечения правил, выбор отключаемого правила; приоритетные маршруты на 5–60 минут. |
| 37 | П4 | Диагностика сервисов, сравнение серверов и A/B транспортов | Проверяется | Отдельные ошибки запуска/сервиса и ограничения измерения. Обычные VPN прошли локальные HTTPS/DoH; живой БС дал timeout, полная runtime-приёмка открыта. |
| 38 | П4 | Резервные цепочки и отдельный движок маршрута | Частично | Цепочки подготовлены; вся сквозная матрица per-route engines при едином владельце VPN/TUN не подтверждена. |
| 39 | П4 | Запись трафика и предложения правил | Проверяется | Beta: метаданные системы/приложений, ручной запуск/таймер, журнал по приложениям/IP/наблюдаемым доменам, очистка/экспорт и ограниченное хранение. Без содержимого пакетов; предложения правил подготовлены. |
| 40 | П4 | История конфигурации и безопасное восстановление | Проверяется | Beta: история/откат; предпросмотр профиля/восстановления, атомарный набор файлов и точка отмены подготовлены. Новая Android-приёмка сохранности/undo открыта. |
| 41 | П4 | Монитор ресурсов и окно поверх приложений | Исходники готовы | Beta: три размера, CPU/память процесса MCRF и пассивный трафик UID. Последние изменения и overlay/lifecycle требуют общей приёмки. |
| 42 | П4 | Энергосбережение, батарея и квоты трафика | Исходники готовы | Beta: порог 1–100%/системный battery saver; таблица измерений и факторов Android; точные МБ/ГБ, календарные периоды и действия по лимиту. Общая проверка и реальное применение открыты. |
| 43 | П4 | Диагностика IPv6 и пассивные сведения о сети Android | Исходники готовы | Итоговая приёмка ожидается. |
| 44 | П4 | Переход в Telegram-группу сообщества | Исходники готовы | С логотипа и из настроек; обработчик ссылок/device-приёмка открыты. |
| 45 | П4 | OpenFlux: официальный upstream и обновление JS-транспортов | Исходники готовы | Интеграция 0.17.1 из p1neappleXpress/OpenFlux; check/apply/recovery/rollback пакетов p1neappleXpress/OpenFluxTransports. Новый native/APK ещё не собран, runtime/провайдеры требуют приёмки. |
| 46 | П4 | Финальный выпуск 2.0 и сохранность данных | Проверяется | Общий снимок, проверки, исходники и публикация. Навигационные сценарии прошли, но контроль обнаружил изменения защищённых файлов; причина и новая restore/undo-приёмка открыты. |
| 47 | П4 | Измерение старта/возврата, upload и активный тест скорости | Запланировано | Ускорение и throughput не доказаны конфигурационными тестами; пассивный монитор не заменяет speed test. |
| 48 | П4 | Расширенное управление серверами и автодеплой S3 Bypass | Отложено | Существующие серверы и подключения S3 Bypass сохранены. |
| 49 | П4 | Автодеплой: полная провайдерская и UI-матрица | Частично | Ранее принятые ручные/именованные установки сохранены; внешние сценарии и CAPTCHA требуют проверки. |


</details>

## Windows

![Windows — главная](docs/screenshots/home-windows-1.1.png)

![Windows — конструктор](docs/screenshots/constructor-windows-1.1.png)

[Все разделы Windows](docs/WINDOWS-GUIDE.md).

## Документация

[Android](docs/ANDROID-GUIDE.md) · [Windows](docs/WINDOWS-GUIDE.md) · [Сборка](docs/BUILD.md) · [Исходники](docs/DEVELOPMENT.md) · [Сообщить об ошибке](https://github.com/vnenapravo7-source/mcrf/issues)

Исходники, лицензии и контрольные суммы приложены к релизам.
