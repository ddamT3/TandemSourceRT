from __future__ import annotations

import math
import struct
from pathlib import Path

from tandem_decoder.blob_cgm_g7 import EVENT_SIZE, DATA_FIELDS_OFFSET, parse_header
from tandem_decoder.tandem_time import tandem_seconds_to_iso

# This module extracts blob-derived IOB snapshots from specific bolus-related events.
# It does NOT represent the full continuous IOB timeline used in the CSV.
#
# Kept series here:
# - completion events only:
#     20 = LID_BOLUS_COMPLETED
#     21 = LID_BOLEX_COMPLETED
# - activation snapshots:
#     55 = LID_BOLUS_ACTIVATED
# - request snapshots:
#     64 = LID_BOLUS_REQUEST1
#
# The full continuous IOB timeline is emitted from blob_bolus.extract_iob_timeline_events()
# using ID_IOB_TIMELINE records.

IOB_COMPLETION_EVENT_IDS = {20, 21}
ID_BOLUS_ACTIVATED = 55
ID_BOLUS_REQUEST1 = 64


def _f32(buf: bytes, off: int) -> float:
	return struct.unpack_from(">f", buf, off)[0]


def extract_blob_iob_series(blob_path: str | Path, *, _blob_bytes: bytes | None = None) -> list[dict]:
	"""Return IOB snapshots from bolus completion events only."""
	blob_bytes = _blob_bytes if _blob_bytes is not None else Path(blob_path).read_bytes()
	return _extract_f32_series(
		blob_bytes,
		IOB_COMPLETION_EVENT_IDS,
		relative_offset=4,
		value_key="iob_completion",
	)


def extract_blob_iob_candidates(blob_path: str | Path) -> list[dict]:
	"""Return blob-derived IOB snapshot series for analysis and CSV candidate export."""
	blob_bytes = Path(blob_path).read_bytes()
	completion_series = extract_blob_iob_series(blob_path, _blob_bytes=blob_bytes)
	activation_series = _extract_f32_series(
		blob_bytes, {ID_BOLUS_ACTIVATED}, relative_offset=4, value_key="iob_activation"
	)
	request_series = _extract_f32_series(
		blob_bytes, {ID_BOLUS_REQUEST1}, relative_offset=8, value_key="iob_request"
	)
	return [
		{
			"label": "completion_events_f32",
			"column": "iob_completion",
			"series": completion_series,
		},
		{
			"label": "activation_events_f32",
			"column": "iob_activation",
			"series": activation_series,
		},
		{
			"label": "request_events_f32",
			"column": "iob_request",
			"series": request_series,
		},
	]


def _extract_f32_series(blob: bytes, event_ids: set[int], relative_offset: int, value_key: str) -> list[dict]:
	rows: list[dict] = []

	for off in range(0, len(blob) - EVENT_SIZE + 1, EVENT_SIZE):
		rec = blob[off:off + EVENT_SIZE]
		if len(rec) != EVENT_SIZE:
			continue

		h = parse_header(rec)
		if h["id"] not in event_ids:
			continue

		value = _f32(rec, DATA_FIELDS_OFFSET + relative_offset)
		if not math.isfinite(value):
			continue

		ts = h["ts"]
		rows.append({
			"ts": ts,
			"time": tandem_seconds_to_iso(ts),
			value_key: value,
		})

	rows.sort(key=lambda x: x["ts"])
	return rows