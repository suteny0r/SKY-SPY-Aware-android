# SKY-SPY-Aware — Handoff

Last updated: 2026-08-13. Project: Android drone-detection analytics app
(`F:\OUI-SPY\SKY-SPY-Aware-android`). User is about to switch AI models; this
file lets the next session continue without re-deriving context.

## Objective
Location-agnostic drone classification from satellite object detection + FAA
(FC) registration lookups. Two analytics tabs of interest:
- **Drones** tab (`DroneStatsScreen`, AppRoot tab 5): drone inventory
  (make/model/MSRP/pilot-profile/fleet-value, simulator-excluded).
- **Flights** tab (`FlightsScreen`, AppRoot tab 6): every individual flight
  from full history, log-derived.
Fix MSRP/model attribution so it reflects only FCC-supplied model names;
parenthetical serial suffixes must not break matches; non-DJI law-enforcement
drones must be badged; and the **"Drone models" section must become an
expandable tree** (see PENDING FEATURE below).

## Device / build / deploy
- Device `R5CN70YWT5Z` (SM-N986U, Android 13). Disconnects often; reconnect
  with `adb devices` (may need a few seconds / retries).
- Build + install: `cd F:\OUI-SPY\SKY-SPY-Aware-android; .\gradlew.bat :app:installDebug`
  (APK-only; PRESERVES on-device DB). Release: `.\gradlew.bat :app:assembleRelease`
  -> `app/build/outputs/apk/release/app-release.apk`.
- DB: `/data/data/com.suteny0r.skyspyaware/databases/detections.db`.
- **Pull DB binary-safe** (PowerShell `adb exec-out ... cat` DOUBLES binary):
  use `F:\TEMP\opencode\pull_dev.py` (Python subprocess) -> writes
  `F:\TEMP\opencode\dev_pull2.db`.
- **Push DB**: `adb push imported_detections.db /data/local/tmp/detections.db`
  then `adb shell run-as com.suteny0r.skyspyaware cp /data/local/tmp/detections.db
  /data/data/com.suteny0r.skyspyaware/databases/detections.db`.
  `run-as` CANNOT write to `/data/local/tmp`. Only push if you intend to reset
  the on-device caches.
- User directive: deploy to device after every code fix. Import dedup key MUST
  exclude timestamp (content-only).
- Health check: `adb logcat -c; adb shell am start -n com.suteny0r.skyspyaware/.MainActivity;
  sleep 8; adb logcat -d *:E | grep -i "skyspy|Exception|SQLite|SELECT|FATAL"`.
  The only expected errors are `RequestManager_FLP` GPS logs (OS noise, not ours).

## Catalog (`app/src/main/java/com/suteny0r/skyspyaware/DroneCatalog.kt`)
- `enum class DroneCategory { TOY, PROSUMER, CINEMATOGRAPHY, INDUSTRIAL, PUBLIC_SAFETY }`.
  `INDUSTRIAL` -> COMMERCIAL profile; `PUBLIC_SAFETY` -> Public-safety agency.
- `data class DroneModelSpec(displayName, msrpUsd, category, matchKeys,
  extraCategories = emptyList())`. `categories` = listOf(category)+extra.
  `pilotProfiles: Set<PilotProfile>` = all categories mapped to profiles
  (this is what makes a dual drone "badged both ways").
  `pilotProfile` (singular) = primary category's profile (back-compat).
- `match(make, model)`: `normalize()` lowercases, strips `(...)` suffixes
  (e.g. `Mavic Air 2 (MA2UE3W)`), collapses whitespace; then **longest-key-wins**
  among keys contained in the normalized query. A bare make with no model
  (e.g. `q="dji"`) matches nothing -> no invented MSRP (prevents skew).
- `msrpForLabel("Make Model")` and `profilesForLabel("Make Model")`: split on
  first space, call match.
