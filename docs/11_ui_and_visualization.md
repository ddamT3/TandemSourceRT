# UI and Visualization

Dashboard components:

- CGM chart
- Basal chart
- IOB chart
- Bolus markers
- Device events
- User mode timeline

User modes:
- Normal
- Sleep
- Exercise
- Eating Soon

## Data Origin Status

The page title is left-aligned and a small, regular-weight status is aligned
to the right:

- green `Data Updated`: successful online request for the current window;
- red `Data Cached`: locally cached data is being displayed;
- orange `Historical`: temporary historical request.

Calendar availability dots use the same color as the active chart dataset.
Pump and Sensor Set retain their source timestamp inside the Data source card.

## Sensor Set Calendar Control

The Sensor Set page includes a `Sync with Android calendar` switch. Status
text reports permission requirements, pending synchronization, successful
synchronization, or removal of managed events. Enabling the switch is
persistent but creates or refreshes events only after the next successful
current-data update. Disabling it removes events created by TandemSourceRT.
