from __future__ import annotations

import csv
import json
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from tandem_decoder.source_dataset import get_cgm_records

TANDEM_EPOCH = datetime(2008, 1, 1, tzinfo=timezone.utc)

def ts_to_iso(sec: int | None) -> str | None:
	if sec is None:
		return None
	return (TANDEM_EPOCH + timedelta(seconds=int(sec))).strftime("%Y-%m-%dT%H:%M:%SZ")

def _normalize_row(r: dict[str, Any]) -> dict[str, Any] | None:
	ts = r.get("ts")
	egv_ts = r.get("egvTs")
	value = r.get("value")
	if value is None:
		return None
	selected_ts = egv_ts if egv_ts is not None else ts
	if selected_ts is None:
		return None
	return {
		"time": ts_to_iso(selected_ts),
		"time_ts": ts_to_iso(ts),
		"ts": int(ts) if ts is not None else None,
		"time_egv_ts": ts_to_iso(egv_ts),
		"egv_ts": int(egv_ts) if egv_ts is not None else None,
		"value": int(value),
		"status": r.get("status"),
		"rate": r.get("rate"),
	}

def _annotate_collisions_and_deltas(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
	ts_counts = Counter(r.get("ts") for r in records if r.get("ts") is not None)
	prev_selected_ts = None
	for rec in records:
		ts = rec.get("ts")
		selected_ts = rec.get("egv_ts") if rec.get("egv_ts") is not None else ts
		rec["ts_collision_count"] = ts_counts.get(ts, 0) if ts is not None else 0
		if prev_selected_ts is None or selected_ts is None:
			rec["delta_seconds_prev"] = None
		else:
			rec["delta_seconds_prev"] = int(selected_ts) - int(prev_selected_ts)
		if selected_ts is not None:
			prev_selected_ts = int(selected_ts)
	return records

def load_cgm_json_as_decoded_glucose(path: str | Path) -> list[dict[str, Any]]:
	data = json.loads(Path(path).read_text(encoding="utf-8"))
	if isinstance(data, dict) and "cgm" in data:
		rows = get_cgm_records(data)
	elif isinstance(data, list):
		rows = data
	else:
		rows = []
	normalized = []
	for r in rows:
		rec = _normalize_row(r)
		if rec is not None:
			normalized.append(rec)
	return _annotate_collisions_and_deltas(normalized)

def write_blob_csv(records: list[dict[str, Any]], csv_path: str | Path) -> None:
	csv_path = Path(csv_path)
	with csv_path.open("w", newline="", encoding="utf-8") as f:
		writer = csv.DictWriter(
			f,
			fieldnames=[
				"time",
				"value",
				"time_ts",
				"ts",
				"time_egv_ts",
				"egv_ts",
				"ts_collision_count",
				"delta_seconds_prev",
				"status",
				"rate",
			],
			delimiter=";",
		)
		writer.writeheader()
		for row in records:
			writer.writerow(
				{
					"time": row.get("time"),
					"value": row.get("value"),
					"time_ts": row.get("time_ts"),
					"ts": row.get("ts"),
					"time_egv_ts": row.get("time_egv_ts"),
					"egv_ts": row.get("egv_ts"),
					"ts_collision_count": row.get("ts_collision_count"),
					"delta_seconds_prev": row.get("delta_seconds_prev"),
					"status": row.get("status"),
					"rate": row.get("rate"),
				}
			)
