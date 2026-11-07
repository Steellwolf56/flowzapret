# Соответствие стратегий Flowseal и ByeDPI

Источник стратегий: [Flowseal/zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube)
(`general.bat`, `general (ALT..ALT13).bat`, `general (EXP).bat`,
`general (FAKE TLS AUTO..ALT3).bat`, `general (SIMPLE FAKE..ALT2).bat`).

ByeDPI — это userspace SOCKS-прокси (`ciadpi`), а zapret — перехват на
уровне ядра (`winws`). Поэтому часть опций zapret не имеет аналога.
Ниже — что именно было сделано для каждого типа опций.

## Общая структура команд

Каждый пресет состоит из групп, разделённых `-A n`. Группы проверяются
по порядку, запрос обслуживает первая подходящая.

| Назначение | Аргументы ByeDPI |
|------------|------------------|
| QUIC (UDP 443) | `-Ku -V443 -a<repeats>` |
| Голосовой UDP Discord/STUN | `-Ku -V19294-19344 -a<repeats>`, `-Ku -V50000-50100 -a<repeats>` |
| Discord TCP | `-Kt -V2053-2096 -H:discord.media <ops>`, `-Kt -V8443 ...` |
| Google / YouTube TCP | `-Kt -V443 -H:"{list:Flowseal Google}" <ops>` |
| General TCP | `-Kt -V443 / -Kh -V80 / -Kt -V8443 -H:"{list:Flowseal General}" <ops>` |

`{list:Имя}` подставляется из `DomainListUtils` перед `shellSplit`
(см. `ByeDpiProxyCmdPreferences.getLists`). Кавычки и двоеточие
обязательны: `-H:"{list:Flowseal General}"`, иначе ByeDPI воспримет
строку как имя файла.

## Соответствие опций

| zapret | ByeDPI | Комментарий |
|--------|--------|-------------|
| `--dpi-desync=multisplit --split-pos=N` | `-sN` | прямая замена |
| несколько `--split-pos` | несколько `-s` | позиции должны возрастать |
| `--dpi-desync=disorder/multidisorder` | `-dN` | несколько `-d` для multidisorder |
| `--dpi-desync=fake` | `-f-1` | фейк почти всего пакета |
| `--dpi-desync-fake-tls-mod=rnd,dupsid` | `-Qr` | случайные SessionID/Random/KeyExchange |
| `--dpi-desync-fake-tls-mod=sni=host` | `-n host` | подмена SNI в фейке |
| `--dpi-desync-fooling=ts` | `-tN` (TTL) | подбор TTL вместо fooling |
| `--dpi-desync-fooling=md5sig` | — | на Android обычно недоступно |
| `--dpi-desync-repeats=N` (UDP) | `-aN` | число фейковых UDP-пакетов |
| `--dpi-desync-repeats=N` (TCP) | `-s pos:r:step` | повторы split-позиций |
| `--hostlist` | `-H:"{list:...}"` | белый список доменов |
| `--hostlist-domains=discord.media` | `-H:discord.media` | один домен |
| `--filter-tcp=2053,2083,2087,2096,8443` | `-V2053-2096` + `-V8443` | `-V` принимает один диапазон |
| `--filter-udp=443` | `-Ku -V443` | протокол + порт |
| `--ip-id=zero` | — | нет аналога |
| `--dpi-desync-split-seqovl=N` | доп. `-s` | например `-s1:2:1` |
| `--dpi-desync-fooling=badseq` | `-tN`, fake+disorder | прямого аналога нет |
| `--dpi-desync=fakedsplit` | `-d1 -f-1` | fake + disorder |
| `--dpi-desync=hostfakesplit` | `-d1 -f-1 -n host` | fake с SNI + disorder |
| `--filter-l7=discord,stun` | `-V<диапазон>` | L7-фильтра нет |
| `--dpi-desync=syndata` | `-d1 -d3 -d5` | syndata только в ядре |
| `--dpi-desync-any-protocol`, `--dpi-desync-cutoff` | — | нет аналога |
| `--ipset`, `--ipset-exclude` | — | не переносились |
| `--wf-tcp`, `--wf-udp` | — | ByeDPI обрабатывает весь проксируемый трафик |

