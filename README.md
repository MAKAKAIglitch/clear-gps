# Clear GPS

**Privacy-first Android radio surveying, local Wi-Fi/Bluetooth/cell-point mapping, and an experimental GNSS anomaly filter.**

Clear GPS is an experimental open-source Android application built with Kotlin, Jetpack Compose, Material 3 and MapLibre Native. Wireless identifiers are collected only when the user starts a scan, and recorded observations stay in the app-private SQLite database. Clear GPS does **not** call BeaconDB, query the WiGLE API, upload observations, or send radio identifiers to a remote geolocation service.

## Features

- **Pixel-inspired Material 3 UI** with dynamic Android 12+ colours, light/dark/system map themes, and explicit scan/status feedback.
- **MapLibre map** using OpenFreeMap styles based on OpenStreetMap data. Includes light Liberty and Dark styles, a blue location dot and accuracy area, a manual amber reference point, coloured radio-point overlays and separately named spoofing-risk zones.
- **Local radio recorder:** manually started scans of nearby Wi-Fi access points (BSSID + RSSI + frequency), available GSM/WCDMA/LTE/NR cell identities and Bluetooth LE addresses/RSSI.
- **Private SQLite database:** Wi-Fi SSIDs and BSSIDs, available cell identifiers, BLE addresses/RSSI, editable labels and coordinates, and bounded observation history. Radio identifiers remain in the app-private database; no radio IDs are uploaded.
- **Radio-only mode:** Clear GPS stops requesting system location providers in the app and estimates a position only when current radio observations match coordinates already recorded in the local database. It does not switch off the GNSS hardware for Android or other apps.
- **WiGLE interchange:** import WiGLE CSV (plain or gzip), WiGLE-style SQLite (`network` / `location` tables), or a Clear GPS SQLite backup. Export individual WiGLE observations as CSV, create a WiGLE-style SQLite database, or create a full Clear GPS backup. Imports merge with the local database and avoid re-adding identical observations.
- **Read-only OBD-II with position fallbacks:** GPS + OBD-II can briefly extrapolate a recent GPS fix using fresh vehicle speed and a valid GPS course; Radio + OBD-II keeps local radio matching as the coordinate source and uses speed to increase scan cadence while moving. Standard PID support is discovered per vehicle when possible; unsupported readings fall back independently. BLE/USB transports are not yet supported.
- **Risk-zone management:** draw multiple named polygons, rename/delete them and enable/disable each zone.
- **Continuous fix control:** the main “Enable fix” button starts/stops the foreground location service and mock GPS output together. The refresh interval is configurable from 1 to 30 seconds; radio scans remain subject to Android throttling. One-shot scans remain available in diagnostics. Recording observations is still a separate switch.
- **Guarded fallback:** previously recorded radio estimates are considered only when recent, matched against enough local points and within a conservative accuracy threshold. A manual reference can be enabled as a final, coarse fallback.
- **Storage and map limits:** up to 20,000 detailed observations and 500 scan-history entries are retained; the map renders up to 1,500 saved points on ordinary devices and lowers the limit on low-RAM devices.
- **Memory-aware Windows build:** Gradle heap is sized from installed/free RAM with a safety reserve for Windows; the daemon, parallel execution and build cache are enabled.
- **Scan cadence:** configurable interval from 10 to 120 seconds; optionally request a faster interval above 25 km/h, subject to Android throttling.
- **Private map fallback:** a tile-free offline canvas retains local radio markers without requesting map data, but does not display streets or labels.
- **Windows build script** and GitHub Actions workflow for unit tests and debug APK generation.

## Radio positioning and WiGLE

WiGLE is supported as a **local file interchange format**, not as a remote database dependency. Import a CSV (plain or gzip) or a SQLite database exported by WiGLE; the importer recognises the `network` summary and `location` observation tables. SQLite imports are validated and merged into the current database rather than replacing it. The export menu offers three distinct outputs: WiGLE CSV (one row per retained observation, plus a representative row for a radio with no history), a WiGLE-shaped SQLite database (`network` and geolocated `location` rows), and a full Clear GPS SQLite backup (radio summaries, observation history, and scan history). WiGLE-shaped exports omit unlocated observations because the WiGLE `location` format requires coordinates; use the full Clear GPS backup to preserve all app history. Identifiers are normalized to lowercase and duplicate observations are skipped when the same file is imported again. Clear GPS never asks for a WiGLE username/API key and never uploads selected files.

