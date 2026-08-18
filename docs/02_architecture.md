# Architecture

## Components

### Android Layer
- Kotlin
- Jetpack Compose
- MVVM

### Authentication Layer
- Native Kotlin OAuth 2.0 authorization-code flow
- PKCE, cookies, redirects and JWT-expiry handling
- Pumper ID discovery and API validation

### Data Layer
- Native Kotlin HTTP repositories
- Tandem Source BFF JSON adapters
- Pump-event dataset generation
- Latest chart-dataset cache
- Latest pump-settings cache
- Latest Sensor Set cache
- Raw diagnostic JSON exports

## Data Flow

UI → ViewModel → Kotlin OAuth/API → BFF JSON → Kotlin Adapter → Dataset → UI

## Cache Policy

- Cache files are created at runtime in Android private app storage.
- Only the latest current request window is persisted.
- A valid cache is replaced only by a newer valid current dataset.
- Historical requests remain in memory and do not modify persistent caches.
- Cached files, credentials, `LocalAssets`, and diagnostic JSON exports are
  not packaged in APK or source-delivery archives.