## Пресеты

Ниже — какие операции реально попадают в TCP-группы каждого пресета.
`Q` = `-Qr`, `SNI` = `-n ...`, `T` = TTL фейка.

| Пресет | TCP-операции | Отличие от Flowseal |
|--------|--------------|---------------------|
| General | `-s1` | seqovl 681 отброшен |
| ALT | `-d1 -f-1 -t8` | fakedsplit → disorder+fake |
| ALT 2 | `-s2` | seqovl 652 отброшен |
| ALT 3 | `-d1 -f-1 -Qr -n www.google.com` | hostfakesplit эмулирован |
| ALT 4 | `-s1 -f-1 -t9` | badseq → TTL 9 |
| ALT 5 | `-d1 -d3 -d5` | syndata опущен |
| ALT 6 | `-s1:2:1` | seqovl → доп. позиция |
| ALT 7 | `-s2 -s1+s` | sniext+1 → `-s1+s` |
| ALT 8 | `-f-1 -t10` | badseq → TTL 10 |
| ALT 9 | `-d1 -f-1 -n www.google.com -t8` | hostfakesplit, md5sig опущен |
| ALT 10 | `-f-1 -Qr -t7` | TTL подобран под badseq |
| ALT 11 | `-s1:2:1 -f-1 -Qr` | seqovl → доп. позиция |
| ALT 12 | split+fake для general, `-d1 -f-1 -n www.google.com` для Google | hostfakesplit |
| ALT 13 | `-d1 -f-1 -Qr -n mail.ru` | hostfakesplit |
| EXP | `-s1 -f-1 -Qr -t6` | seqovl 480 отброшен |
| Fake TLS AUTO | `-d0+sm -f-1 -Qr -n www.google.com` | multidisorder pos 1,midsld |
| Fake TLS AUTO ALT | `-s1 -f-1 -Qr -n www.google.com` | fakedsplit |
| Fake TLS AUTO ALT 2 | `-s1:2:1 -f-1 -Qr -n www.google.com -t9` | badseq → TTL |
| Fake TLS AUTO ALT 3 | `-s1:2:1 -f-1 -Qr -t9` | — |
| Simple Fake | `-f-1 -Qr -t8` | — |
| Simple Fake ALT | `-f-1 -t9` | badseq → TTL |
| Simple Fake ALT 2 | `-f-1 -Qr -t10` | — |

## Доменные списки

- `proxytest_flowseal_general.sites` — основной список Flowseal
  (`list-general.txt`) без регулярок `^`.
- `proxytest_flowseal_google.sites` — Google/YouTube
  (`list-google.txt`).
- Списки `list-exclude`, `ipset-*` не переносились: в ByeDPI доменные
  списки работают как белый список, поэтому исключения не нужны —
  всё, что не попало в список, не десинхронизируется.
- Оба списка добавлены в настройки доменных списков; по умолчанию
  включены списки `YouTube` и `GoogleVideo` (как в ByeByeDPI), а
  `Flowseal General`/`Flowseal Google` можно включить вручную — они
  используются стратегиями через `{list:...}` независимо от активности.

## Почему некоторые стратегии похожи

ByeDPI не умеет seqovl/badseq/syndata, поэтому стратегии, которые в
zapret отличаются только этими параметрами, после адаптации могут
выполнять близкие операции. Чтобы пресеты оставались различимыми и
полезными для автоподбора, в таких случаях варьируется TTL фейка
(`-t6`…`-t10`) или добавляется дополнительная split-позиция. Это
осознанное отличие от оригинала, а не ошибка переноса.
