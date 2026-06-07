# Blob Format

## Binary Layout

Record size: 26 bytes

0-1   : Source/Event ID
2-5   : Timestamp
6-9   : Sequence Number
10-25 : Payload

## Endianness

Default:
- Big Endian

Known Exception:
- ExerciseTime field in Event 229 uses Little Endian.

## Ordering

Records are not guaranteed to be ordered.

Recommended ordering:
1. Timestamp
2. Sequence Number

## Time Representation

Timestamp values are Unix epoch seconds.

## Time Semantics

The Tandem ecosystem involves two distinct time domains.

### Sensor Time

CGM sensors (Dexcom G6, Dexcom G7, Libre, etc.) generate glucose measurements using their own internal clock.

The original sensor acquisition timestamp is not available in the event blob.

### Pump Time

The insulin pump records events using the pump clock.

Examples:

- CGM values received by the pump
- Basal delivery events
- Bolus events
- IOB snapshots
- Device state transitions
- User mode changes

All timestamps stored in the event blob are pump timestamps.

### Practical Consequences

A CGM sample shown at time `T` does not necessarily represent the exact moment the sensor physically measured glucose.

Instead:

```text
Sensor acquires glucose value
            ↓
Pump receives the value
            ↓
Pump stores event with pump timestamp
            ↓
Event blob
```

The timestamp available in the blob corresponds to the pump event time.

### Canonical Timeline

TandemSourceRT uses pump time as the canonical timeline for all datasets.

This guarantees consistent alignment between:

- CGM
- Basal
- Bolus
- IOB
- Device State
- User Mode

### Source of Truth

The event blob exposes only pump-recorded timestamps.

Sensor-native timestamps are not currently available through the decoded blob format.
