# TandemSourceRT

Native Android application for downloading, adapting and visualizing Tandem t:slim X2 pump data from Tandem Source.

## Features

* Tandem Source OAuth/PKCE authentication
* Cloud data download
* Current-pump selection and historical event requests
* Interactive glucose dashboard
* Daily history navigation
* Pump events visualization
* Native Kotlin BFF JSON adapters
* Raw pump-event and pump-settings JSON export
* Offline cache for the latest pump settings
* Automatic build numbering

## Displayed Data

* CGM
* IOB
* Basal
* Bolus
* Carbohydrates
* Pump Events
* Sensor Events

## Architecture

### Android

* Kotlin
* Jetpack Compose
* Material 3

### Data and authentication

* Native Kotlin OAuth/PKCE client
* Native Kotlin BFF repositories and adapters
* No embedded Python runtime

## Data Flow

```text
Login
↓
Tandem Authentication
↓
Pumper Discovery and Validation
↓
Pump Assignment Selection
↓
Pump Events / Pump Settings JSON
↓
Kotlin Adapters
↓
UI Rendering
```

## Build

### Standard Release Build

From PowerShell:

```powershell
.\buildAPK.bat
```

The build script:

* increments `versionCode`
* generates the APK
* creates a Git commit
* creates a versioned APK filename

Example:

```text
v01.01.009
v01.01.010
v01.01.011
```

### Development Build

To build without changing the version:

```powershell
.\buildAPK.bat -noincr
```

This mode:

* does not modify `app/build.gradle.kts`
* does not increment `versionCode`
* does not create a Git commit
* generates a versioned APK using the current version

Example output:

```text
TandemSourceRT-v01.01.009.apk
```

## Android SDK Setup

The build script automatically creates `local.properties` when possible.

The following locations are checked:

Windows:

```text
%LOCALAPPDATA%\Android\Sdk
```

or:

```text
ANDROID_HOME
```

If automatic detection fails, create `local.properties` manually:

```properties
sdk.dir=C:\Users\YOUR_USERNAME\AppData\Local\Android\Sdk
```

The `local.properties` file is intentionally excluded from Git and must remain local to each machine.

## GitHub Releases

APK files are not stored in the Git repository.

Official APK distribution is performed through GitHub Releases.

Release workflow:

```text
1. Run buildAPK.bat
2. Push main branch
3. Create version tag
4. Push tag
5. Create GitHub Release
6. Upload versioned APK
```

Example:

```text
Tag: v01.01.009
APK: TandemSourceRT-v01.01.009.apk
```

## Requirements

* Android Studio
* JDK 17+
* Gradle Wrapper (included)

## Current Status

Stable version with:

* native Kotlin OAuth and BFF data pipeline
* current-pump selection
* gap-aware chart rendering
* automated build workflow

## Disclaimer

This project is not affiliated with, endorsed by, or supported by Tandem Diabetes Care.

It is intended for personal research and educational purposes only.
