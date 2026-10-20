# Компоненты переносимых сборок

- MCRF: исходники интерфейсов и адаптеров — каталог `ports` данного проекта. Переносы ещё не опубликованы как релизы.
- sing-box-lx / libbox: GPL-3.0-or-later, [исходники v1.14.2-lx.11, 3d3d3db](https://github.com/Leadaxe/sing-box-lx/tree/3d3d3db3a2ccef3b1a8eb71b831ed2461c54b94d). Android-сборка `1.14.2-lx.11-mcrf-clash.1`: исходники не изменены, к исходным тегам сборки добавлен `with_clash_api`. Точные зависимости и инструменты: `ports/android/android-core-lock.json` и `ports/android/native-core/build.ps1`. Это отдельная сборка, не исходный бинарный файл автора. Уведомление автора и полный текст лицензии сохранены в `licenses/sing-box-NOTICE.txt` и `licenses/sing-box-GPL-3.0.txt`.
- Avalonia: MIT, [исходники 11.3.7](https://github.com/AvaloniaUI/Avalonia/tree/11.3.7).
- .NET: MIT и лицензии сторонних компонентов; [runtime](https://github.com/dotnet/runtime).
- Gradle Wrapper: Apache-2.0, [Gradle 9.1.0](https://github.com/gradle/gradle/tree/v9.1.0).
- ZXing core 3.5.3: Apache-2.0, офлайн-сканирование и генерация QR, [исходники](https://github.com/zxing/zxing/tree/zxing-3.5.3).
- Telegram SOCKS/WS: MIT, [y0sy4/telegram-proxy, cc6b7928](https://github.com/y0sy4/telegram-proxy/tree/cc6b7928e7306509bdeefbb6e0e29f2a982bdb48), адаптер/патчи `ports/android/transports/telegram`, полный текст в `licenses/tgws-LICENSE`. golang.org/x/net v0.52.0: BSD-3-Clause.

До публикации Linux/Android релиза нужно приложить полные тексты лицензий, уведомления транзитивных зависимостей и соответствующие исходники приложения и GPL-компонентов. Тестовые пакеты не заменяют опубликованный комплект исходников.
