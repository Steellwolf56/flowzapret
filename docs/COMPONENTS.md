# Обновляемые компоненты FlowZapret

Приложение умеет обновлять «начинку» без пересборки APK:

| Компонент | Имя в манифесте | Артефакт |
| --- | --- | --- |
| ByeByeDPI (ядро + JNI-шимы) | `byedpi` | `libbyedpi.so` |
| Telegram MTProto прокси | `tgwsproxy` | `libtgwsproxy.so` |
| Пресеты Flowseal | `presets` | `flowseal_presets.json` |

Kotlin/UI-код так обновить нельзя — для него нужен новый APK.

## Как это работает

1. Приложение скачивает `components.json` по HTTPS (поле «Источник обновлений»).
2. Скачивает `components.json.sig` и проверяет ECDSA P-256 подпись встроенным
   публичным ключом (`res/raw/components_pubkey.pem`).
3. Для каждого артефакта проверяет SHA-256, размер, а для `.so` — ELF-заголовок
   и ABI (`arm64-v8a` → `e_machine=0xB7`, `armeabi-v7a` → `e_machine=0x28`).
4. Атомарно устанавливает в `filesDir/components/<name>/<version>/`.
5. При следующем запуске `NativeLibLoader`/`TgProxyNative` грузят обновлённую
   библиотеку; при ошибке — откат на встроенную.

Нативные библиотеки применяются после полного перезапуска приложения
(Android не умеет выгружать уже загруженный `.so`).

## Формат манифеста

```json
{
  "schema": 1,
  "generatedAt": "2026-11-07T21:00:00Z",
  "components": {
    "byedpi": {
      "version": "0.16.6+flowzapret.1a2b3c4d",
      "nativeApi": 1,
      "minAppVersionCode": 2,
      "abis": {
        "arm64-v8a":   { "url": "libbyedpi-arm64-v8a.so",   "sha256": "…", "size": 123456 },
        "armeabi-v7a": { "url": "libbyedpi-armeabi-v7a.so", "sha256": "…", "size": 123456 }
      }
    },
    "tgwsproxy": {
      "version": "1.0.0+flowzapret.1a2b3c4d",
      "nativeApi": 1,
      "minAppVersionCode": 2,
      "abis": { "…": "…" }
    },
    "presets": {
      "version": "2026.11.07+1a2b3c4d",
      "nativeApi": 1,
      "minAppVersionCode": 2,
      "artifact": { "url": "flowseal_presets.json", "sha256": "…", "size": 12345 }
    }
  }
}
```

`url` может быть относительным — он разрешается относительно URL манифеста.
Подпись `components.json.sig` — base64 от DER-подписи ECDSA P-256/SHA-256
ровно тех байт, что лежат в `components.json`.

`nativeApi` — версия контракта JNI. Если меняются имена пакетов, классов или
сигнатуры `external fun`, её нужно поднять; клиент с меньшей версией
проигнорирует компонент.

## Сборка

```bash
# один раз: ключ подписи (приватный не коммитится)
tools/generate-signing-key.sh

# сборка всех артефактов + манифеста + подписи + zip
tools/build-components.sh

# быстрая пересборка без Gradle (если libbyedpi.so уже собран)
SKIP_GRADLE=1 tools/build-components.sh
```

Результат — `dist/components/`:

```
components.json
components.json.sig
libbyedpi-arm64-v8a.so
libbyedpi-armeabi-v7a.so
libtgwsproxy-arm64-v8a.so
libtgwsproxy-armeabi-v7a.so
flowseal_presets.json
```

Плюс `dist/flowzapret-components.zip` для ручного импорта.

Версии по умолчанию содержат хеш содержимого, поэтому повторная сборка без
изменений не создаёт «фальшивое» обновление. Переопределить можно так:

```bash
BYEDPI_VERSION=0.16.6+flowzapret.7 TGWS_VERSION=1.0.0+flowzapret.7 \
PRESETS_VERSION=2026.11.07 tools/build-components.sh
```

## Публикация

```bash
tools/publish-components.sh
```

Скрипт клонирует `git@github.com:Steellwolf56/flowzapret-components.git`
в `~/flowzapret-components-repo`, копирует туда файлы и пушит в `main`.
Готовая ссылка для приложения:

```
https://raw.githubusercontent.com/Steellwolf56/flowzapret-components/main/components.json
```

Она же — дефолт в `BuildConfig.COMPONENTS_MANIFEST_URL`.

## Ручной импорт

В приложении: **Компоненты → Импорт из файла (zip)**. Подходит
`dist/flowzapret-components.zip`. При импорте SHA-256 проверяется всегда,
подпись — если в архиве есть `components.json.sig`.

## Откат и сброс

- Ошибка загрузки/проверки библиотеки → `ComponentStore.markBroken()` и
  загрузка встроенной версии.
- «Сбросить к встроенным» удаляет `filesDir/components/`.

## Лицензии

- ByeDPI — GPL-3.0, <https://github.com/hufrea/byedpi>
- ByeByeDPI — GPL-3.0, <https://github.com/romanvht/ByeByeDPI>
- Flowseal presets — <https://github.com/Flowseal/zapret-discord-youtube>
- tg-ws-proxy — GPL-3.0, <https://github.com/Flowseal/tg-ws-proxy>

Распространяя обновлённые `.so`, сохраняйте требования GPL-3.0 и указывайте
источники.
