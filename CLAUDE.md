# JagaRide

Display name **JagaRide** (renamed from JagaRider in v1.5). The package/applicationId stays `com.jagarider`
(changing it would make Android treat it as a new app and break OTA updates), as do the repo name
`Muzamilmohd/JagaRider`, source folder and keystore file names.

Android app (Kotlin, no external libraries) for motorcycle riders to check rain before a ride: live rain
intensity at their location, a map of rain distribution within a slider-chosen range (5–300 km or all of Peninsular Malaysia), a 2-hour forecast, and
OTA self-update from GitHub Releases. Developer credit: Mohd Muzamil (Settings → About + main screen footer).

UI text in 4 languages, default **Bahasa Melayu**, selectable in Settings: Melayu / English / 中文 / தமிழ்
(`Lang`, `I18n.lang`, `Prefs.lang`). Every user-facing string goes through `t(ms, en, zh, ta)` (I18n.kt) —
always fill all four. In Kotlin templates next to Chinese/Tamil text use `${name}`, not `$name`.
Light/dark/follow-system theme (`Prefs.theme`): colours come from `Palette` (Ui.kt); views read
`Ui.palette(ctx)`; the map gets an invert + hue-rotate ColorMatrix in dark mode. Activities extend
`BaseActivity` (Ui.kt), which applies language + theme before `onCreate` and `recreate()`s on change.

## Files
- `Weather.kt` – Open-Meteo client (no API key). Range slider stops 5–300 km + `PENINSULA` (999 = whole
  Peninsular Malaysia, fixed area centred 3.95°N 102°E). `area()` picks grid size 7/9/11 by range; one request for
  the grid. Rider point always first in the list (row/col = -1 when outside the grid, i.e. Peninsula mode).
  Bigger areas refresh less often (5/10/15 min) to stay within the Open-Meteo free limit. `current.precipitation` / `minutely_15` are mm per 15 min → ×4 = mm/h.
  `Level` thresholds (mm/h): 0.1 Renyai, 1 Ringan, 4 Sederhana, 10 Lebat, 30 Sangat lebat.
- `MapTiles.kt` – OSM tile loader (memory LRU + 7-day file cache in cacheDir/tiles, max 2 connections,
  proper User-Agent — OSM tile usage policy; never bulk-prefetch).
- `Views.kt` – Canvas views: `RainMapView` (OSM tiles, zoom fitted to range, grid bilinearly interpolated
  into a coloured overlay clipped to the range circle, pulsing rider marker, attribution), `LevelBarView`,
  `RainChartView` (15-min bars with weather icons; shrinks when dry). Hourly strip (6 h: icon, temp,
  precipitation_probability) is built in `MainActivity.renderHours`. `Weather.icon(code, "HH:mm")` = emoji, day 7–18.
- `MainActivity.kt` – main screen (code-built UI): location (LocationManager, no Play services), verdict
  (🟢/🟡/🟠/🔴), auto-refresh every 5 min while resumed, ⚙️ button → `SettingsActivity`.
- `SettingsActivity.kt` – language, display mode, rain alerts, app updates (check button + daily switch), About/credit.
  Radio buttons use `View.generateViewId()` + `isSaveEnabled = false`: shared IDs made recreate() restore the
  wrong group and the language got stuck (fixed v1.6).
- `RainAlert.kt` – `RainAlertJob` (JobScheduler, ~15 min, persisted) checks a 5×5 grid around the last location
  saved while the app was open (no background-location permission). Rain → channel `rain_alert_v1` with
  `res/raw/rain.wav`; heavy (≥10 mm/h) or thunderstorm → `storm_alert_v1` with `res/raw/storm.wav`. Channel
  sounds are fixed once created — bump the channel ID if a sound changes. Sounds are synthesized by
  `tools/GenSounds.java` (`java tools/GenSounds.java app/src/main/res/raw`). Test: `adb shell cmd jobscheduler run -f com.jagarider 4343`.
  `RECEIVE_BOOT_COMPLETED` is required for `setPersisted(true)` (without it the jobs silently never schedule).
- `UpdateUi.kt` – shared check → dialog → install flow (main screen auto-check + Settings button).
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
  copy `app/build/outputs/apk/release/app-release.apk` to `dist/JagaRide-v<ver>.apk`, then
  `gh release create v<ver> dist/JagaRide-v<ver>.apk --repo Muzamilmohd/JagaRider --notes "..."`.
  Tag must be `v` + versionName. Notes above a "## Cara pasang" heading are shown in the update dialog.
