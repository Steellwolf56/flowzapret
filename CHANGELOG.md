# Changelog

Все заметные изменения FlowZapret. Формат основан на
[Keep a Changelog](https://keepachangelog.com/), версии — [SemVer](https://semver.org/).

## [Unreleased]

## [1.2.0] — 2026-11-07

### Added
- **Обновляемые компоненты** (меню → Компоненты): ядро ByeByeDPI
  (`libbyedpi.so`), ядро Telegram-прокси (`libtgwsproxy.so`) и пресеты Flowseal
  (`flowseal_presets.json`) обновляются без пересборки APK.
  - Манифест обновлений `components.json` с подписью `components.json.sig`
    (ECDSA P-256 / SHA-256).
  - Проверка артефактов по SHA-256, размерам, ELF-заголовку и ABI.
  - Атомарная установка в `filesDir/components/<name>/<version>/` с откатом
    к встроенной версии при ошибке.
  - Ручной импорт компонентов из zip-архива и «Сбросить к встроенным».
  - Скрипты `tools/build-components.sh`, `tools/publish-components.sh`,
    `tools/generate-signing-key.sh` и документация [docs/COMPONENTS.md](docs/COMPONENTS.md).
- Экран «Логи» для Telegram-прокси с фильтрами INFO/ERROR/NULL, копированием
  и сохранением в файл.
- Подсказки для расширенных опций прокси (Fake TLS, PROXY-протокол).

### Changed
- Управление Telegram-прокси переведено на переключатель «Вкл | Выкл» со
  строкой состояния (выключен / запуск / работает / остановка).
- `TgProxyService`: все команды (старт/стоп/перезапуск) проходят через одну
  последовательную очередь; каждый старт несёт полный снимок настроек,
  поэтому перезапуск из уведомления больше не сбрасывает параметры.
- Пресеты Flowseal перенесены в `assets/flowseal_presets.json`; при наличии
  установленного компонента используется его версия.

### Fixed
- Нативное ядро прокси: заголовок PROXY protocol потребляется только при
  реальном его наличии (`peek` вместо безусловного чтения первой строки).
  Обычный MTProto-handshake больше не терял начало и не выдавал
  «expected PROXY header».
- Нативное ядро прокси: Fake TLS определяет режим по первому байту
  (`0x16` — TLS, HTTP → 301-редирект, иначе — raw MTProto), поэтому ссылка с
  `dd`-секретом работает без ложных редиректов.

## [1.1.0] — 2026-11-07

### Added
- Вкладка **«Telegram»** — MTProto-прокси на Rust-ядре
  [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), работающий независимо
  от режима обхода DPI.
- Ссылка на прокси для Telegram (применение в клиенте / копирование),
  генерация и копирование секрета.
- Настройки прокси: DC auto/manual, DC1–DC5 и media-DC, Fake TLS, кастомный
  домен CF, тест DC, `no-secure`, PROXY-протокол, пул, буфер, порт, автозапуск.
- Foreground-сервис с уведомлением: перезапуск и остановка из шторки.

## [1.0.0] — 2026-11-06

### Added
- Форк [ByeByeDPI](https://github.com/romanvht/ByeDPIAndroid) под пакетом
  `com.flowzapret`; собственное имя и иконка.
- 22 стратегии [Flowseal](https://github.com/Flowseal/zapret-discord-youtube),
  добавленные в историю команд как закреплённые именованные пресеты.
- Доменные списки Flowseal (`Flowseal General`, `Flowseal Google`).
- Сборка только под ARM (`armeabi-v7a`, `arm64-v8a`) + universal APK.
- Вкладка «Обход DPI» с VPN-режимом и локальным SOCKS-прокси.

[Unreleased]: https://github.com/Steellwolf56/flowzapret/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/Steellwolf56/flowzapret/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/Steellwolf56/flowzapret/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/Steellwolf56/flowzapret/releases/tag/v1.0.0
