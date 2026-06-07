# Cleanup notes

This cleaned snapshot keeps:
- live standalone login/download/decode flow
- bundled blob test mode from the login screen
- change-day behavior centering the chart at 12:00

Removed as unused or legacy:
- ApiTandemRepository.kt
- tandem_decoder/blob_decoder.py scaffold
- network_security_config.xml and cleartext manifest flags
- PoC-only Python helper functions test()/inspect_test_blob()
- verbose zoom and dataset debug logs

Reduced:
- top bar logo size from 100.dp to 40.dp
- live/bundled debug logging noise
