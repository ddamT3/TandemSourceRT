# Device State

The deviceState model is reconstructed from multiple event types.

## User Mode

| Value | Meaning     |
| ----- | ----------- |
| 0     | Normal      |
| 1     | Sleep       |
| 2     | Exercise    |
| 3     | Eating Soon |

## Pump Control State

| Value | Meaning     |
| ----- | ----------- |
| 0     | No Control  |
| 1     | Open Loop   |
| 2     | Pinning     |
| 3     | Closed Loop |

## Sensor Types

| Value | Meaning   |
| ----- | --------- |
| 1     | Dexcom G6 |
| 3     | Dexcom G7 |
| 4     | Libre 2   |
| 5     | Libre 3   |

## Event 313

Event 313 is the primary device-state snapshot. The adapter reads explicit BFF
properties for pump control state, user mode, and sensor type.

## Event 229

Event 229 represents user-mode transitions and related metadata, including
previous and current mode, requested action, exercise choice and duration, and
whether a mode was started or stopped by the pump UI or timer.

## Event 230

Event 230 is only partially mapped.

It contributes additional device state information but is not currently required for user mode timeline reconstruction.

## Reconstruction Strategy

deviceState is reconstructed by merging:

* Event 313
* Event 229
* Event 230

Not all fields are present in every event.