- **Dual-use (badged INDUSTRIAL + PUBLIC_SAFETY)** — intersection drones:
  DJI Matrice 30 / 300 RTK / 350 RTK, DJI Mavic 2 Enterprise, DJI Mavic 3
  Enterprise, Skydio 2+, Skydio X10, Teal 2, Draganfly Commander, WingtraOne,
  DJI Matrice 4E / 4T / 4P.
- **Pure PUBLIC_SAFETY**: Brinc Lemur, Parrot ANAFI USA.
- **Pure INDUSTRIAL**: Parrot Bluegrass, DJI Agras T50, DJI FlyCart 30.
- NOTE: model names come programmatically from FCC `Make:`/`Model:` lines in
  faa_cache text; user says they are rarely errors — add REAL catalog entries
  rather than second-guessing names. When a new model appears with no MSRP,
  it means it is not yet in the catalog (a catalog gap, not a data bug).
- Current device faa_cache = 362 rows; **all 21 distinct model strings now
  match** the catalog (last gap was `DJI Matrice 4E`, since added). The
  historical FAA crawl is still growing the cache over time.

## Known prior fixes (all deployed)
- `distinctBasicIds()` in `DetectionCache.kt`: was `db.query("SELECT DISTINCT
  basic_id FROM detections", emptyArray())` -> Android wraps as
  `SELECT * FROM SELECT DISTINCT...` (syntax error, threw, killed the
  historical lookup crawl). Changed to `db.rawQuery(...)`.
