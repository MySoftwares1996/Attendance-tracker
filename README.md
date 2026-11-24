# Attendance Tracker (Android)

An Android app that works out whether an employee is **in the office during working hours**, **outside the office during working hours**, **on a lunch break**, or **outside working hours**. It does this from the phone's GPS position, the office location and its gates.

## Features

- **Working hours**: choose one of two modes.
  - **Fixed**: From/To times. Night shifts that run past midnight work too.
  - **Flexible**: arrive any time inside a window (for example 8:00–11:00). Expected logout = actual arrival + hours to work. For example, with 9 h: arrive at 9 → out at 18:00; arrive at 10 → 19:00; arrive at 11 → 20:00. Arriving after the window is flagged *Late*.
  - **Extra lunch extends logout**: lunch beyond the daily allowance is added to the logout time automatically.
  - **Overtime**: time in the office after logout is counted as overtime until a cutoff you choose (for example 23:00).
- **Lunch break declaration**: tap *Declare lunch break* to start and *End lunch break* to finish. You can set a lunch window, a maximum length, and optionally allow lunch only inside the window. An alert fires if lunch goes over the limit. An open lunch ends automatically when working hours finish.
- **Office and gates**:
  - Stand in the middle of the office and tap *Use my current location*. The app averages 20 seconds of GPS fixes.
  - Set the premises radius.
  - Stand at each gate and add it.
  - Every entry and exit is recorded with the gate used ("Left HQ via Main Gate").
- **Status**: one of In office, Outside office (working hours), On lunch break, Lunch break exceeded, Outside working hours, or Location unknown. A notification fires when you leave the office during working hours.
- **Today summary and 14-day history**: first entry, last exit, time in office, time outside during working hours, lunch time, and the event log with GPS accuracy.

## Automatic IN/OUT punches (the core)

Every entry and exit is recorded automatically. Several independent layers make sure no punch is missed:

| Layer | What it does |
|---|---|
| **Primary GPS tracker** (foreground service) | Adaptive polling: every 5 s within about 200 m of the boundary or while a change is being confirmed, 15 s around the office, 60 s when more than 3 km away, 5 min outside the working day. |
| **Gate-timed punches** | Arrival = the first moment seen at a gate. Leaving = the last moment seen at a gate. Each punch records which gate was used. |
| **System geofences** (backup) | Google Play services watches the office and every gate, and wakes the app on entry or exit even if the phone killed it. |
| **Self-healing** | A watchdog runs every 15 min, tracking restarts if the app is swiped away, and everything is re-armed after a reboot or app update. |
| **Gap reporting** | A heartbeat logs any period without tracking. A punch detected after a gap is marked *approximate* instead of being shown as exact. |
| **Permission watchdog** | Turning GPS off, or downgrading location access, raises an immediate alert. Tracking recovers automatically. |
| **Phone-maker Autostart** | Guided step for Xiaomi, Oppo, Vivo, Realme, OnePlus, Huawei and similar phones, whose battery managers kill background apps. |

## How accuracy is enforced

1. **Bad fixes are thrown away.** This covers mock/fake GPS apps, fixes older than 60 s, fixes with accuracy worse than the limit (default ±35 m), and impossible jumps (over 216 km/h).
2. **The whole error circle must agree.** A fix counts as *inside* only if its entire accuracy circle is inside the premises radius, and as *outside* only if the entire circle is outside. Fixes on the boundary never change the status.
3. **Confirmation (hysteresis).** In/out only changes after 3 consecutive confident fixes agree (configurable), so GPS jitter cannot create false punches.

For the best results, follow the in-app setup steps: precise location set to *All the time*, battery unrestricted, and Autostart on where the phone has it. Capture the office and every gate while standing at them outdoors.

## Getting the APK

Every push runs the **Build APK** GitHub Actions workflow, which runs the unit tests and builds `AttendanceTracker.apk`. Download it from *Actions → latest run → Artifacts*.

## OTA (over-the-air) updates

The installed app updates itself. It checks for updates when opened and every 6 hours while tracking runs, then shows **Update available → Install update**. The new APK is downloaded, its SHA-256 checksum is verified, and it is installed through Android's PackageInstaller.

**One-time setup:**

1. **Signing key.** Add 4 repository secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Every release must be signed with the same key, because Android rejects updates signed with any other. Back up the keystore; if it is lost, phones can no longer be updated.
2. **Where the app downloads updates.** By default it reads `https://github.com/<owner>/<repo>/releases/latest/download/update.json`. That URL only works for a **public** repository. If the repo stays private, either:
   - publish releases to a public repository or other public host, then set the Actions variable `UPDATE_MANIFEST_URL` to that `update.json` URL; or
   - use Google Play internal testing or Firebase App Distribution instead.
3. **On the phone.** The first time you tap *Install update*, allow "Install unknown apps" for Attendance Tracker.

**Release flow: test first, then confirm.**

1. Every push to the default branch builds a **test build**, published as a GitHub *pre-release* (`v1.0.N`) with the APK. Install it by hand to try it. **Phones do not receive it over the air**, because the app only reads the latest *non*-pre-release.
2. After the test build is confirmed, run *Actions → Build APK → Run workflow* with **promote_tag** = `v1.0.N`. That marks the build as the official release and phones update over the air.

`update.json` format:

```json
{"versionCode": 14, "versionName": "1.0.14", "apkUrl": "https://…/AttendanceTracker.apk", "sha256": "…", "notes": "…"}
```

Only builds signed with your release key are published. Note that the APK built before the key was added is debug-signed: uninstall it once and install the first signed release. After that, every update arrives over the air.

## Building locally

Requires Android Studio or an Android SDK with JDK 17:

```
./gradlew assembleRelease     # APK in app/build/outputs/apk/release/
./gradlew testDebugUnitTest   # unit tests for the presence/status logic
```

## Code map

| Path | Purpose |
|---|---|
| `core/PresenceEngine.kt` | GPS filtering and inside/outside decision with gate attribution |
| `core/StatusResolver.kt` | Working-hours, lunch and status rules |
| `AttendanceController.kt` | Ties location, schedule and lunch together; logs events and durations |
| `service/TrackingService.kt` | Foreground location service |
| `service/LocationCapture.kt` | Averaged precise capture for the office and gates |
| `update/UpdateManager.kt` | OTA check, download, checksum verification and install |
| `ui/` | Today, History and Setup screens (Jetpack Compose) |
