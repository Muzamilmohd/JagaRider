# JagaRide

Display name **JagaRide** (renamed from JagaRider in v1.5). The package/applicationId stays `com.jagarider`
(changing it would make Android treat it as a new app and break OTA updates), as do the repo name
`Muzamilmohd/JagaRider`, source folder and keystore file names.

Android app (Kotlin, no external libraries) for motorcycle riders to check rain before a ride: live rain
intensity at their location, a map of rain distribution within a slider-chosen range (5–300 km or all of Peninsular Malaysia), a 2-hour forecast, and
OTA self-update from GitHub Releases. Developer credit: Muzamil Mohd (Settings → About + main screen footer).

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
- `RainAlert.kt` – `RainAlertJob` (JobScheduler, ~15 min, persisted) checks a 5×5 grid around the live location
  when ACCESS_BACKGROUND_LOCATION ("Allow all the time", optional, asked from Settings with an explanation
  dialog) is granted, otherwise around the last location
  saved while the app was open. `RescheduleReceiver` (MY_PACKAGE_REPLACED + BOOT_COMPLETED) re-schedules both
  jobs — Android drops scheduled jobs when the app is updated. Rain → channel `rain_alert_v2` with
  `res/raw/rain.wav`; heavy (≥10 mm/h) or thunderstorm → `storm_alert_v2` with `res/raw/storm.wav`. Channel
  sounds are fixed once created — bump the channel ID (and delete the old one) if a sound changes. Sounds (2 s, filtered
  noise only — tonal droplets sounded like birds) are synthesized by
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
- **Before uploading, verify the APK version**: `aapt dump badging dist/JagaRide-v<ver>.apk | head -1` must show the
  new versionCode/versionName. v1.9 and the first v2.0 upload shipped versionCode 9 ("1.8") because a failed command
  in a `&&` chain skipped the version bump — the updater then reinstalled 1.8 forever.

## Radar (v1.9)
- `Radar.kt` – RainViewer real radar for "now": `weather-maps.json` → latest `radar.past` frame (10-min). Free API
  limits: **zoom ≤ 7** (higher returns a "Zoom Level Not Supported" tile) and only the Universal Blue colour
  scheme. Pixel colour → dBZ by nearest match to the Universal Blue column of
  `rainviewer_api_colors_table.csv` (dBZ -10..75) → mm/h via Marshall-Palmer; < 12 dBZ = no rain.
  `scan()` walks every z7 pixel (~1.2 km) in the area → centre (≤1.5 px), nearest, heaviest, heaviest ≤ 50 km.
- Main screen + rain alerts use radar for current rain/nearest/verdict; Open-Meteo stays for forecasts and
  is the fallback when radar fails. Model thunderstorm codes only count when radar shows ≥ 4 mm/h.
- Map layer switches automatically (no toggle, v2.0): range ≤ 50 km → radar tiles (`MapTiles.radar`, memory-only,
  z7 stretched) — same source as status/alerts; ≥ 100 km / Peninsula → model overlay (`Radar.shownFor`). The
  nearest/heaviest text follows the map source; a non-clickable label on the map names the source.
  Attribution: RainViewer on map + About.
- Clock: `Clock` (I18n.kt) formats every displayed time 12h/24h (`Prefs.clock24`, default = phone setting);
  API times stay "HH:mm" internally — format only at display (`Clock.hm`, `Clock.hour`, `Clock.time`).

## Riders tab (v2.2)
- Bottom tab bar (MainActivity): 🌧️ Weather | 🏍️ Riders (`RiderPage.kt`). Notification/`EXTRA_RIDER_TAB` opens Riders.
- Backend: Firebase project `jagaride-427d6` (owner: the user's Google account), via REST only (`Firebase.kt`):
  Anonymous Auth (identitytoolkit signUp / securetoken refresh) + Realtime Database (Singapore). Config lives in
  **`firebase.properties`** (gitignored, keep a backup: `dbUrl=…`, `apiKey=…`) → `BuildConfig.FIREBASE_DB/KEY`.
  Without it the Riders tab shows "not set up".
- Groups (`RiderGroup.kt`): invite code `JR-XXXX-XXXX-XXXX-XXXX-XXXX` (100-bit secret). gid = SHA-256("gid:"+code)[:32],
  AES-GCM key = SHA-256("key:"+code)[:16]. Paths: `groups/{gid}/meta` {n: enc(name), t}, `groups/{gid}/m/{uid}`
  {d: enc({n,a,o,s}), t: server timestamp}. Server never sees names/locations in clear. One active group per phone.
- Rules: `firebase/database.rules.json` (paste into console → Realtime Database → Rules → Publish).
- `LocationShareService`: foreground service (type location) started only by the user's "Start sharing" button;
  GPS/network updates every 5 s, uploads every ≥10 s; Stop in app or notification removes my entry.
- RiderPage polls `groups/{gid}/m` every 8 s while visible; map = `RainMapView.setCenter` + `riders` markers,
  auto-zoom to fit; hides riders idle > 2 h, greys > 5 min.