A Wi-Fi/Bluetooth identifier by itself does not reveal coordinates. For radio-only navigation, first record scans at places with known coordinates, use a manual reference as coarse training data, or import a compatible WiGLE CSV. Clear GPS now prioritizes **radio fingerprint matching**: it compares current identifiers and RSSI values with complete, previously recorded local scans, weights similarity by radio type and signal-level differences, and combines nearby high-quality matches. If no suitable scan fingerprint exists, it falls back to the geolocated radio-point table using capped per-radio-type weights, a deliberately softened RSSI influence, and spatial outlier rejection. If nearly identical fingerprints point to distant places, the algorithm refuses to choose one. Radio-only estimates are never written back as ground-truth training coordinates, avoiding self-reinforcing drift. For non-manually-edited, non-WiGLE points, a new strongest RSSI sample can still refine the stored approximate reception location when its reference fix is no worse than 50 m; this does not triangulate an antenna or guarantee transmitter coordinates. Estimates remain heuristic and require previously geolocated recordings; moved access points, changing radio conditions, or sparse coverage can still cause a missing or wrong fix. Cell identifiers are collected only when Android exposes them and do not become coordinates without local reference data.

## Read-only OBD-II foundation

OBD-II connects only after the user opens the OBD-II screen, selects a previously paired Bluetooth Classic SPP adapter and taps Connect. It does not start Bluetooth discovery. Android's Nearby devices / `BLUETOOTH_CONNECT` permission is used only to list paired adapters and connect to the selected adapter.

The client first requests standard SAE J1979 Mode 01 PID 00 (the read-only capability bitmap for PIDs 01–20) and skips common PIDs that the ECU says it does not support. If the bitmap is absent or unusable, it probes the known PIDs independently: 0D (vehicle speed), 0C (engine RPM), and 05 (coolant temperature). Speed is requested first; an unavailable optional reading does not invalidate the other values. The fixed allowlist rejects arbitrary requests. `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATAT1` and `ATSP0` configure only the ELM-compatible adapter's output, adaptive timing and automatic protocol selection; no ECU reset, trouble-code clearing, coding, adaptation or write service is sent.

Only fresh PID 0D speed contributes to position fallback. In GPS + OBD-II mode, the **map** can extrapolate for at most 30 seconds from a recent GPS fix, only when that fix has a course, reported GPS accuracy is at most 100 metres, and speed data is no more than 5 seconds old; projected travel is capped at 300 metres and estimated uncertainty grows with time and distance. If those conditions fail, the map reverts to the current GPS fix or stops presenting it as fresh rather than guessing a direction. When the foreground monitor is running, it can also publish that bounded prediction through the mock GPS provider **only if mock-location output was explicitly enabled**, the last fix was accepted as NORMAL by the GNSS filter, and the position is outside risk zones. The prediction is not fed back to the spoofing detector; an alert, stale input, excessive projected distance or a new source mode stops and clears it. In Radio + OBD-II mode, the local radio database remains the only coordinate source and fresh OBD speed may shorten radio scan intervals while moving. Speed, RPM and coolant temperature alone cannot determine a position. The latest speed sample is handed between the app and foreground service through a timestamped preference and is considered usable for only 5 seconds; it is not a trip history and is cleared on disconnect. Initial transport support is Bluetooth Classic RFCOMM/SPP; BLE and USB require separate transports.

## Privacy and network traffic

- **No BeaconDB requests and no WiGLE API requests.** No network geolocation lookup runs in the background.
- **No automatic upload.** Scans, labels, coordinates, zones and history are stored locally in app-private SQLite/preferences.
- **Local radio identifiers.** SSIDs and BSSIDs are stored in the private local database for search and editing. Bluetooth device names and advertisement payloads are not read or stored. SSIDs, BSSIDs, cell IDs and BLE addresses are never added to map requests or uploaded by Clear GPS.
- **Maps are still online by default.** OpenFreeMap serves map styles/tiles from its public service, so ordinary map requests expose the device's IP address and which map area is being viewed to that map provider. Wireless identifiers are not attached to those requests. For more private use, the map component must be pointed at a self-hosted or offline map source.
- **Permissions matter.** Android can throttle Wi-Fi scans, limit cell information, randomize Bluetooth addresses, and refuse scans when permissions or system settings do not allow them. Turning off global Android location can also block Wi-Fi scanning on some versions/devices.
- **Radio-only is an app-level mode.** A regular Android application cannot force the system-wide GNSS hardware switch off without privileged/system access. Clear GPS can stop requesting GPS updates itself; supplying locations to other apps additionally requires selecting Clear GPS as the mock-location app in Developer options. Some apps detect or ignore mock locations.
- The app is experimental and does not guarantee accurate navigation or detect every kind of GNSS spoofing.

