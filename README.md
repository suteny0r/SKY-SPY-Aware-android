# SKY-SPY-Aware (Android)

Native Android port of [SKY-SPY-Aware](https://github.com/suteny0r/SKY-SPY-Aware), the live drone detection dashboard for OUI-SPY Sky Spy. Default mode is **MQTT subscriber**: the app connects to a shared Sky Spy feed and plots drone + pilot positions live, with no sensor, serial port, or server required on the phone.

## What it does

- **MQTT subscriber (default)** - connects to a broker, subscribes to `skyspy/raw`, and parses detection JSON lines
- **Live map** - drone markers colored by altitude (green <50m, yellow <150m, orange <400m, red 400m+), pilot pins, and dashed drone-to-pilot lines
- **Console** - scrolling raw feed, detections highlighted
- **Settings** - broker host/port/TLS/credentials/topic, persisted locally
- Ships with the public subscribe-only credentials so it works out of the box against the shared feed

## Default connection

| Setting | Value |
|---|---|
| Broker | `65604cba457d4f8992aefe5820219ae4.s1.eu.hivemq.cloud` |
| Port | `8883` (TLS) |
| Username | `skyspy` |
| Password | `skyspyaware` |
| Topic | `skyspy` |

These are read-only subscriber credentials (the `mqtt_consumer_secrets` pair). They can only subscribe, not publish.

## Detection format

Handles the Sky Spy JSON lines published on `<topic>/raw`:

```json
{"mac":"8c:1e:d9:00:99:8b","rssi":-80,"drone_lat":25.788074,"drone_long":-80.172089,"drone_altitude":79,"pilot_lat":25.789348,"pilot_long":-80.172851,"basic_id":"1581F895725840H5P4GZ"}
```

Drones age out after 60 seconds without new data.

## Building

- Android Studio (Ladybug+) or command line with JDK 17+
- Open the folder, let Gradle sync, Run
- Or: `gradlew assembleDebug` -> APK at `app/build/outputs/apk/debug/app-debug.apk`

Stack: Kotlin, Jetpack Compose, Material 3, Eclipse Paho MQTT (TLS via system CA store), osmdroid (OpenStreetMap, no API key).

## Project structure

```
app/src/main/java/com/suteny0r/skyspyaware/
├── MainActivity.kt        # entry point + osmdroid config
├── Detection.kt           # JSON parser + Drone model
├── SettingsRepository.kt  # broker config (defaults to consumer creds)
├── MqttManager.kt         # Paho subscriber
├── SkySpyViewModel.kt     # drone state, 60s age-out, console buffer
└── ui/
    ├── AppRoot.kt         # bottom-nav shell (Map / Console / Settings)
    ├── MapScreen.kt       # osmdroid map + markers + lines
    ├── ConsoleScreen.kt   # scrolling raw feed
    ├── SettingsScreen.kt  # broker config + connect/disconnect
    └── Theme.kt
```

## Related

- [SKY-SPY-Aware](https://github.com/suteny0r/SKY-SPY-Aware) - the Python/Flask web dashboard this is ported from
- [OUI-SPY Unified Blue](https://github.com/colonelpanichacks/oui-spy-unified-blue) - ESP32-S3 Sky Spy mode firmware
