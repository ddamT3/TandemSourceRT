# Authentication and Backend

TandemSourceRT authenticates directly from the Android device with OAuth 2.0
Authorization Code and PKCE. No custom backend service is required.

## Authentication Flow

- Username and password are submitted only as part of the Tandem sign-in flow.
- Redirects, cookies, PKCE challenge generation, authorization-code exchange,
  and token validation are implemented in Kotlin.
- Candidate pumper IDs are validated against the current Tandem Source API.
- Short-lived access tokens remain in memory.
- No embedded Python runtime is used.

## Current Tandem Source Endpoints

```text
GET /api/pumpers/pumpers/{pumperId}
GET /api/reports/bff/pump-logs/{assignmentId}
GET /api/reports/bff/pumper/{pumperId}
```


The pumper responses provide device assignment IDs and pump settings.
`pump-logs` accepts `pumperId`, `startDate`, `endDate`, and `eventIds`, and
returns JSON containing `events` and `clockChanges`.

The reports BFF is operated by Tandem Source. TandemSourceRT does not
introduce or require an intermediary server.

## Login Status

The Login page reports the runtime result explicitly:

- `Authentication succeeded, data available`
- `No connection. Cached data is available.`
- `Tandem authentication failed.`
- `Authentication succeeded, data loading failed.`

When Remember me is enabled, credentials are written only after installation
to Android private preferences. Credential values are never compiled into the
APK or included in source archives.
