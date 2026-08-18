# Android Application

## Technology

- Kotlin
- Jetpack Compose
- MVVM
- Kotlin serialization
- Native OAuth/PKCE and HTTP client

## Features

- Login
- Calendar navigation
- Dataset visualization
- Event timeline
- Local BFF JSON adaptation
- Offline cache for the latest current chart dataset
- Offline cache for the latest pump settings and Sensor Set snapshot
- Sensor Set summary page
- Pump battery, profile, settings, diagnostics, and time-zone display
- Updated/cached/historical status indicators
- Raw JSON diagnostic exports

The current pump-log request covers a 14-day interval whose upper bound cannot
exceed today. Dates that resolve to that same current interval update the
current cache. Older historical intervals are temporary and remain in memory.
