# Kotlin BFF Decoder and Adapter

The current application consumes Tandem Source BFF JSON. `PumpEventsAdapter`
maps explicit event properties into the normalized Kotlin `DayDataset`; no
binary decoder or Python runtime is included in the application.

## Input

The pump-log response contains:

- `events`
- `clockChanges`
- event code
- pump time and optional estimated time
- sequence group and sequence number
- event-specific JSON properties

## Output Dataset

- CGM
- Basal
- Bolus
- IOB
- CHO
- Device State
- Supplemental pump events

## Principles

- Treat BFF JSON as the source of truth.
- Map explicit values without inventing missing data.
- Use pump time as the canonical event and chart timeline.
- Order records by pump time, sequence group, and sequence number.
- Preserve supplemental events needed by Pump and Sensor Set summaries.
- Keep endpoint DTOs separate from the UI data model so future BFF changes
  remain isolated in the adapter layer.

## Validation

Kotlin adapter tests compare normalized output against retained Python
reference fixtures. The Python code is test-only and is not packaged in the
APK.
