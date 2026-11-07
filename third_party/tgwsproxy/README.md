# tgwsproxy (Rust-ядро Telegram MTProto прокси)

Исходники нативной библиотеки `libtgwsproxy.so`, которая используется во
вкладке «Telegram».

- Upstream: <https://github.com/Flowseal/tg-ws-proxy>
- Android-обёртка, из которой перенесён Kotlin-слой:
  <https://github.com/amurcanov/tg-ws-proxy-android>
- Лицензия: GPL-3.0 (см. [LICENSE](LICENSE))

Каталог содержит только исходный код ядра для соблюдения требований GPL-3.0.
Готовые бинарники лежат в `app/src/main/tgwsJniLibs/`:

| ABI | Файл |
| --- | --- |
| `arm64-v8a` | `app/src/main/tgwsJniLibs/arm64-v8a/libtgwsproxy.so` |
| `armeabi-v7a` | `app/src/main/tgwsJniLibs/armeabi-v7a/libtgwsproxy.so` |

Каталог вынесен отдельно от `app/src/main/jniLibs`, потому что задача
`runNdkBuild` очищает `jniLibs` при каждой сборке. В `app/build.gradle.kts`
он подключён как дополнительный `jniLibs.srcDir`.

Пересборка ядра (при необходимости) выполняется из этого каталога средствами
Rust/NDK; в обычной сборке приложения используется готовый `.so`.

## Локальные правки

- `src/proxy.rs` — предпросмотр нераспознанного PROXY-заголовка теперь режется
  по символам (`chars().take(60)`), а не по байтовому индексу. Прежний вариант
  `&text[..text.len().min(60)]` паниковал (`not a char boundary`) на бинарных
  данных клиента и ронял процесс через `panic = "abort"`.

