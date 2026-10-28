# JagaRider

Android app (Kotlin, no external libraries) for motorcycle riders to check rain before a ride: live rain
intensity at their location, a map of rain distribution within a chosen range (5/10/25/50 km), a 2-hour forecast, and
OTA self-update from GitHub Releases. UI text in Bahasa Melayu.
Package/applicationId `com.jagarider` — never change it (breaks OTA updates).

## Files
- `Weather.kt` – Open-Meteo client (no API key). One request for a 7×7 grid (49 points) spanning ±range
  around the rider; centre point first in the list, each `Point` has `row`/`col`. `current.precipitation` / `minutely_15` are mm per 15 min → ×4 = mm/h.
  `Level` thresholds (mm/h): 0.1 Renyai, 1 Ringan, 4 Sederhana, 10 Lebat, 30 Sangat lebat.
- `MapTiles.kt` – OSM tile loader (memory LRU + 7-day file cache in cacheDir/tiles, max 2 connections,
  proper User-Agent — OSM tile usage policy; never bulk-prefetch).
- `Views.kt` – Canvas views: `RainMapView` (OSM tiles, zoom fitted to range, grid bilinearly interpolated
  into a coloured overlay clipped to the range circle, pulsing rider marker, attribution), `LevelBarView`,
  `RainChartView`.
- `MainActivity.kt` – main screen (code-built UI): location (LocationManager, no Play services), verdict
  (🟢/🟡/🟠/🔴), auto-refresh every 5 min while resumed, "Kemas kini app" section.
- `Updater.kt` – checks `https://api.github.com/repos/<jagarider.updateRepo>/releases/latest` (repo in
  `gradle.properties`, `Muzamilmohd/JagaRider`), daily via `UpdateJob` + on app open, installs the release's
  `.apk` asset through `PackageInstaller`. Copied from JagaPMA (`com.jagalink`).
- `Prefs.kt` – SharedPreferences (range km, auto-update, last check, notified version).
- Logo `res/drawable/ic_launcher.xml`: rider on motorbike with rain cloud (vector).

## Testing
Phone connected via `adb` (wireless). `adb install -r app/build/outputs/apk/release/app-release.apk`,
`adb shell pm grant com.jagarider android.permission.ACCESS_FINE_LOCATION`, screenshots via `adb exec-out screencap -p`.

## Build
- minSdk 26, target/compileSdk 36, Gradle 9.1.0, AGP 8.13.0, Kotlin 2.2.20, bytecode 17.
- `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`; if Gradle says "Unable to establish loopback
  connection", set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\gtmp`.
- `local.properties` (gitignored) holds `sdk.dir`.

## Release & OTA
- Release key: `jagarider-release.jks` + `keystore.properties` (gitignored, **must be backed up**). Every OTA
  APK must be signed with it, otherwise install fails (signature mismatch).
- To release: bump `versionCode` + `versionName` in `app/build.gradle.kts`, `./gradlew assembleRelease`,
  copy `app/build/outputs/apk/release/app-release.apk` to `dist/JagaRider-v<ver>.apk`, then
  `gh release create v<ver> dist/JagaRider-v<ver>.apk --repo Muzamilmohd/JagaRider --notes "..."`.
  Tag must be `v` + versionName. Notes above a "## Cara pasang" heading are shown in the update dialog.
