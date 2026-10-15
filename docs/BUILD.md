# Сборка MCRF

## Исходники

- `wpf/*.cs`, `wpf/Theme.xaml`, `wpf/Provisioning.py` — новый Windows-интерфейс, автодеплой и выдача подключений.
- `native/*.cs` — общая модель, управление Windows-движками и сохранённые исходники старого интерфейса.
- `native/assets/` — оформление, встроенные публичные списки и стратегии.
- `native/zapret-engine/` — расширение фильтрации приложений, патч и сборка Zapret.
- `native/turn-engines/` — мост CSQTT и скрипты сборки дополнительных движков.

Пользовательские профили, подписки, пароли, журналы и рабочие каталоги не входят в репозиторий.

## Требования

Windows x64 и .NET Framework 4.8 с компилятором
`%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe`.

**Чистого клона пока недостаточно для сборки EXE:** `native/build.ps1` встраивает
сторонние бинарные ресурсы и их лицензии из локального `work/`.
Они не добавлены в Git. Точные пути перечислены в `$resources` и ссылках `/r:`
этого скрипта; отсутствие файла останавливает сборку.

| Ресурсы | Источник / расположение |
| --- | --- |
| sing-box-lx и libcronet | [v1.14.2-lx.11](https://github.com/Leadaxe/sing-box-lx/releases/tag/v1.14.2-lx.11), `work/sing-box-lx/` |
| ByeDPI и лицензия | [hufrea/byedpi](https://github.com/hufrea/byedpi), `work/byedpi/` |
| WebView2 SDK, загрузчик и лицензии | [Microsoft.Web.WebView2](https://www.nuget.org/packages/Microsoft.Web.WebView2), `work/webview-sdk/package/` |
| SSH.NET и зависимости | Закреплённые версии и ресурсы в `native/build.ps1`, `work/sshnet/` |
| QRCoder и MIT-лицензия | [QRCoder 1.6.0](https://www.nuget.org/packages/QRCoder/1.6.0), `work/qrcode/qrcoder-1.6.0/`; DLL встраивается в EXE |
| Telegram WS | [v2.0.7](https://github.com/y0sy4/telegram-proxy/releases/tag/v2.0.7), `work/tgws/` |
| WarpScout | [v0.16.0](https://github.com/vernette/warpscout/releases/tag/v0.16.0), `work/warpscout/` |
| curl, CA и лицензии | [curl for Windows](https://curl.se/windows/), `work/curl/` |
| Flowseal, стратегии, WinDivert, модифицированный winws и Cygwin | `work/zapret/`; [сборка и исходники расширения](../native/zapret-engine/README.md) |
| Лицензия OpenFlux | [OpenFlux](https://github.com/p1neappleXpress/OpenFlux), `work/openflux/LICENSE` |
| Каталог дополнительных движков | [Релиз с QWDTT](https://github.com/vnenapravo7-source/mcrf/releases/tag/engines-2026-10-13-1), `native/assets/optional-engines.json` |

Версии и проверенные контрольные суммы части компонентов описаны в
[документации разработчика](DEVELOPMENT.md). Используйте согласованные версии,
а не произвольные последние сборки. Автоматическая подготовка всех зависимостей
и побайтовая воспроизводимость релизного EXE пока не подтверждены.

После подготовки ресурсов:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File native/build.ps1 -Wpf -OutputPath outputs/native/mcrf.exe
```

Результат: `outputs/native/mcrf.exe`. Скрипт создаёт иконку и сжимает встроенные
EXE/DLL без потери данных. Для компиляции запуск самого MCRF не требуется.

Для сохранённой версии релиза используется `outputs/native/release-1.0.11/mcrf.exe`.
`AssemblyVersion`/`AssemblyFileVersion` — 1.0.11.0, `AssemblyInformationalVersion` — 1.0.11.
Приложение и тестовые EXE собираются последовательно: генератор значка общий.

Локальные проверки: `wpf/tests/ReleaseVerification20261013.ps1`. Тесты используют отдельные профили и не устанавливают панели на сервер. Для воспроизведения проверки закреплённого OpenFlux-установщика нужен `work/deployment-sources/openflux/deploy.sh` с ревизии, указанной в `wpf/Deployment.cs`; живые тесты запускаются отдельно только на разрешённом сервере.

## Дополнительные движки и лицензии

Скрипты: `native/turn-engines/build.ps1`, `native/zapret-engine/build.ps1` и
`native/package-engines.ps1`. Они требуют отдельных инструментов и исходников,
указанных внутри скриптов. Исходные архивы дополнительных движков доступны в
[релизе движков](https://github.com/vnenapravo7-source/mcrf/releases/tag/engines-2026-10-13-1).

Лицензии сторонних компонентов сохраняются; публикация кода MCRF не заменяет их
условия. Общая лицензия на собственный код приложения пока не выбрана.
