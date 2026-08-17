# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Native Android port of the SKY-SPY-Aware drone-detection dashboard (Kotlin, Jetpack Compose, Material 3, single `:app` module). It subscribes to a Sky Spy MQTT feed (`<topic>/raw` JSON lines), plots drones and pilots on an osmdroid satellite map, persists infinite detection history in SQLite, enriches drones with FAA/FCC registration lookups, and classifies surroundings with a bundled on-device YOLOv8-OBB TFLite model.

`HANDOFF.md` holds detailed session context (catalog rules, pending features, prior fixes). Read it before working on the Drones/Flights analytics tabs.

## Build / deploy / debug

No test suite and no lint config exist; verification is build + on-device run.

```powershell
.\gradlew.bat assembleDebug                 # APK at app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:installDebug             # build + install; preserves on-device DB
.\gradlew.bat :app:assembleRelease          # release APK (debug-signed)
```

- Requires JDK 17. Compose compiler is pinned (Kotlin 1.9.24 / compose ext 1.5.14 / AGP 8.5.2); don't bump one without the others.
- Test device: `R5CN70YWT5Z` (SM-N986U, Android 13). Disconnects often; retry `adb devices`.
- User directive: deploy to the device after every code fix.
- Health check after install:
  `adb logcat -c; adb shell am start -n com.suteny0r.skyspyaware/.MainActivity; sleep 8; adb logcat -d *:E | grep -iE "skyspy|Exception|SQLite|FATAL"` — only expected errors are `RequestManager_FLP` GPS noise.
- On-device DB: `/data/data/com.suteny0r.skyspyaware/databases/detections.db`. Never pull it with `adb exec-out ... cat` from PowerShell (binary gets doubled); use a Python subprocess (see `F:\TEMP\opencode\pull_dev.py`). Push via `/data/local/tmp` then `adb shell run-as com.suteny0r.skyspyaware cp ...` (`run-as` cannot write to `/data/local/tmp`). Do not push a stale DB over the device copy — the FAA cache crawl grows it continuously.

## Architecture

All source lives in `app/src/main/java/com/suteny0r/skyspyaware/` (data layer) and `.../ui/` (Compose screens). ~7.4k lines total.

**`DataRepo` (object singleton) is the hub.** Everything flows through it: it owns `MqttManager`, `DetectionCache`, FAA lookup loops, satellite-scan scheduling, notifications, and exposes all state as `StateFlow`s. `SkySpyViewModel` is a thin bridge that just forwards `DataRepo` flows/actions to Compose. `SkySpyService` is a START_STICKY foreground service that keeps `DataRepo` collecting when the UI is closed; collection stops only on explicit user disconnect.

**Data flow:** MQTT message → `Detection.kt` (JSON parse → `Drone` model) → `DataRepo` in-memory drone state (60s live age-out) + `DetectionCache` (SQLite `detections.db`: `detections`, `flights`, `faa_cache` tables; schema currently v5). Background loops in `DataRepo`: FAA lookups every 5s with retry polling (`FaaClient`), flight segmentation (2.5 min quiet = flight over), debounced stats recompute, hourly prune, satellite-scan scheduling (24h TTL, only drones seen in the last 24h — scanning the whole history OOMs).

**Analytics:** `StatisticsCalculator` computes `Stats` (model counts, per-drone stats, pilot-profile/category charts, flights) from the DB plus FAA-cache text. `DroneCatalog` maps FCC `Make:`/`Model:` strings to MSRP/category/pilot-profile via normalized longest-key-wins matching; parenthetical serial suffixes are stripped; a bare make with no model matches nothing. FAA `FAA_NOT_FOUND` sentinel marks simulator traffic, which is excluded from stats. When a model has no MSRP it is a catalog gap — add a real `DroneModelSpec` entry, don't second-guess the FCC name.

**Satellite classification:** `SatelliteAnalyzer` tiles ~2.2 km of ESRI imagery around a point and runs `YoloDetector` (`app/src/main/assets/satellite_yolo.tflite`, 15 DOTAv1 classes). Class counts feed `DroneClassifier`'s role heuristic, along with per-drone notes.

**Navigation:** `ui/AppRoot.kt` is a bottom-nav shell owning all cross-screen state — `selectedKey`/`focusKey`, `selectedFlight: FlightSummary?`, `flightMapKey`, and the history-window filter. Tab screens receive callbacks that mutate this state (e.g. `select(key)`, `onSelectFlight`). `DroneFlightsScreen` is the reusable one-drone trail map: pass a `(startTs, endTs)` window to show a single flight. `MapScreen` is the live map; map camera state is preserved across tab switches.

## Gotchas

- Android's `SQLiteDatabase.query()` wraps raw SQL as `SELECT * FROM (<sql>)`; use `rawQuery()` for hand-written SELECTs (this bug once killed the FAA crawl).
- Import dedup key must exclude timestamp (content-only), per user directive.
- `androidResources { noCompress += "db" }` exists because a sample dataset DB ships in assets (`large-dataset/skyspy-detections.db`).
- Root-level `skyspy-detections.db`, `yolov8n-obb.*`, and `large-dataset/` are untracked local artifacts (pulled DBs, model source weights), not app inputs; the app uses the copies under `app/src/main/assets/`.
- Default MQTT credentials in `SettingsRepository` are the public subscribe-only pair; they are intentionally committed.
