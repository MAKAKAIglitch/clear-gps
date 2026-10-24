# Clear GPS

**Privacy-first Android radio surveying, local Wi-Fi/Bluetooth/cell-point mapping, and an experimental GNSS anomaly filter.**

Clear GPS is an experimental open-source Android application built with Kotlin, Jetpack Compose, Material 3 and MapLibre Native. Wireless identifiers are collected only when the user starts a scan, and recorded observations stay in the app-private SQLite database. Clear GPS does **not** call BeaconDB, query the WiGLE API, upload observations, or send radio identifiers to a remote geolocation service.

## Features

- **Pixel-inspired Material 3 UI** with dynamic Android 12+ colours, light/dark/system map themes, and explicit scan/status feedback.
- **MapLibre map** using OpenFreeMap styles based on OpenStreetMap data. Includes light Liberty and Dark styles, a blue location dot and accuracy area, a manual amber reference point, coloured radio-point overlays and separately named spoofing-risk zones.
- **Local radio recorder:** manually started scans of nearby Wi-Fi access points (BSSID + RSSI + frequency), available GSM/WCDMA/LTE/NR cell identities and Bluetooth LE addresses/RSSI.
- **Private SQLite database:** point labels and notes, optional manually edited coordinates, sightings, bounded observation history and scan history. SSIDs and Bluetooth device names are not read/stored during live scans.
- **Radio-only mode:** Clear GPS stops requesting system location providers in the app and estimates a position only when current radio observations match coordinates already recorded in the local database. It does not switch off the GNSS hardware for Android or other apps.
- **WiGLE interchange:** import a user-selected WiGLE-compatible CSV (plain or gzip) and export the local point table as a WiGLE-style CSV. Import/export is file-based; nothing is uploaded automatically.
- **Risk-zone management:** draw multiple named polygons, rename/delete them and enable/disable each zone.
- **Explicit recording control:** while the app is visible, the recorder scans approximately every 15 seconds when the recording switch is on. The foreground GNSS monitor is separate.
- **Guarded fallback:** previously recorded radio estimates are considered only when recent, matched against enough local points and within a conservative accuracy threshold. A manual reference can be enabled as a final, coarse fallback.
- **Storage limits:** up to 20,000 detailed observations and 500 scan-history entries are retained; the map renders at most 400 saved radio points at once.
- **Windows build script** and GitHub Actions workflow for unit tests and debug APK generation.

## Radio positioning and WiGLE

WiGLE is supported as a **local import/export format**, not as a remote database dependency. Choose a CSV file you already have from WiGLE (or another compatible export) with the Android document picker. Clear GPS never asks for a WiGLE username/API key and never uploads the file.

A Wi-Fi/Bluetooth identifier by itself does not reveal coordinates. For radio-only navigation, collect observations at places where Clear GPS has a known position, use the manual reference as an approximate training position, or import geolocated records from a compatible WiGLE CSV. During a later scan, Clear GPS compares observed identifiers with locally stored, geolocated points. The estimate is heuristic; it may be absent or substantially wrong if too few saved points match, points have moved, or the radio environment has changed. Cell identifiers are collected when Android exposes them, but they are not magically geolocated without known coordinates in the local database.

## Privacy and network traffic

- **No BeaconDB requests and no WiGLE API requests.** No network geolocation lookup runs in the background.
- **No automatic upload.** Scans, labels, coordinates, zones and history are stored locally in app-private SQLite/preferences.
- **No SSID or Bluetooth-name retention.** The scanner reads an SSID only transiently to respect the common Wi-Fi "_nomap" opt-out suffix and immediately discards it; it is never stored. Bluetooth device names and advertisement payloads are not read or stored. Wi-Fi BSSID, signal strength/frequency, available cell identifiers, and Bluetooth LE addresses/signal strength are used for local matching.
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
- **Локальная SQLite-база:** подписи и заметки, координаты точек с возможностью редактирования, счётчик наблюдений, история наблюдений и сканирований. Во время живого сканирования SSID и имена Bluetooth-устройств не читаются и не сохраняются.
- **Режим «Только радио»:** Clear GPS перестаёт запрашивать системные провайдеры геолокации и рассчитывает положение только при совпадении текущих радиосигналов с координатными точками локальной базы. Режим не выключает GNSS-модуль для всей системы Android.
- **Обмен с WiGLE:** импорт выбранного пользователем WiGLE-совместимого CSV (обычный или gzip) и экспорт локальной таблицы в WiGLE-style CSV. Это обмен файлами — автоматической отправки нет.
- **Зоны спуфинга:** рисование нескольких полигонов, переименование, удаление и отдельное включение каждой зоны.
- **Явная запись:** при открытом приложении сканирование повторяется примерно раз в 15 секунд только пока переключатель записи включён. Фоновый GNSS-монитор — отдельная функция.
- **Ограничение объёма данных:** до 20 000 подробных наблюдений и 500 записей истории; на карте одновременно показывается не более 400 сохранённых радио-точек.
- **Сборка для Windows** и GitHub Actions для тестов и создания debug APK.

## Локальная навигация и WiGLE

WiGLE здесь поддерживается как **формат импорта/экспорта**, а не как удалённая база-сервис. Можно выбрать локальный CSV, заранее экспортированный из WiGLE или другой совместимой программы. Clear GPS не запрашивает WiGLE API key, не просит учётную запись и не отправляет файл на сервер.

Один Wi-Fi/Bluetooth идентификатор сам по себе не даёт координат. Для навигации без GPS необходимо записывать радионаблюдения в местах с известной позицией, использовать ручной ориентир как приблизительную исходную точку или импортировать геолокированные записи из WiGLE CSV. При последующем сканировании Clear GPS сопоставляет наблюдаемые идентификаторы с координатами, уже сохранёнными на устройстве. Расчёт эвристический: при недостатке совпадений, перестановке точек доступа или изменении радиосреды позиция может отсутствовать или быть сильно неверной. Сотовые идентификаторы собираются только если Android их предоставляет; без известных координат в локальной базе они не превращаются в геолокацию автоматически.

## Приватность и сетевые запросы

- **Никаких запросов к BeaconDB или WiGLE API.** Удалённого геолокационного поиска и фоновой отправки радио-данных нет.
- **Нет автоматической загрузки.** Сканирования, подписи, координаты, зоны и история хранятся в приватных SQLite/preferences на устройстве.
- **SSID и имена Bluetooth не сохраняются.** SSID считывается на мгновение только для соблюдения распространённого Wi-Fi opt-out суффикса "_nomap", затем отбрасывается; имена Bluetooth и содержимое BLE-рекламы не читаются. Для локального сопоставления используются BSSID, уровень сигнала/частота, доступные идентификаторы сот, адреса BLE и RSSI.
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
