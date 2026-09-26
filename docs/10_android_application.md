# Android Application

## Technology

- Kotlin
- Jetpack Compose
- MVVM
- Kotlin serialization
- Native OAuth/PKCE and HTTP client

## Features

- Login
- Calendar navigation
- Dataset visualization
- Event timeline
- Local BFF JSON adaptation
- Offline cache for the latest current chart dataset
- Offline cache for the latest pump settings and Sensor Set snapshot
- Sensor Set summary page
- Optional Android Calendar synchronization for Sensor and Infusion Set reminders
- Pump battery, profile, settings, diagnostics, and time-zone display
- Updated/cached/historical status indicators
- Raw JSON diagnostic exports

The current pump-log request covers a 14-day interval whose upper bound cannot
exceed today. Dates that resolve to that same current interval update the
current cache. Older historical intervals are temporary and remain in memory.

## Android Calendar Reminders

The Sensor Set page contains an opt-in calendar switch. Enabling it requests
`READ_CALENDAR` and `WRITE_CALENDAR`; account synchronization uses
`WRITE_SYNC_SETTINGS`. On every successful current-data update, the app
synchronizes two timed events to the primary visible writable calendar:

- estimated Sensor expiration;
- Infusion Set change due time.

Each event has alerts 120 minutes before and at its start time. Existing
correct events are left untouched. Changed times update the same event, and a
missing event is recreated without producing duplicates. Disabling the switch
deletes both events managed by TandemSourceRT.

Sensor expiry remains `Observed since + 10 days`. Only its calendar event time
is normalized outside the inclusive 10:00–20:00 local-time window. The event
title reports the original estimate, for example
`Sensor estimated: 4 Oct 2026, 03:15`. The Infusion Set title reports its
original configured due time, for example
`Infusion set due: 20 Aug 2026, 19:30`.

After a mutation, TandemSourceRT notifies both `CalendarContract.Events` and
`CalendarContract.Instances` with network-sync notification flags and requests
account synchronization for non-local calendars. It does not force-stop,
launch, or depend on a specific calendar application.

## System Navigation Insets

The application uses a Material 3 `Scaffold` and `NavigationBar`. A fixed
`56.dp` height previously constrained the entire bottom navigation container,
including the space Material 3 needs for Android navigation-bar insets. This
could allow a three-button system navigation bar to overlap or compress the
application navigation area on some devices.

Two variants were evaluated:

- Test build `v02.01.006` removed the fixed height and explicitly called
  `enableEdgeToEdge()`. The application bottom navigation rendered correctly
  on the available test phone, but a small status-bar artifact appeared and
  the Android navigation bar changed appearance.
- Release `v02.01.007` keeps the fixed height removed and does not call
  `enableEdgeToEdge()` explicitly. On the available test phone, the artifact
  disappeared, the Android navigation bar returned to its previous appearance,
  and all application navigation items remained visible and usable.

The final implementation therefore relies on the Material 3 `NavigationBar`
default sizing and inset behavior, without explicit edge-to-edge activation or
additional manual `WindowInsets` handling.