## Build on Windows

Run **build-apk.bat** from the repository root. The helper checks for a compatible Java/Android SDK/Gradle setup and can offer to install missing tools after confirmation. It verifies downloaded archive hashes when supported by the bootstrap script.

After a successful build, the APK is written to **dist/ClearGPS-debug.apk**. You can also download the **ClearGPS-debug** artifact from the [GitHub Actions runs](https://github.com/MAKAKAIglitch/clear-gps/actions).

## Maps and attribution

The default map styles are supplied by [OpenFreeMap](https://openfreemap.org/) and use OpenMapTiles / OpenStreetMap data. Attribution is shown in the app. OpenFreeMap's public styles are convenient but require internet access; consult its current [terms](https://openfreemap.org/tos/) before adding offline downloads or larger-scale caching. No map tiles are bundled in the APK.

## Development status

This is a research/learning project. The app has automated build and unit-test checks, but the full scan/record/edit/radio-only workflow still needs hands-on validation on real Android devices and different manufacturers. The stated accuracy radius of a local estimate is heuristic rather than a surveyed guarantee.

---

# Русский

**Clear GPS — экспериментальное Android-приложение для локальной записи Wi-Fi/Bluetooth/сотовых радиоточек, навигации по собственной базе и фильтрации GNSS-аномалий.**

Проект построен на Kotlin, Jetpack Compose, Material 3 и MapLibre Native. Радиоидентификаторы сканируются только по команде пользователя или при явно включённой записи. Наблюдения остаются в приватной SQLite-базе приложения. Clear GPS **не вызывает BeaconDB, не обращается к WiGLE API, не загружает наблюдения на сервер и не отправляет радиоидентификаторы в удалённый геолокационный сервис**.

## Возможности

- **Интерфейс в духе Pixel / Material 3:** динамические цвета Android 12+, темы карты «система / светлая / тёмная», понятные статусы сканирования.
- **Карта MapLibre** на основе OpenFreeMap и данных OpenStreetMap: тёмная и светлая темы, синяя точка позиции с радиусом точности, янтарный ручной ориентир, цветные радио-точки и несколько именованных зон риска.
- **Локальный сканер:** Wi-Fi BSSID/уровень сигнала/частота, доступные Android идентификаторы сотовых сот GSM/WCDMA/LTE/NR, адреса и RSSI Bluetooth LE.
- **Локальная SQLite-база:** SSID и MAC/BSSID Wi-Fi, доступные идентификаторы сот и адреса BLE; редактируемые подписи и координаты, ограниченная история наблюдений. Радиоидентификаторы остаются в приватной базе приложения; они не загружаются на сервер.
- **Режим «Только радио»:** Clear GPS перестаёт запрашивать системные провайдеры геолокации и рассчитывает положение только при совпадении текущих радиосигналов с координатными точками локальной базы. Режим не выключает GNSS-модуль для всей системы Android.
- **Обмен с WiGLE:** импорт CSV (обычный или gzip), WiGLE SQLite (`network` / `location`) и резервных копий Clear GPS; экспорт CSV по отдельным наблюдениям, WiGLE-совместимой SQLite и полной SQLite-копии Clear GPS. Импорт объединяет данные и не дублирует уже имеющиеся наблюдения.
- **Основа OBD-II только для чтения:** подключение по запросу к уже сопряжённому Bluetooth Classic SPP адаптеру; чтение оборотов (PID 0C), скорости автомобиля (PID 0D) и температуры охлаждающей жидкости (PID 05). Произвольные команды, сброс ошибок, запись/кодирование ЭБУ и адаптации не предоставляются. BLE/USB и фирменные параметры пока не поддерживаются.
- **Зоны спуфинга:** рисование нескольких полигонов, переименование, удаление и отдельное включение каждой зоны.
- **Кнопка фикса:** «Включить фикс» запускает foreground-сервис, непрерывные обновления выбранного источника и mock GPS; повторное нажатие отключает фикс и mock-вывод. Интервал обновления настраивается от 1 до 30 секунд. Разовое сканирование доступно в диагностике, запись радионаблюдений остаётся отдельным переключателем. Android всё ещё может ограничивать частоту Wi‑Fi/Bluetooth-сканов.
- **Ограничение объёма данных:** до 20 000 подробных наблюдений и 500 записей истории; на карте одновременно показывается не более 400 сохранённых радио-точек.
- **Сборка с учётом ОЗУ:** heap Gradle рассчитывается по доступной/установленной памяти с резервом для Windows; включены daemon, параллельная сборка и кэш.
- **Частота фикса:** интервал 1–30 секунд; при включённой адаптации интервал может уменьшаться в движении. Фактическая частота радиообновлений зависит от системных ограничений Android.
- **Приватный режим карты:** офлайн-холст не запрашивает тайлы, но показывает локальные радиоточки без улиц и подписей.
- **Сборка для Windows** и GitHub Actions для тестов и создания debug APK.

## Локальная навигация и WiGLE

WiGLE поддерживается как **локальный файловый формат**, а не как удалённый сервис. Можно импортировать CSV (обычный или gzip) либо SQLite-базу WiGLE; распознаются таблицы `network` (сводка сетей) и `location` (отдельные наблюдения). Импорт SQLite проверяется и объединяется с текущей базой, а не заменяет её. Меню экспорта предлагает три разных файла: WiGLE CSV (строка на каждое сохранённое наблюдение и одна сводная строка для точки без истории), WiGLE-совместимую SQLite с таблицами `network` и `location`, а также полную резервную копию Clear GPS SQLite с радиоточками, историей наблюдений и историей сканирований. В WiGLE-форматы не включаются наблюдения без координат, поскольку формат таблицы `location` требует координаты; для сохранения всей истории приложения используй полную резервную копию Clear GPS. Идентификаторы приводятся к нижнему регистру, повторный импорт того же файла не должен добавлять одинаковые наблюдения. Clear GPS не запрашивает WiGLE API key и не отправляет выбранные файлы на сервер.

Один Wi-Fi/Bluetooth идентификатор сам по себе не даёт координат. Для радионавигации сначала запиши сканирования в местах с известным положением, используй ручной ориентир как грубую учебную точку или импортируй совместимый WiGLE CSV. Clear GPS в первую очередь применяет **сопоставление радиоснимков**: сравнивает текущие идентификаторы и RSSI с полными сканированиями из локальной базы, учитывает тип радио и различия уровня сигнала, затем объединяет близкие качественные совпадения. Если подходящего отпечатка нет, используется запасной расчёт по координатам радиоточек с ограничением влияния каждого типа радио, ослабленным влиянием RSSI и отбрасыванием пространственных выбросов. Если почти одинаковый отпечаток найден в удалённых местах, алгоритм отказывается угадывать. Результат режима «Только радио» не записывается обратно как достоверная учебная координата — это предотвращает накопление ошибки от самоподкрепления. Для точек, которые не закреплены вручную и не импортированы из WiGLE, новый максимум RSSI всё ещё может уточнить примерное место приёма, если исходный ориентир имеет точность не хуже 50 м; это не триангуляция и не гарантия координат антенны. Расчёт остаётся эвристическим и требует ранее записанных сканирований с координатами. Перестановка точек доступа, изменение радиосреды или малое число совпадений могут привести к отсутствию позиции или ошибке. Идентификаторы сотовых сот доступны только когда их предоставляет Android; без локальных координат они не превращаются в геолокацию автоматически.

## Основа OBD-II только для чтения

OBD-II запускается только по действию пользователя: открыть вкладку, выбрать ранее сопряжённый Bluetooth Classic SPP-адаптер и нажать «Подключить». Поиск окружающих Bluetooth-устройств не выполняется. Разрешение Android «Устройства поблизости» / `BLUETOOTH_CONNECT` используется только для списка сопряжённых адаптеров и подключения к выбранному.

Сначала приложение запрашивает стандартный SAE J1979 Mode 01 PID 00 — битовую карту поддержки PID 01–20. Если ЭБУ её возвращает, неподдерживаемые показатели пропускаются. Если карта отсутствует или некорректна, приложение по отдельности пробует известные PID: 0D (скорость автомобиля), 0C (обороты двигателя) и 05 (температура охлаждающей жидкости). Скорость запрашивается первой; отсутствие необязательного значения не должно мешать другим. Список команд ограничен: произвольные запросы не принимаются. Команды `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATAT1` и `ATSP0` меняют только текстовый вывод, адаптивное ожидание и автоматический выбор протокола на ELM-совместимом адаптере. Запись в ЭБУ, сброс ошибок, кодирование и адаптации не выполняются.

Для резервной геолокации используется только свежая скорость PID 0D. В режиме «GPS + OBD-II» **карта** может продолжить последнюю GPS-оценку максимум на 30 секунд — только если у фикса есть курс, заявленная GPS-точность не хуже 100 метров, а значение скорости не старше 5 секунд; расчётное перемещение ограничено 300 метрами, а оценка погрешности растёт со временем и расстоянием. Если условия не выполнены, карта возвращается к обычному GPS-фиксу либо прекращает показывать его как свежий, а не угадывает направление. Когда foreground-монитор запущен, он также может передавать этот ограниченный прогноз через фиктивный GPS-провайдер, **только если пользователь отдельно включил фиктивную геолокацию**, последний GPS-фикс принят GNSS-фильтром как NORMAL, а координата вне зон риска. Прогноз не подаётся обратно на вход фильтра; тревога, устаревание датчиков, превышение лимита расстояния или смена режима останавливает и очищает его. В режиме «Радио + OBD-II» единственным источником координат остаётся локальная радиобаза, а свежая скорость может учащать её сканирование в движении. Одна скорость, обороты и температура не определяют координаты. Последняя скорость кратковременно передаётся между приложением и foreground-сервисом через preference с меткой времени и считается пригодной только 5 секунд; это не история поездок, при отключении она очищается. Сейчас поддерживается Bluetooth Classic RFCOMM/SPP; для BLE и USB нужен отдельный транспорт.

## Приватность и сетевые запросы

- **Никаких запросов к BeaconDB или WiGLE API.** Удалённого геолокационного поиска и фоновой отправки радио-данных нет.
- **Нет автоматической загрузки.** Сканирования, подписи, координаты, зоны и история хранятся в приватных SQLite/preferences на устройстве.
- **Радиоидентификаторы остаются локально.** SSID и MAC/BSSID Wi-Fi сохраняются в локальной базе для поиска и редактирования. Имена Bluetooth и содержимое BLE-рекламы не читаются. SSID, BSSID, идентификаторы сот и адреса BLE не добавляются к запросам карты и не отправляются приложением на сервер.
- **Карта по умолчанию всё ещё онлайн.** OpenFreeMap отдаёт стили и тайлы с публичного сервиса, поэтому его сервер видит обычный IP-адрес устройства и запрашиваемые участки карты. Радиоидентификаторы к картографическим запросам не добавляются. Для более приватного режима нужно подключить собственный сервер или локальную карту.
- **Ограничения Android остаются.** Система может ограничивать частоту Wi-Fi-сканирования, скрывать часть информации о сотах и использовать рандомизированные Bluetooth-адреса. На некоторых версиях/устройствах выключение глобальной геолокации блокирует и Wi-Fi-сканирование.
- **«Только радио» — режим самого приложения.** Обычное Android-приложение не может принудительно выключить GNSS-модуль для всей системы без системных привилегий. Clear GPS может перестать запрашивать GPS-провайдер; передача координат другим приложениям дополнительно требует выбора Clear GPS как приложения фиктивной геолокации в настройках разработчика. Некоторые приложения распознают или игнорируют фиктивные координаты.
- Проект экспериментальный и не гарантирует точность навигации или обнаружение всех вариантов GNSS-спуфинга.

## Сборка на Windows

Запусти **build-apk.bat** из корня репозитория. Сборщик проверяет Java, Android SDK и Gradle; при необходимости он предлагает установить недостающие инструменты после подтверждения. Где доступно, скачиваемые архивы проверяются по хешам.

После успешной сборки APK будет в **dist/ClearGPS-debug.apk**. Также его можно получить как артефакт в [GitHub Actions](https://github.com/MAKAKAIglitch/clear-gps/actions).

## Карты и атрибуция

По умолчанию используются стили [OpenFreeMap](https://openfreemap.org/) на основе OpenMapTiles / OpenStreetMap. Атрибуция показывается в приложении. Публичные стили OpenFreeMap удобны, но требуют интернет; перед реализацией офлайн-загрузки или более крупного кэширования изучи актуальные [условия сервиса](https://openfreemap.org/tos/). Карты не включены в APK.

## Статус разработки

Это исследовательский проект. Сборка и unit-тесты автоматизированы, но полный сценарий сканирования/записи/редактирования/радионавигации ещё нужно проверять на реальных телефонах Android разных производителей. Указанный радиус точности локальной оценки — эвристика, а не измеренная гарантия.

## License

MIT — see [LICENSE](LICENSE).
