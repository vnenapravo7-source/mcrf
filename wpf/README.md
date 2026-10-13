# Интерфейс MCRF

Windows-интерфейс на WPF, общий сетевой код — в `../native/`.

[Описание всех разделов и скриншоты](../README.md#разделы-программы) · [Сборка](../docs/BUILD.md) · [Структура исходников](../docs/DEVELOPMENT.md)

- `MainWindow.cs`, `ShellPages.cs`, `Theme.xaml` — окно, навигация и оформление.
- `ClientSession.cs` — профиль и управление сетевыми модулями.
- `Route*.cs`, `RoutingHosts.cs`, `ProcessCapture.cs` — маршруты, hosts и захват процесса.
- `ServerPages.cs`, `ServersWorkspace.cs`, `ModulePages.cs` — серверы, WARP и обновления.
- `ZapretWorkspace.cs`, `StrategyPages.cs`, `SetupPages.cs` — профили, редактор, генератор и проверки.
- `Deployment*.cs`, `Provisioning.py` — SSH-установка и создание подключений.
- `VkBrowser.cs`, `VkCallFields.cs` — вход VK и ссылки звонков.
- `JournalView.cs`, `ProfileActions.cs`, `HotkeyCapture.cs`, `Tray.cs` — журнал, профиль, горячие клавиши и трей.
- `tests/` — локальные проверки и генератор демонстрационных скриншотов.

Пользовательский файл — `mcrf.exe`. Профиль — `%LOCALAPPDATA%\mcrf-wpf`; этот путь сохранён для совместимости.






