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
- Android Calendar reminder repository
- Raw diagnostic JSON exports

## Data Flow

UI → ViewModel → Kotlin OAuth/API → BFF JSON → Kotlin Adapter → Dataset → UI

For optional calendar reminders, current Sensor Set data follows a separate
output path:

Sensor Set snapshot → Calendar reminder repository → Android Calendar Provider
→ selected calendar account and calendar notifications

## Cache Policy

- Cache files are created at runtime in Android private app storage.
- Only the latest current request window is persisted.
- A valid cache is replaced only by a newer valid current dataset.
- Historical requests remain in memory and do not modify persistent caches.
- Cached files, credentials, `LocalAssets`, and diagnostic JSON exports are
  not packaged in APK or source-delivery archives.

## Calendar Reminder State

- Calendar synchronization is opt-in and stored in private app preferences.
- The Android event IDs created by TandemSourceRT are retained locally.
- A stable marker in each managed event allows recovery when a stored ID no
  longer exists and prevents duplicate events.
- Event creation, update, and deletion are followed by Calendar Provider
  change notifications. Non-local calendar accounts also receive an explicit
  synchronization request.
- The Calendar Provider and calendar application deliver notifications after
  synchronization; TandemSourceRT does not schedule its own background alarm.
