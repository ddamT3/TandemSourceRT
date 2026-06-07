from __future__ import annotations

from pathlib import Path
from typing import Any

from tandem_decoder.blob_bolus import (
	aggregate_bolus,
	extract_blob_bolus_events,
	extract_cho_from_bolus,
	extract_iob_timeline_events,
)
from tandem_decoder.blob_cgm_g7 import extract_blob_cgm, reconstruct_gt_rows
from tandem_decoder.blob_state_basal import extract_blob_basal_device_events


def decode_blob_to_dataset(blob_path: str) -> dict[str, list[dict[str, Any]]]:
	blob = Path(blob_path)

	cgm = reconstruct_gt_rows(extract_blob_cgm(blob))
	bolus = aggregate_bolus(extract_blob_bolus_events(blob))
	cho = extract_cho_from_bolus(bolus)

	state_events = extract_blob_basal_device_events(blob)
	basal = [row for row in state_events if row.get("CommandedBasalRate") is not None]
	device_state = [
		row
		for row in state_events
		if (
			row.get("PumpControlState") is not None
			or row.get("CurrentUserMode") is not None
			or row.get("PreviousUserMode") is not None
			or row.get("RequestedAction") is not None
			or row.get("SensorType") is not None
			or row.get("eventType") is not None
		)
	]
	
	iob = [
		{
			"ts": row["ts"],
			"time": row["time"],
			"iob": row["iob_timeline"],
		}
		for row in extract_iob_timeline_events(blob)
	]

	return {
		"cgm": cgm,
		"bolus": bolus,
		"basal": basal,
		"iob": iob,
		"cho": cho,
		"deviceState": device_state,
	}
