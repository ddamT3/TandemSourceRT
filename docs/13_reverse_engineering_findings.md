# Reverse Engineering Findings

## Current BFF Findings

- Pump logs are returned as JSON with `events` and `clockChanges`.
- Each event exposes `eventCode`, sequence information, pump time and event properties.
- Ordering uses pump time, sequence group and sequence number.
- Pump settings and profiles are returned by the reports pumper endpoint.
- Native Kotlin adapters map explicit BFF values without synthesizing missing data.

## Legacy Binary Findings

The following findings apply to versions through `v01.01.xxx`:

- Tandem event records are 26 bytes.
- The default encoding is Big Endian.
- Event 229 ExerciseTime uses Little Endian encoding.
- Records are not guaranteed to be ordered.
- Ordering should use timestamp and sequence number.
- Device state is reconstructed from events 229, 230 and 313.
- CGM data is generated from event 399.
- IOB data is generated from event 81.

## Time Semantics

Two independent clocks exist:

- Sensor Time
- Pump Time

Legacy event blobs exposed only pump-recorded timestamps. Current BFF
events provide pump and estimated timestamps where supplied by Tandem Source.

Sensor-native timestamps are not currently available.

## Dataset Model

The decoder generates:

- cgm
- basal
- bolus
- iob
- cho
- deviceState

## Adapter Philosophy

- Minimal assumptions
- Event-driven parsing
- No estimation
- No synthetic data generation
