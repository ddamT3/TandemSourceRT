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
- Latest pump-settings cache
- Raw diagnostic JSON exports

## Data Flow

UI → ViewModel → Kotlin OAuth/API → BFF JSON → Kotlin Adapter → Dataset → UI
