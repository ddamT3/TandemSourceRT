# Decoder

## Record Structure

Each record is 26 bytes.

Offset | Size | Description
-------|------|------------
0      | 2    | Source/Event
2      | 4    | Timestamp
6      | 4    | Sequence Number
10     | 16   | Payload

## Output Dataset

- CGM
- Basal
- Bolus
- IOB
- CHO
- Device State

## Principles

- Blob is the authoritative data source.
- Minimal assumptions.
- Event-driven decoding.
