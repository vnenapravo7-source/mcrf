# P0/44: Direct family retry — кандидат для общих проверок, НЕ ПРИНЯТО

Контроль19окт: native632c host/tag+stub/3ABI/API/ELF и external7 APK gates
пройдены. External7 установлен на Samsung, main10018/20protected/14othermodules
не изменены (phone9ae6d001). В контролируемой новой acceptance стандартный
Direct, appDirect RULE и appVPN RULE открыли YandexROOT; appBLOCK запретил
доступ. IPv4compatOFF/TUNIPv6ON. Полная приёмка НЕ объявлена: Vivo не проверен;
IPv6 на шведских профилях дал закрытие соединения, но тот же literal IPv6
через сохранённый NL10.09 GRPC дал HTTPS trace с IPv6 egress/TLS1.3 (кадр74).
Таким образом общего запрета IPv6 нет; точная причина шведских профилей
не установлена. Не выдавать ограниченные проверки за полный IPv6 PASS.
Старая пометка о модуле6 ниже относится к исходной подготовке, не к доставке7.

Основа: sing-box-lx `3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d`,
sing-tun `6f56eca9cb2e81d1c233b6104618ff30500e9f39`.
Живой upstream checkout, пины и установленный модуль6 не изменены.

Полевой случай10018: системный DNS домашнего роутера выдаёт A198.18.0.1
и AAAAfdfe:dcba:9876::c612:1 для Яндекса; отдельный запрос к тому же
роутеру подтвердил оба ответа. Chrome принимает локальный TCP, затем
получает TLS -100. СовместимостьIPv4 устраняет симптом на том же сервере.
Это НЕ доказательство универсального дефекта IPv6 движка или причины Vivo.

Контракт: только уже выбранный Direct TCP/TUN, подтверждённый HTTP/TLS hostname,
реальная ошибка ENETUNREACH/EHOSTUNREACH/EADDRNOTAVAIL. Внутренний A lookup
через существующую DNS-политику и повторная проверка того же правила для
кандидатаIPv4. Иное правило (включая VPN/BLOCK) запрещает попытку. Никакого
запрета AAAA/IPv6, смены DNS-провайдера, профиля, OS bypass или Direct→VPN.
Не повторять успешно установленное TCP/TLS или прикладные данные.

Дельта: два новых route-файла + один помеченный seam в route.go. Тег
with_mcrf_direct_family включать только в новой неизменяемой сборке внешнего
модуля; без тега stub возвращает исходный dialer. Условие снятия: upstream
реализовал эквивалентный same-policy retry; проверять при обновлении пина.

Подготовлены негативные fixtures (реальный native matcher для VPN/BLOCK,
смены Direct-правила/TLS options; cancel/timeout, TLS errors, успешныйIPv6,
unknown hostname, UDP, cap/dedup), сравнение всех dial/TLS route options,
теговый stub, pin/hash source-freezer и один grouped native producer.
Версия кандидата1.14.2-lx.11-mcrf-direct-family.1, внешний модуль7; Java/SPI
поверхность без изменений, адаптер берётся из проверенного модуля6.
Приватные журналы/адрес роутера в исходник/лицензии не включаются.

До доставки обязательны общие host gates с тегом/без, сборка3ABI,
JavaAPI/ELF/notice/подпись/APK-identity, контролируемая установка ТОЛЬКО
внешнего модуля и реальное сравнение Direct с compatibilityOFF/IPv6ON,
включая отрицательную проверку маршрутов. Успех компиляции НЕ приёмка.
На момент подготовки НЕ применён к живому upstream, НЕ компилирован,
НЕ установлен. P1/capabilities/publication остаются выключенными.
