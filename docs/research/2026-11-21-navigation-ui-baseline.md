# Navigation UI baseline review

- **Date:** 2026-11-21
- **Research type:** repository source-code review
- **Repository:** [MAKAKAIglitch/clear-gps](https://github.com/MAKAKAIglitch/clear-gps)
- **Branch:** `feature/navigation-ui`
- **Reviewed snapshot:** [`17c8baee676c6a8e1970aeb7887c518f8252f637`](https://github.com/MAKAKAIglitch/clear-gps/commit/17c8baee676c6a8e1970aeb7887c518f8252f637)
- **Purpose:** establish the current implementation baseline and identify the next engineering decisions for the navigation-first interface.

This is a review of the checked-in source code, not an external market survey, performance benchmark or real-device validation.

## 1. Executive summary

The feature branch already has a separate Material 3 navigation shell and a dedicated MapLibre controller. The app launches into the navigation shell, while the existing ClearGPS interface is opened from the navigation settings under **Местоположение**. The map is deliberately kept separate from radio-observation overlays and Cell Intelligence tooling.

The visible navigation experience is still an early prototype: the map and floating controls exist, the bottom island has **Карта / Поиск / Настройки**, and the settings sheet has **Общие / Карта / Местоположение**. Search, routing, turn-by-turn guidance and live navigation-location updates are not implemented.

The most important architectural gap is the location contract: the navigation screen reads the saved ClearGPS mock-output preferences once when the screen is composed. It does not currently subscribe to the physical Android location provider or to continuous changes from the ClearGPS service.

## 2. Scope and sources reviewed

The review checked the following files at the reviewed branch snapshot:

- [`MainActivity.kt`](../../app/src/main/java/org/cleargps/MainActivity.kt) — application entry point and MapView lifecycle.
- [`NavigationPrototypeScreen.kt`](../../app/src/main/java/org/cleargps/NavigationPrototypeScreen.kt) — navigation shell, floating UI, settings sheet and current position preference reader.
- [`NavigationMapController.kt`](../../app/src/main/java/org/cleargps/NavigationMapController.kt) — separate navigation MapLibre controller and map marker.
- [`ClearGpsApp.kt`](../../app/src/main/java/org/cleargps/ClearGpsApp.kt) — existing location/radio application UI and Back handling.
- [`NetworkGate.kt`](../../app/src/main/java/org/cleargps/NetworkGate.kt) — allowlisted network egress and MapLibre request filtering.
- [`app/build.gradle.kts`](../../app/build.gradle.kts) — app dependencies and Android/Compose build configuration.
- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) — existing architecture notes and separation boundaries.
- [`README.md`](../../README.md) — public project description and navigation UI intent.

Dependency details verified in `app/build.gradle.kts`: Jetpack Compose Material 3 is used through the Compose BOM, MapLibre Android SDK is `13.7.1`, and OkHttp is `4.12.0`.

## 3. Verified implementation findings

### 3.1 Application entry and navigation-to-ClearGPS transition

- `MainActivity.onCreate()` initializes MapLibre, installs the `NetworkGate` MapLibre client, and sets content to `ClearGpsNavigationShell`.
- `ClearGpsNavigationShell` switches between `NavigationPrototypeScreen` and the existing `ClearGpsApp`.
- The navigation settings sheet exposes a separate **Местоположение** section. Its action opens the existing `ClearGpsApp` composable rather than duplicating its location controls in the navigation UI.
- `ClearGpsApp` accepts an optional `onBackToNavigation` callback. Its Back handler first resolves open sub-screens/dialogs and returns to its HOME tab; from HOME it calls the callback and returns to the navigation shell.
- The legacy interface therefore remains the location configuration and advanced radio/GNSS surface. It is not rendered as a technical overlay on the navigation map.

### 3.2 Navigation map

- `NavigationMapController` is a separate controller; it does not reuse `ClearGpsMapController`.
- The navigation controller loads the OpenFreeMap Liberty or Dark style and adds only a GeoJSON current-position source with a halo and location dot.
- There are no Wi-Fi, cell, BLE, Cell Intelligence, saved-zone or radio-analysis layers in this controller.
- Zoom and recenter actions are exposed to the Compose UI.
- When there is no eligible saved position, the camera starts at a fallback coordinate in Helsinki. No location feature is drawn for that fallback; it must not be interpreted as a measured device position.

### 3.3 Current position is a snapshot, not live tracking

The current `readNavigationPosition()` function reads the ClearGPS preferences only when `NavigationPrototypeScreen` is composed. It returns a coordinate only if both `LocationMonitorService.KEY_HAS_OUTPUT` and `LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE` are true, and the stored latitude/longitude pass range validation.

The controller exposes `updatePosition()`, but the current navigation Compose screen has no call to that method and no location listener/flow/observer. Consequently:

- the marker does not continuously follow new fixes;
- ordinary physical GPS is not independently requested by the navigation screen;
- the UI currently depends on ClearGPS mock-output state for its saved-position marker;
- opening the screen while no mock output exists leaves the marker absent and uses Helsinki only as the initial camera centre.

This is the first design decision to resolve before adding a navigation location loop.

### 3.4 Material 3 navigation UI

The current implementation includes:

- a top search island with a **Куда едем?** prompt and settings shortcut;
- zoom-in, zoom-out and recenter controls on the right;
- a route/status card near the bottom;
- a compact floating bottom island with three actions: **Карта**, **Поиск**, **Настройки**;
- a settings sheet with three sections: **Общие**, **Карта**, **Местоположение**;
- an address-entry sheet that currently accepts text only.

The bottom panel is a floating rounded surface constrained to a compact width, not a full-width NavigationBar. Search and settings have visible selected states in the bottom island.

The **Общие** and **Карта** settings sections currently provide descriptive prototype content. The **Местоположение** section opens the legacy ClearGPS UI.

### 3.5 Map lifecycle and network policy

- The activity owns attach/detach handling for the native `MapView` and forwards the activity start/resume/pause/stop lifecycle events.
- The Compose `AndroidView` release path detaches the navigation controller and disposes the native view through the activity callback.
- The navigation controller sets `KEY_NAVIGATION_MAP_ACTIVE` while attached and clears it on detach.
- `NetworkGate.installMapLibre()` resets that preference on process startup to prevent a stale enabled flag.
- MapLibre requests are checked by an OkHttp network interceptor. The current allowlist is limited to HTTPS GET requests to `tiles.openfreemap.org` and approved style/data/font/asset paths; the existing host, path, method and map-online checks remain authoritative.

Any future geocoder, route service or offline-map downloader needs its own explicit network-policy review. It must not be assumed to work merely because MapLibre tiles are allowed.

## 4. Current feature maturity

| Area | Current state |
|---|---|
| Navigation-first app entry | Implemented in the branch |
| Dedicated navigation MapLibre controller | Implemented |
| Online OpenFreeMap basemap | Implemented through the existing allowlist |
| Floating search / zoom / recenter controls | UI implemented |
| Bottom floating island | UI implemented: Карта / Поиск / Настройки |
| Settings sections | UI implemented: Общие / Карта / Местоположение |
| Opening the existing ClearGPS interface | Wired through the Местоположение section |
| Search text input | UI only; no geocoding |
| Route calculation | Not implemented |
| Turn-by-turn guidance | Not implemented |
| Continuous navigation location updates | Not implemented |
| Configurable expanded map cache | Not implemented |
| Downloadable offline map regions | Not implemented |

## 5. Open engineering questions

### P0 — define the navigation position contract

Decide which position the navigation map must display:

1. physical Android location delivered to the app;
2. the position output published by ClearGPS (which may itself be a mock location);
3. a selected ClearGPS source/fusion output with explicit source and mock provenance.

The UI must not accidentally consume its own mock output as if it were independent physical GPS input. It should carry at least coordinate, monotonic measurement time, accuracy, source/provenance and mock/physical status. Define permission-denied, no-fix, stale-fix and service-stopped states before implementing the observer.

### P1 — connect lifecycle-aware live location

Once the contract is chosen, add an explicit lifecycle-aware source and update `NavigationMapController.updatePosition()` from that source. Validate first-fix, stale-fix, permission revocation, app pause/resume and ClearGPS service start/stop. Avoid polling preferences every frame or using wall-clock time as a substitute for a measurement timestamp.

### P1 — choose geocoding and routing services independently

Search and routing need separate provider decisions. Record endpoint, licence/attribution, privacy implications, request limits, offline behaviour and whether network use is opt-in. Extend `NetworkGate` only with exact documented HTTPS hosts, paths and methods required by the chosen providers.

### P2 — offline map strategy

Distinguish ordinary MapLibre tile caching from user-selected downloadable regions. Define region selection, storage quota, pack format/provider compatibility, attribution, update/removal semantics and behaviour when a pack is incomplete. A cache-size setting alone does not constitute offline-region support.

### P2 — responsive UI and theming

Validate the bottom island and settings tabs on narrow screens, landscape, gesture navigation, large font scale and accessibility services. The map style is selected when the navigation controller initially attaches; there is no dedicated controller API in the reviewed implementation to switch its style after a system theme change. Confirm this behaviour on device and add an explicit style update path if required.

## 6. Recommended implementation order

1. Agree on the navigation position contract and the visible states for no permission, no fix, stale fix and mock output.
2. Implement a lifecycle-aware position source and connect it to the navigation map marker.
3. Add tests for coordinate validation, stale measurements, source provenance and observer lifecycle transitions.
4. Research and choose a geocoding provider; document the network/privacy/licence decision before adding its host to `NetworkGate`.
5. Research and choose a routing engine/provider independently of geocoding.
6. Implement route presentation and guidance after a real route can be requested and validated.
7. Design offline-region storage and cache controls, then test map rendering with network disabled.
8. Run Gradle unit tests/build and physical-device checks for lifecycle, location permission, map/network behaviour and UI sizing.

## 7. Verification limits

This document is a source review of the stated Git commit. It does not claim that the APK compiled, that unit tests passed, that tile requests were observed on a device, or that navigation accuracy was measured. Those checks must be recorded separately after they are actually run.