- Simulator detection: `FaaClient.kt` `else -> FaaLookup(FAA_NOT_FOUND, false)`
  (was `"No registration data"`, which didn't match the filter sentinel).
  `DataRepo.isSimulator(result)` matches both sentinels; `flightsFromDb` takes
  `simulatorKeys` to skip simulator flights; added debounced
  `scheduleStatsRecompute()` after each lookup so tabs update live.
- Flights tab DB-backed: `FlightRecord` + `flights` table (DB v4->v5) +
  `DetectionCache.loadFlights()`; `StatisticsCalculator.computeFlights` and
  `flightsFromDb` implemented; `DataRepo.refreshFlights()` + `emitFlights`
  branch prefer DB flights.
- MSRP shown in Drones tab "Drone models" `ModelRow` via `msrpForLabel`, tiny
  `$X` next to name; column `weight(1f)`, bar `width(72.dp)` (no truncation).

## PENDING FEATURE — expandable tree in "Drone models"
User request (verbatim intent): "drone models should be an expandable tree.
tapping the model should reveal leaves for each drone ID. tapping drone ID
should reveal flight list for that drone. tapping flight should show trail on
map."

Building blocks ALREADY EXIST (reuse them):
- `AppRoot.kt` (tab 5 = Drones via `DroneStatsScreen(vm, onSelect = {key -> select(key)})`).
  Navigation state: `selectedFlight: FlightSummary?` and `flightMapKey: String?`
  both route to `DroneFlightsScreen(vm, droneKey, onBack, window)` (see below).
  `select(key, openMap)` sets `selectedKey`/`focusKey` and switches tab.
- `DroneFlightsScreen.kt` = full-screen osmdroid map for ONE drone across a
  `(startTs, endTs)` window. Loads via `vm.loadDroneFlights(droneKey, startTs,
  endTs)` -> `List<TrailPoint>` and draws a polyline trail + scrub slider. This
  is exactly the "show trail on map" target. Pass `window = startTs to endTs`
  to show a single flight's trail.
- `FlightsScreen.kt` (tab 6) already calls `onSelectFlight = { selectedFlight = it }`
  where `it` is a `FlightSummary(droneKey, startTs, endTs)`; that triggers
  `DroneFlightsScreen` with that flight's window. Mirror this pattern.

Data needed for the three tree levels (VERIFY exact fields/availability on
resume):
- L1 model -> L2 drone IDs: `Stats` exposes `modelCounts: Map<String,Int>` and
  `topDrones: List<DroneStats>` (each `DroneStats` has `key` and `model`).
  Group `topDrones` by `model` to get, per model, the list of drone keys.
  CONFIRM `topDrones` is the full list (not top-N) and that `DroneStats.model`
  is populated. Alternative: query `DetectionCache` directly grouped by model.
- L2 drone ID -> L3 flights: need flight records grouped by drone key. Source
  is the `flights` table via `DetectionCache.loadFlights()` ->
  `List<FlightRecord>` (each has droneKey/basicId, startTs, endTs, points
  trail). `FlightsScreen` already consumes these — find how it gets them
  (VM state or direct load) and reuse. Group `FlightRecord`s by `droneKey`.
- L3 flight tap -> map: call back into AppRoot to set
  `selectedFlight = FlightSummary(droneKey, startTs, endTs)` (or add an
  `onShowFlight` callback to `DroneStatsScreen` that AppRoot wires to set
  `selectedFlight`). This reuses the existing `DroneFlightsScreen` route.

Implementation sketch for `DroneStatsScreen.kt` "Drone models" section:
- Replace the flat `modelCounts.forEach { ModelRow(...) }` with an expandable
  `Column` of `ModelNode(model, count, dronesForModel, max)` using
  `remember { mutableStateOf(false) }` expand state; tapping toggles.
- `ModelNode` shows name + `$MSRP` + count + expand caret; when expanded,
  renders `DroneIdNode` per drone key in that model.
- `DroneIdNode` shows the key (basic_id/MAC) + flight count; tapping expands
  to a `FlightNode` list.
- `FlightNode` shows flight start/end time + duration; tapping invokes
  `onShowFlight(droneKey, startTs, endTs)` -> AppRoot -> `DroneFlightsScreen`.

Files to touch: `DroneStatsScreen.kt` (tree UI + `onShowFlight` param),
`AppRoot.kt` (wire `onShowFlight` -> `selectedFlight`), possibly
`StatisticsCalculator.kt`/`Stats` (expose per-drone flight grouping if not
already available). `FlightSummary` + `loadDroneFlights` signatures are the
contract to mirror — read them first.

## Key files
- `DroneCatalog.kt` — catalog + matching (see above).
- `DroneStatsScreen.kt` — Drones tab UI; "Drone models" `ModelRow`, profile
  chips, `pilotProfileCounts`/`categoryCounts` charts.
- `StatisticsCalculator.kt` — `Stats` data class, `DroneStats`, `modelCounts`,
  `topDrones`, `computeFlights`, `flightsFromDb(records, faaText, simulatorKeys)`,
  `pilotProfileCounts`, `categoryCounts`. Both profiles/categories counted for
  dual drones.
- `AppRoot.kt` — navigation; `selectedFlight`/`flightMapKey` -> `DroneFlightsScreen`;
  `select()`, `pendingSelection`, history window filter.
- `DroneFlightsScreen.kt` — osmdroid full-history map for one drone + window
  slider. Reuse for "show trail on map".
- `FlightsScreen.kt`, `DroneDetail.kt`, `ListScreen.kt`, `MapScreen.kt` — other
  tabs (reference for patterns; `MapScreen` draws live `vm.drones` trails).
- `DataRepo.kt` — `isSimulator`, `flightsFromDb`, `scheduleStatsRecompute`.
- `DetectionCache.kt` — `distinctBasicIds()` (rawQuery fix), `flights` table,
  `loadFlights()`, `FlightRecord`.
- `FaaClient.kt` — `FAA_NOT_FOUND` sentinel + `else` branch fix.
- `import_logs.py` (`F:\TEMP\opencode\`) — log import with 1 Hz timestamps +
  flights-from-markers; regenerated `imported_detections.db` (163k rows, 6715
  flights) already pushed once.
- `pull_dev.py` (`F:\TEMP\opencode\`) — binary-safe device DB pull.
- On-device DB currently has 362 faa_cache rows (historical crawl in progress);
  do NOT push a stale DB over it.
