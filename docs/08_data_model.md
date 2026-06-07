# Data Model

## Dataset Structure

The decoder produces a normalized dataset composed of:

* cgm
* basal
* bolus
* iob
* cho
* deviceState

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

The Tandem event blob is the authoritative source of all decoded values.

No estimation or reconstruction is performed beyond the decoded event data.
