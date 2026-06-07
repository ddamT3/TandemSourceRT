# Reverse Engineering Findings

## Confirmed Findings

- Tandem event records are 26 bytes.
- The default encoding is Big Endian.
- Event 229 ExerciseTime uses Little Endian encoding.
- Records are not guaranteed to be ordered.
- Ordering should use timestamp and sequence number.
- The event blob is the authoritative source of data.
- Device state is reconstructed from events 229, 230 and 313.
- CGM data is generated from event 399.
- IOB data is generated from event 81.

## Time Semantics

Two independent clocks exist:

- Sensor Time
- Pump Time

The event blob exposes only pump-recorded timestamps.

Sensor-native timestamps are not currently available.

## Dataset Model

The decoder generates:

- cgm
- basal
- bolus
- iob
- cho
- deviceState

## Decoder Philosophy

- Minimal assumptions
- Event-driven parsing
- No estimation
- No synthetic data generation