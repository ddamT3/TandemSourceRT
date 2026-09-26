# Known Limitations

- Tandem APIs are unofficial.
- Reverse-engineered mappings may evolve.
- Decoder coverage is not complete.
- OAuth behavior may change in future releases.
- Sensor start is an observed boundary, not the sensor-native activation time.
- Estimated sensor end assumes a 10-day session from the first observed CGM.
- Sensor serial number, lot, firmware, and pairing details are not exposed by
  the currently used Tandem Source reports.
- Calendar reminders require a visible writable Android calendar and calendar
  permissions granted by the user.
- Calendar event persistence is handled by the Android Calendar Provider;
  final display and notification timing also depend on the selected calendar
  account and installed calendar client.
- The `v02.01.007` bottom navigation was verified on the currently available
  test phone, but validation on the device that originally showed three-button
  navigation overlap is still pending. That future test must disable navigation
  bar auto-hide, select three-button navigation, verify that Dashboard,
  Calendar, Pump, Sensor Set, and Login are fully visible and clickable, and
  confirm that the Android navigation bar does not overlap the application bar.
