# FlowZapret

Android-приложение для обхода DPI на базе
[ByeByeDPI](https://github.com/romanvht/ByeDPIAndroid) с 22 стратегиями
[Flowseal](https://github.com/Flowseal/zapret-discord-youtube) и его
доменными списками.

Вторая вкладка — Telegram MTProto-прокси на базе
[tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android)
(обёртка над Rust-ядром [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)).
Обе вкладки работают независимо и могут быть включены одновременно.

- Пакет: `com.flowzapret`
- Android 8.0+ (API 26)
- Архитектуры: `armeabi-v7a`, `arm64-v8a` (universal APK включён)
- Движок обхода: [ByeDPI / ciadpi](https://github.com/hufrea/byedpi)
- Лицензия: GPL-3.0
- Исходники Rust-ядра прокси: `third_party/tgwsproxy/`

Подробности: [README-RU.md](README-RU.md) и
[docs/flowseal-mapping.md](docs/flowseal-mapping.md).

## Обновляемые компоненты

ByeByeDPI (`libbyedpi.so`), ядро Telegram-прокси (`libtgwsproxy.so`) и
пресеты Flowseal (`flowseal_presets.json`) можно обновлять без пересборки
APK: **меню → Компоненты**. Манифест обновлений подписан ECDSA P-256,
артефакты проверяются по SHA-256 и ABI. Подробности и инструкция по
публикации: [docs/COMPONENTS.md](docs/COMPONENTS.md).

```bash
./gradlew :app:assembleRelease
tools/build-components.sh    # dist/components + подпись
tools/publish-components.sh  # публикация в GitHub
```

Готовые APK: `app/build/outputs/apk/release/`.

## Благодарности

[ByeByeDPI](https://github.com/romanvht/ByeDPIAndroid),
[ByeDPI](https://github.com/hufrea/byedpi),
[Flowseal](https://github.com/Flowseal/zapret-discord-youtube),
[bol-van/zapret](https://github.com/bol-van/zapret),
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel),
[amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android),
[Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) и
[JNA](https://github.com/java-native-access/jna).
