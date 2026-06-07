# Architecture

## Components

### Android Layer
- Kotlin
- Jetpack Compose
- MVVM

### Python Layer
- Embedded through Chaquopy
- OAuth handling
- Blob download
- Blob decoding

### Decoder Layer
- Binary record parser
- Event dispatch
- Dataset generation

## Data Flow

UI → ViewModel → Python → Tandem API → Blob → Decoder → Dataset → UI
