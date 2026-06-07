# Authentication and Backend

The application uses the Tandem OAuth flow with PKCE.

## Notes

- Direct username/password authentication is insufficient.
- OAuth context is required.
- Redirect handling is required.
- Session management is performed in the embedded Python layer.

## Design Goal

No custom backend service is required.
All communication happens directly from the device.
