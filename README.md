# TandemSourceRT

Android application for downloading, decoding and visualizing Tandem t:slim X2 pump history from Tandem t:connect.

## Features

- Tandem t:connect authentication
- Cloud data download
- Multi-pump account support
- Automatic history merge across multiple pumps
- Interactive glucose dashboard
- Daily history navigation
- Pump events visualization
- Embedded Python decoder
- Pump event blob export
- Automatic build numbering

## Displayed Data

- CGM
- IOB
- Basal
- Bolus
- Carbohydrates
- Pump Events
- Sensor Events

## Architecture

### Android

- Kotlin
- Jetpack Compose
- Material 3

### Embedded Python

- Chaquopy
- requests
- Custom Tandem decoder

## Data Flow

```text
Login
↓
Tandem Authentication
↓
Pumper Discovery
↓
Pump Metadata Download
↓
tconnectDeviceId Enumeration
↓
Pump Event Blob Download
↓
Blob Decode
↓
Multi-Pump Merge
↓
UI Rendering
```

## Build

From PowerShell:

```powershell
.\buildAPK.bat
```

The build script:

- increments versionCode automatically
- builds the Debug APK
- creates a Git commit automatically

Example:

```text
v01.01.001
v01.01.002
v01.01.003
```

## Requirements

- Android Studio
- JDK 17+
- Python 3.x
- Gradle Wrapper (included)

## Current Status

Stable version with:

- multi-pump support
- automatic dataset merge
- gap-aware chart rendering
- automated build workflow

## Disclaimer

This project is not affiliated with, endorsed by, or supported by Tandem Diabetes Care.

It is intended for personal research and educational purposes only.