# Authentication and Backend

The application uses the Tandem OAuth flow with PKCE.

## Notes

- Direct username/password authentication is insufficient.
- OAuth context is required.
- Redirect handling is required.
- Session management, cookies, redirects, PKCE and token exchange are implemented in Kotlin.

## Design Goal

No custom backend service is required.
All communication happens directly from the device.

## Tandem Source Endpoint Generations

### Legacy flow through v01.01.xxx

Versions through `v01.01.xxx` used the reports facade:

```text
GET /api/reports/reportsfacade/{pumperId}/pumpeventmetadata
GET /api/reports/reportsfacade/pumpevents/{pumperId}/{tconnectDeviceId}
```

The second endpoint returned a Base64-encoded binary event blob which
was decoded locally.

### Current flow from v01.02.xxx

Starting with `v01.02.xxx`, TandemSourceRT uses the current Tandem
Source endpoints observed in the web application:

```text
GET /api/pumpers/pumpers/{pumperId}
GET /api/reports/bff/pump-logs/{assignmentId}
GET /api/reports/bff/pumper/{pumperId}
```

The pumper response provides `devices[]` and their `assignmentId`
values. `pump-logs` accepts `pumperId`, `startDate`, `endDate` and
`eventIds`, and returns JSON containing `events` and `clockChanges`.

Authentication remains OAuth 2.0 Authorization Code with PKCE. The
endpoint migration changes device discovery and report download, not
the security model.

Starting with `v02.01.xxx`, authentication is native Kotlin. The app
validates candidate pumper IDs against the current pumper API and keeps
short-lived access tokens only in memory. No embedded Python runtime is used.

The reports BFF is operated by Tandem Source; TandemSourceRT does not
introduce or require its own backend server.
