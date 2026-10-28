# JagaRider

Android app (Kotlin, no external libraries) for motorcycle riders. Currently a **shell app**: main screen +
OTA self-update from GitHub Releases. Rider features to be added later. UI text in Bahasa Melayu.
Package/applicationId `com.jagarider` — never change it (breaks OTA updates).

## Files
- `MainActivity.kt` – main screen (code-built UI) incl. "Kemas kini" section: check button + daily auto-check switch.
- `Updater.kt` – checks `https://api.github.com/repos/<jagarider.updateRepo>/releases/latest` (repo in
  `gradle.properties`, `Muzamilmohd/JagaRider`), daily via `UpdateJob` + on app open, installs the release's
  `.apk` asset through `PackageInstaller`. Copied from JagaPMA (`com.jagalink`).
- `Prefs.kt` – SharedPreferences (auto-update, last check, notified version).

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
