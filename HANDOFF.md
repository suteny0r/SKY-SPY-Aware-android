# SKY-SPY-Aware — Handoff

Last updated: 2026-08-17. Project: Android drone-detection analytics app
(`F:\OUI-SPY\SKY-SPY-Aware-android`). This file carries session context that
the repo itself doesn't; read CLAUDE.md first for build commands and
architecture.

## Current state
- Version: **1.3.0 (versionCode 3)**, released with both APKs at
  https://github.com/suteny0r/SKY-SPY-Aware-android/releases/tag/v1.3.0
  and installed on the test device. 1.2.0 was skipped — that tag was already
  used by an earlier release; existing release tags are NOT sequential
  (v1.1.9 predates v1.1.0/v1.2.0), so always check `gh release list` before
  tagging.
- The expandable "Drone models" tree (model -> drone IDs -> flights -> trail
  on map) shipped in f01dfe1 and is no longer pending.
- An iOS port exists at `F:\OUI-SPY\sky-spy-aware-ios`
  (github.com/suteny0r/sky-spy-aware-ios, ported by DeepSeek then corrected).
  Its FEATURE-GAPS.md treats this Android app as the authoritative reference.
- v1.3.0 was a large correctness sweep (commit fb1aeab) informed by the iOS
  corrections plus a four-reviewer audit of this codebase. See that commit
  message for the full list. Load-bearing invariants it introduced:
  - `SatelliteAnalyzer.AreaScan.complete`: scans with any failed imagery tile
    must never be cached (DataRepo auto paths skip caching them; manual scan
    buttons still apply results).
  - `YoloDetector.Detection` coordinates are normalized to the SOURCE bitmap
    (letterboxing is internal); `corners` are precomputed aspect-true. TFLite
    inference is serialized per interpreter — keep it that way.
  - `droneMap` / `faaCache` / `faaPlatformLabels` snapshots must be taken via
    `publishDrones()` / `publishFaa()` (all guarded by their monitors;
    faaPlatformLabels uses the faaCache monitor).
  - FAA lookups: the ONLY definitive not-found is a 2xx with an empty items
    array. Every other failure (3xx/401/403/parse error/blank body) must stay
    retriable or a captive portal marks the fleet as simulators forever.
  - UI simulator tests go through `DataRepo.isSimulator` (public), never
    `== FAA_NOT_FOUND`.
  - `DroneCatalog.matchKeys` must contain a model token, never a bare brand
    name ("teal" invented MSRPs from make-only FCC records). Grouping labels
    go through `DroneCatalog.canonicalLabel` (strips "(serial)" suffixes).
  - Altitude histogram is 9 bins (0-199m in 25m steps + 200m+); keep
    `StatisticsCalculator.altBins` and `StatsScreen.ALT_LABELS` in sync.
  - `StatCell(label, value)` — callers pass the caption first.
  - MapScreen: wrap programmatic `controller.setCenter/setZoom` in
    `moveCamera {}` or the osmdroid listener trips the `userMoved` guard and
    kills startup centering.

## Device / build / deploy
- Device `R5CN70YWT5Z` (SM-N986U, Android 13). Disconnects often; reconnect
  with `adb devices` (may need a few seconds / retries).
- Build + install: `.\gradlew.bat :app:installDebug` (APK-only; PRESERVES the
  on-device DB). Release: `.\gradlew.bat :app:assembleRelease` ->
  `app/build/outputs/apk/release/app-release.apk` (debug-signed).
- User directive: deploy to device after every code fix.
- Health check: `adb logcat -c; adb shell am start -n
  com.suteny0r.skyspyaware/.MainActivity; sleep 8; adb logcat -d *:E |
  grep -iE "skyspy|Exception|SQLite|FATAL"`. Expected noise only:
  `RequestManager_FLP` GPS logs, and `LoadedApk isPerfLogEnable` NPEs from
  OTHER processes (Samsung framework) — check the pid against
  `adb shell pidof com.suteny0r.skyspyaware` before blaming the app.
- DB: `/data/data/com.suteny0r.skyspyaware/databases/detections.db`
  (schema v7). **Pull binary-safe** with `F:\TEMP\opencode\pull_dev.py`
  (PowerShell `adb exec-out ... cat` DOUBLES binary). **Push**: `adb push` to
  `/data/local/tmp` then `adb shell run-as com.suteny0r.skyspyaware cp ...`
  (`run-as` cannot write to `/data/local/tmp`). Do NOT push a stale DB over
  the device copy — the historical FAA crawl grows faa_cache continuously.
- Import dedup key MUST exclude timestamp (content-only).
- `F:\TEMP\opencode\import_logs.py` builds `imported_detections.db` from logs
  (1 Hz timestamps + flights-from-markers). Imports are now validated
  (user_version <= 7, detections table present) and a failed import no longer
  kills persistence.

## Catalog (`DroneCatalog.kt`)
- Model names come programmatically from FCC `Make:`/`Model:` lines in
  faa_cache text; they are rarely wrong — when a new model has no MSRP, add a
  REAL `DroneModelSpec` entry (catalog gap, not a data bug).
- `match()` is longest-key-wins over normalized text; bare makes must match
  nothing (see invariant above). Dual-use drones (Matrice 30/300/350/4E/4T/4P,
  Mavic 2/3 Enterprise, Skydio 2+/X10, Teal 2, Draganfly Commander,
  WingtraOne) are badged INDUSTRIAL + PUBLIC_SAFETY intentionally;
  `Statistics.identifiedCount` exists so UI counts each drone once.

## Known deliberate limitations (do not "fix" silently)
- `isValidPosition` rejects |lat| < 0.5 OR |lon| < 0.5: a documented
  corrupt-fix tradeoff that also blinds the app near the equator/prime
  meridian (London!). Revisit only if non-US data is expected.
- Tile indices are not wrapped at the antimeridian/poles (unreachable with
  plausible drone telemetry).
- NMS/letterbox coordinate frame is source-normalized (slightly anisotropic
  IoU for non-square viewport scans); exact for the square satellite path.
