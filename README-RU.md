# FlowZapret

Android-приложение для обхода DPI-блокировок, форк
[ByeByeDPI](https://github.com/romanvht/ByeDPIAndroid) с набором стратегий
[Flowseal / zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube).

- Пакет: `com.flowzapret`
- Минимальная версия Android: 8.0 (API 26)
- Архитектуры: `armeabi-v7a`, `arm64-v8a` (universal APK включён)
- Движок: [ByeDPI / ciadpi](https://github.com/hufrea/byedpi) + hev-socks5-tunnel
- Лицензия: GPL-3.0

## Возможности

- Две свайпаемые вкладки: «Обход DPI» и «Telegram».
- Telegram MTProto-прокси (Rust-ядро tg-ws-proxy) работает независимо от
  VPN/прокси-режима обхода DPI; оба режима можно включить одновременно.
- VPN-режим (hev-socks5-tunnel) и режим локального SOCKS-прокси.
- 22 стратегии Flowseal, добавленные в историю команд как закреплённые
  именованные пресеты. Пресеты видны в основном списке стратегий и в
  автоподборе «Подбор стратегий».
- Доменные списки Flowseal (`Flowseal General`, `Flowseal Google`) —
  белые списки: трафик банков, госуслуг и прочих не входящих в список
  доменов не затрагивается. Списки доступны в настройках доменных
  списков и всегда используются стратегиями через `{list:...}`.
- Автоматический подбор стратегий с проверкой доступности сайтов.
- Настройки командной строки ByeDPI, редактор UI-настроек, быстрые
  плитки, ярлыки, автозапуск.

## Пресеты

Все 22 стратегии Flowseal в исходном порядке:

| # | Пресет | Что делает (ByeDPI) |
|---|--------|---------------------|
| 1 | General | multisplit pos=1 |
| 2 | ALT | disorder + fake |
| 3 | ALT 2 | multisplit pos=2 |
| 4 | ALT 3 | fake + случайный TLS + SNI |
| 5 | ALT 4 | split + fake, TTL 9 |
| 6 | ALT 5 | multidisorder |
| 7 | ALT 6 | multisplit pos=1 + доп. позиция |
| 8 | ALT 7 | multisplit pos=2 + sniext+1 |
| 9 | ALT 8 | fake, TTL 10 |
| 10 | ALT 9 | fake + disorder + подмена SNI |
| 11 | ALT 10 | fake + случайный TLS, TTL 7 |
| 12 | ALT 11 | split + fake + случайный TLS |
| 13 | ALT 12 | split + fake / hostfakesplit |
| 14 | ALT 13 | fake + hostfakesplit (SNI mail.ru) |
| 15 | EXP | экспериментальный split + fake |
| 16 | Fake TLS AUTO | disorder midsld + fake |
| 17 | Fake TLS AUTO ALT | split + fake + SNI |
| 18 | Fake TLS AUTO ALT 2 | multisplit + fake, TTL 9 |
| 19 | Fake TLS AUTO ALT 3 | multisplit + fake |
| 20 | Simple Fake | fake + случайный TLS |
| 21 | Simple Fake ALT | fake, TTL 9 |
| 22 | Simple Fake ALT 2 | fake + случайный TLS, TTL 10 |

Подробное соответствие аргументов и список несовместимостей —
в [docs/flowseal-mapping.md](docs/flowseal-mapping.md).

## Сборка

Требования: JDK 17+, Android SDK 36, NDK 27.2.11906872, CMake 3.22.1.

```bash
cp local.properties.example local.properties   # или создать вручную
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

`local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

### Подпись

Release-сборка подписывается ключом из `keystore.properties` в корне проекта:

```properties
storeFile=flowzapret-release.jks
storePassword=...
keyAlias=flowzapret
keyPassword=...
```

Ключ и `keystore.properties` не входят в репозиторий. Если файла нет,
`assembleRelease` создаст неподписанный APK.

### Артефакты

После сборки APK лежат в `app/build/outputs/apk/release/`:

- `app-universal-release.apk` — все ABI;
- `app-arm64-v8a-release.apk` — только arm64-v8a.

## Ограничения

Движок ByeDPI не поддерживает часть возможностей zapret, поэтому
некоторые стратегии адаптированы, а не перенесены 1:1:

- `seqovl` — эмулируется дополнительными позициями split;
- `badseq` — эмулируется fake + disorder / изменённым TTL;
- `ip-id=zero`, `syndata`, `cutoff`, `any-protocol`, `fakedsplit-pattern`,
  `hostfakesplit` — прямых аналогов нет;
- фильтры L7 (`discord`, `stun`) заменены диапазонами UDP-портов;
- QUIC/UDP нельзя ограничить по домену — только по порту.

## Благодарности

- [ByeByeDPI](https://github.com/romanvht/ByeDPIAndroid) — база приложения.
- [ByeDPI / ciadpi](https://github.com/hufrea/byedpi) — движок десинхронизации.
- [Flowseal](https://github.com/Flowseal/zapret-discord-youtube) — стратегии и доменные списки.
- [bol-van/zapret](https://github.com/bol-van/zapret) — идеи и оригинальные стратегии.
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — VPN-транспорт.
- [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android) — Android-слой Telegram MTProto прокси.
- [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — Rust-ядро прокси (`third_party/tgwsproxy/`).
- [JNA](https://github.com/java-native-access/jna) — вызовы нативной библиотеки прокси.

## Лицензия

GPL-3.0. См. [LICENSE](LICENSE).
