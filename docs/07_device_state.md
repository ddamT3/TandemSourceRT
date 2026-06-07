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

## Event 313 Layout

| Offset | Field            |
| ------ | ---------------- |
| +0     | UserMode         |
| +1     | PumpControlState |
| +3     | SensorType       |

Event 313 is treated as the primary device state snapshot.

## Event 229 Layout

| Offset | Field                        |
| ------ | ---------------------------- |
| +0     | ExerciseChoice               |
| +1     | RequestedAction              |
| +2     | PreviousUserMode             |
| +3     | CurrentUserMode              |
| +4     | SleepStartedByGUI            |
| +5     | ExerciseStoppedByTimer       |
| +8     | EatingSoonStoppedByTimer     |
| +9     | ExerciseTime (Little Endian) |

Event 229 represents user mode transitions and related metadata.

## Event 230

Event 230 is only partially mapped.

It contributes additional device state information but is not currently required for user mode timeline reconstruction.

## Reconstruction Strategy

deviceState is reconstructed by merging:

* Event 313
* Event 229
* Event 230

Not all fields are present in every event.
