# Reverse Engineering Findings

## Current BFF Findings

- Pump logs are returned as JSON with `events` and `clockChanges`.
- Each event exposes `eventCode`, sequence information, pump time and event properties.
- Ordering uses pump time, sequence group and sequence number.
- Pump settings and profiles are returned by the reports pumper endpoint.
- Native Kotlin adapters map explicit BFF values without synthesizing missing data.
- Event 9 exposes remaining insulin and battery percentage in the same
  pump-status record.
- Event 61 with completion status 3 identifies a completed infusion-set change.
- Event 447 is used as the latest sensor-session observation boundary.

## Time Semantics

Two independent clocks exist:

- Sensor Time
- Pump Time

Current BFF events provide pump and estimated timestamps where supplied by
Tandem Source. Pump time is the canonical application timeline.

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

The only derived values shown by the current application are identified as
estimates or configured reminders: sensor end is observed start plus 10 days,
and set-change reminder time combines the last completed change date with the
pump reminder configuration.
