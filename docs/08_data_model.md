# Data Model

## Dataset Structure

The Kotlin BFF adapter produces a normalized dataset composed of:

* cgm
* basal
* bolus
* iob
* cho
* deviceState
* supplementalEvents

## Semantics

* Timestamps represent pump-recorded time.
* Records may arrive out of order.
* Sequence numbers help resolve ordering.
* Null means field not present in the source event.

## CGM

Generated from event 399.

CGM values should be treated as a time series.

## IOB

Generated from event 81.

IOB is not a physical action.

It is a pump-computed value periodically emitted by the system.

## CHO

CHO values are user-entered carbohydrate data associated with bolus activity.

## Device State

deviceState is a composite model.

It is reconstructed by merging:

* Event 313 (snapshot)
* Event 229 (user mode)
* Event 230 (partial state)

## Source of Truth

The Tandem Source `pump-logs` JSON response is the authoritative source
of event values. Pump settings come from the reports pumper JSON response.

No estimation or reconstruction is performed beyond explicit BFF event data.
Legacy binary blobs were the source of truth only through `v01.01.xxx`.
