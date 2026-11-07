# Сборка FlowZapret

Инструкции для сборки из исходников. Если нужен только готовый APK —
скачайте его со страницы [Releases](https://github.com/Steellwolf56/flowzapret/releases/latest).

## Требования

- JDK 17+
- Android SDK (compileSdk 36, build-tools 34+)
- Android NDK 27.x (для `hev-socks5-tunnel` и JNI ByeDPI)
- Rust с таргетами `aarch64-linux-android` и `armv7-linux-androideabi`
  (только для ядра Telegram-прокси)
- `openssl` (только для подписи манифеста компонентов)

## Клонирование

Репозиторий использует git-сабмодули (`byedpi`, `hev-socks5-tunnel`):

```bash
git clone --recurse-submodules git@github.com:Steellwolf56/flowzapret.git
cd flowzapret
```

## Окружение

```bash
cp local.properties.example local.properties
# впишите путь к SDK:
# sdk.dir=/path/to/Android/Sdk
```

`local.properties` не коммитится.

## APK

```bash
./gradlew :app:assembleDebug      # отладочный
./gradlew :app:assembleRelease    # релизный (подписанный, если есть keystore.properties)
```

Результат — в `app/build/outputs/apk/release/`:

- `app-universal-release.apk` — все ABI (armeabi-v7a + arm64-v8a);
- `app-arm64-v8a-release.apk` — только arm64-v8a;
- `app-armeabi-v7a-release.apk` — только armeabi-v7a.

Сборка поддерживает только ARM-архитектуры: x86/x86_64 исключены в
`app/build.gradle.kts` (`abiFilters`).

## Подпись релиза

Release подписывается ключом из `keystore.properties` в корне проекта:

```properties
storeFile=flowzapret-release.jks
storePassword=...
keyAlias=flowzapret
keyPassword=...
```

`keystore.properties` и `*.jks` не входят в репозиторий. Если файла нет,
`assembleRelease` соберёт неподписанный APK.

## Обновляемые компоненты

Компоненты (ядро ByeByeDPI, ядро tgws-прокси, пресеты Flowseal) собираются и
подписываются отдельно:

```bash
tools/generate-signing-key.sh   # один раз: ключ подписи манифеста
tools/build-components.sh       # dist/components + subпись + zip
tools/publish-components.sh     # публикация в GitHub
```

Быстрая пересборка без Gradle (если `libbyedpi.so` уже собран):

```bash
SKIP_GRADLE=1 tools/build-components.sh
```

Формат манифеста, подпись и откат — в [COMPONENTS.md](COMPONENTS.md).

## Публикация релиза

```bash
tools/publish-app.sh            # APK в dist/ + GitHub-релиз (нужен gh)
VERSION=1.3.0 tools/publish-app.sh
```

Скрипт собирает APK (если не передан `SKIP_GRADLE=1`), копирует их в `dist/`,
пишет `dist/SHA256SUMS-<версия>` и создаёт/обновляет GitHub-релиз с тегом
`v<версия>`, прикладывая APK, контрольные суммы и, при наличии,
`dist/flowzapret-components.zip`.
