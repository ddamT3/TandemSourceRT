# TandemSourceRT Toolchain and Environment

**Updated:** 2026-08-18

**Documented release:** `v02.01.004`

## Project Rules

- Use the Gradle wrapper included in the repository.
- Do not commit test-only releases.
- Keep local SDK paths, credentials, tokens, cookies, HAR files, exported
  medical data, and diagnostic captures out of Git.
- The application runtime is entirely Kotlin. Python files under
  `app/src/test/python` are parity-test references only.
- Create source delivery archives from committed content with `git archive`.

## Verified Tools and Versions

| Component | Verified version | Source |
|---|---:|---|
| Windows | 10.0.19045 | Local build environment |
| PowerShell | 7.6.4 | `$PSVersionTable` |
| Git for Windows | 2.55.0.windows.4 | `git --version` |
| JDK | Eclipse Temurin 17.0.20+8 | `java -version` |
| Gradle Wrapper | 8.7 | `gradle-wrapper.properties` |
| Android Gradle Plugin | 8.6.1 | Gradle build files |
| Kotlin Android/Serialization | 1.9.24 | Gradle build files |
| Compose Compiler | 1.5.14 | `app/build.gradle.kts` |
| Compose BOM | 2024.06.00 | `app/build.gradle.kts` |
| Kotlin serialization JSON | 1.6.3 | `app/build.gradle.kts` |
| Android Platform Tools | 37.0.1 | Android SDK |
| `compileSdk` / `targetSdk` | 35 / 35 | `app/build.gradle.kts` |
| `minSdk` | 29 | `app/build.gradle.kts` |

Additional tools used during development include Android Studio, Android SDK
command-line tools, `adb`, browser developer tools, and GitHub.

Tandem Source host: `https://source.eu.tandemdiabetes.com`

Android package: `com.example.tandemapp.st`

Main activity: `com.example.tandemapp.st.MainActivity`

## Versioning

- Display versions use `vMM.mm.rrr`, for example `v02.01.004`.
- `release_versionName` stores `MM.mm`.
- `release_versionCode` stores the revision from `000` to `999`.
- Android `versionCode` is calculated as
  `major * 100000 + minor * 1000 + revision`.
- `buildAPK.bat -noincr` builds without incrementing the revision or creating
  a commit.

## Local Configuration

The following files define the build and should be preserved:

- `gradle/wrapper/gradle-wrapper.properties`
- `settings.gradle.kts`
- root `build.gradle.kts`
- `app/build.gradle.kts`
- `gradle/libs.versions.toml`, when present
- `gitDiff.bat`

`local.properties` is workstation-specific and must not be committed. It must
point to the Android SDK installed on the current machine:

```properties
sdk.dir=<android-sdk-path>
```

## Build

Run commands from the repository root.

Standard debug build:

```powershell
.\gradlew.bat clean assembleDebug
```

Build without a release increment:

```powershell
.\buildAPK.bat -noincr
```

Fully clean build:

```powershell
Remove-Item -Recurse -Force .\.gradle, .\build, .\app\build -ErrorAction SilentlyContinue
.\gradlew.bat clean assembleDebug --no-build-cache --rerun-tasks
```

The project-local Gradle and Android working directories should remain inside
the repository when the local workflow configures them.

## Device Installation

```powershell
adb kill-server
adb start-server
adb devices
adb install -r ".\app\build\outputs\apk\debug\TandemSourceRT-v02.01.004.apk"
```

For a clean reinstall that intentionally removes all app-private caches and
preferences:

```powershell
adb uninstall com.example.tandemapp.st
adb install ".\app\build\outputs\apk\debug\TandemSourceRT-v02.01.004.apk"
```

## Diagnostics

Clear and capture authentication logs:

```powershell
adb logcat -c
adb logcat -d | Select-String "TandemAuth"
```

Raw pump-event and pump-settings JSON exports are written by the installed app
to its Android download directory. These exports may contain personal medical
data and must not be committed or included in delivery archives.

## Current Tandem Source APIs

The runtime uses:

```text
GET /api/pumpers/pumpers/{pumperId}
GET /api/reports/bff/pump-logs/{assignmentId}
GET /api/reports/bff/pumper/{pumperId}
```

`pump-logs` accepts `pumperId`, `startDate`, `endDate`, and `eventIds`, and
returns JSON containing `events` and `clockChanges`. Assignment IDs are
discovered from Tandem Source responses and are never hardcoded.

OAuth/PKCE, downloads, exports, and BFF JSON adaptation are implemented in
Kotlin. Chaquopy, an embedded Python interpreter, and Python HTTP libraries are
not part of the application APK.

## Runtime Data and Packaging

The following files are created only after installation in Android private
storage:

- `latest_chart_data.json`
- `latest_pump_settings.json`
- `latest_sensor_set.json`
- Remember me preferences

They are not application assets and are not packaged in the APK. `LocalAssets`
is ignored by Git. Build and source delivery archives must not contain local
exports, credentials, tokens, cookies, reports, or cached medical data.

Create the source archive from committed files:

```powershell
git archive --format=zip --output=".\Temp\TandemSourceRT-v02.01.004-source.zip" HEAD
```

## New Workstation Setup

1. Install Git, JDK 17, Android Studio or the Android command-line tools, and
   Android Platform Tools.
2. Clone the repository.
3. Configure `local.properties` with the local Android SDK path.
4. Use the included Gradle wrapper.
5. Verify the connected Android device with `adb devices`.
6. Build and run the tests before producing a delivery.
