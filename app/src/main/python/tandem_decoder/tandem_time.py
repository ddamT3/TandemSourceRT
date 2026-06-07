from __future__ import annotations
from datetime import datetime, timedelta, timezone

TANDEM_EPOCH = datetime(2008, 1, 1, tzinfo=timezone.utc)

def tandem_seconds_to_iso(seconds: int) -> str:
	return (TANDEM_EPOCH + timedelta(seconds=int(seconds))).strftime("%Y-%m-%dT%H:%M:%SZ")
