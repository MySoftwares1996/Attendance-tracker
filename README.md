# Attendance Tracker (Android)

An Android app that works out whether an employee is **in the office during working hours**, **outside the office during working hours**, **on a lunch break**, or **outside working hours**. It does this from the phone's GPS position, the office location and its gates.

## Features

- **Working hours**: set From/To times and working days. Night shifts that run past midnight work too.
- **Lunch break declaration**: tap *Declare lunch break* to start and *End lunch break* to finish. You can set a lunch window, a maximum length, and optionally allow lunch only inside the window. An alert fires if lunch goes over the limit. An open lunch ends automatically when working hours finish.
- **Office and gates**:
  - Stand in the middle of the office and tap *Use my current location*. The app averages 20 seconds of GPS fixes.
  - Set the premises radius.
  - Stand at each gate and add it.
  - Every entry and exit is recorded with the gate used ("Left HQ via Main Gate").
- **Status**: one of In office, Outside office (working hours), On lunch break, Lunch break exceeded, Outside working hours, or Location unknown. A notification fires when you leave the office during working hours.
- **Today summary and 14-day history**: first entry, last exit, time in office, time outside during working hours, lunch time, and the event log with GPS accuracy.

## How accuracy is enforced

1. **Bad fixes are thrown away.** This covers mock/fake GPS apps, fixes older than 60 s, fixes with accuracy worse than the limit (default ±35 m), and impossible jumps (over 216 km/h).
2. **The whole error circle must agree.** A fix counts as *inside* only if its entire accuracy circle is inside the premises radius, and as *outside* only if the entire circle is outside. Fixes on the boundary never change the status.
3. **Confirmation (hysteresis).** In/out only changes after 3 consecutive confident fixes agree (configurable), so GPS jitter cannot flip the status.
4. **Exact time.** An entry or exit is timestamped at the first fix of the confirming run, not at the moment it was confirmed.
5. **High-accuracy GPS around work time.** The app uses high-accuracy GPS (one fix every 15 s) from 30 min before working hours until 30 min after. Outside that period it switches to low power.
6. **Survives restarts.** Tracking runs in a foreground service and restarts after a reboot.

For the best results, allow location **"All the time"** with **Precise** on, disable battery optimisation for the app (the Today screen prompts for both), and capture the office and gates outdoors.

## Getting the APK

Every push triggers the **Build APK** GitHub Actions workflow, which runs the unit tests and builds the APK. Open *Actions → Build APK → latest run → Artifacts → AttendanceTracker-apk*, download it, unzip it, and install `app-release.apk` on the phone. You may need to allow "Install unknown apps".

The APK is signed with the debug key, which is fine for internal use. For Play Store distribution, add your own signing config in `app/build.gradle.kts`.

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
| `ui/` | Today, History and Setup screens (Jetpack Compose) |
