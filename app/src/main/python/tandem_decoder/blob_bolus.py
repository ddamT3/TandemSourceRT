from __future__ import annotations

import csv
import struct
from collections import defaultdict
from pathlib import Path

from tandem_decoder.tandem_time import tandem_seconds_to_iso
from tandem_decoder.blob_cgm_g7 import EVENT_SIZE, DATA_FIELDS_OFFSET, parse_header, extract_blob_cgm, reconstruct_gt_rows
from tandem_decoder.blob_state_basal import extract_blob_basal_device_events


# Event ids reverse-engineered from the Tandem web app
ID_BOLUS_REQ1 = 64
ID_BOLUS_REQ2 = 65
ID_BOLUS_REQ3 = 66
ID_BOLUS_ACTIVATED = 55
ID_BOLUS_COMPLETED = 20
ID_BOLEX_COMPLETED = 21
ID_IOB_TIMELINE = 81


def _u8(buf: bytes, off: int) -> int:
	return struct.unpack_from(">B", buf, off)[0]


def _u16(buf: bytes, off: int) -> int:
	return struct.unpack_from(">H", buf, off)[0]


def _f32(buf: bytes, off: int) -> float:
	return struct.unpack_from(">f", buf, off)[0]


def _base(rec: bytes) -> int:
	return DATA_FIELDS_OFFSET


def parse_64_req1(rec: bytes) -> dict:
	h = parse_header(rec)
	b = _base(rec)
	return {
		"id": h["id"],
		"ts": h["ts"],
		"seq_num": h["seq_num"],
		"CorrectionBolusIncluded": _u8(rec, b + 0),
		"BolusType": _u8(rec, b + 1),
		"BolusID": _u16(rec, b + 2),
		"BG": _u16(rec, b + 4),
		"CarbAmount": _u16(rec, b + 6),
		"IOB": _f32(rec, b + 8),
		"CarbRatio": _u16(rec, b + 14) / 1000.0,
	}


def parse_65_req2(rec: bytes) -> dict:
	h = parse_header(rec)
	b = _base(rec)
	return {
		"id": h["id"],
		"ts": h["ts"],
		"seq_num": h["seq_num"],
		"StandardPercent": _u8(rec, b + 0),
		"Options": _u8(rec, b + 1),
		"BolusID": _u16(rec, b + 2),
		"Duration": _u16(rec, b + 6),
		"TargetBG": _u16(rec, b + 8),
		"ISF": _u16(rec, b + 10),
		"UserOverride": _u8(rec, b + 12),
		"SelectedIOB": _u8(rec, b + 13),
		"DeclinedCorrection": _u8(rec, b + 14),
	}


def parse_66_req3(rec: bytes) -> dict:
	h = parse_header(rec)
	b = _base(rec)
	return {
		"id": h["id"],
		"ts": h["ts"],
		"seq_num": h["seq_num"],
		"BolusID": _u16(rec, b + 2),
		"FoodBolusSize": _f32(rec, b + 4),
		"CorrectionBolusSize": _f32(rec, b + 8),
		"TotalBolusSize": _f32(rec, b + 12),
	}


def parse_55_activated(rec: bytes) -> dict:
	h = parse_header(rec)
	b = _base(rec)
	return {
		"id": h["id"],
		"ts": h["ts"],
		"seq_num": h["seq_num"],
		"SelectedIOB": _u8(rec, b + 1),
		"BolusID": _u16(rec, b + 2),
		"IOB": _f32(rec, b + 4),
		"BolusSize": _f32(rec, b + 8),
	}


def parse_20_completed(rec: bytes) -> dict:
	h = parse_header(rec)
	b = _base(rec)
	return {
		"id": h["id"],
		"ts": h["ts"],
		"seq_num": h["seq_num"],
		"BolusID": _u16(rec, b + 0),
		"CompletionStatus": _u16(rec, b + 2),
		"IOB": _f32(rec, b + 4),
		"InsulinDelivered": _f32(rec, b + 8),
		"InsulinRequested": _f32(rec, b + 12),
	}


def parse_21_bolex_completed(rec: bytes) -> dict:
	"""Parse extended bolus completion event 21.

	Event 21 reports completion of the extended part of a bolus.
	The payload layout matches event 20:
	- BolusID
	- CompletionStatus
	- IOB
	- InsulinDelivered
	- InsulinRequested
	"""
	row = parse_20_completed(rec)
	row["is_bolex_completion"] = True
	return row



PARSERS = {
	ID_BOLUS_REQ1: parse_64_req1,
	ID_BOLUS_REQ2: parse_65_req2,
	ID_BOLUS_REQ3: parse_66_req3,
	ID_BOLUS_ACTIVATED: parse_55_activated,
	ID_BOLUS_COMPLETED: parse_20_completed,
	ID_BOLEX_COMPLETED: parse_21_bolex_completed,
}

BOLUS_TYPE_LABEL = {
	1: "carb",
	2: "automatic_correction",
	3: "quick",
	4: "remote",
}

COMPLETION_STATUS_LABEL = {
	0: "unknown",
	1: "cancelled",
	2: "aborted",
	3: "completed",
}

ROW_KIND_ORDER = {
	"cgm": 0,
	"bolus": 1,
	"iob_timeline": 2,
	"iob_candidate": 3,
}


def extract_blob_bolus_events(blob_path: str | Path) -> list[dict]:
	blob = Path(blob_path).read_bytes()
	rows: list[dict] = []
	for off in range(0, len(blob) - EVENT_SIZE + 1, EVENT_SIZE):
		rec = blob[off:off + EVENT_SIZE]
		if len(rec) != EVENT_SIZE:
			continue
		h = parse_header(rec)
		parser = PARSERS.get(h["id"])
		if parser is None:
			continue
		row = parser(rec)
		row["offset"] = off
		row["time"] = tandem_seconds_to_iso(row["ts"])
		rows.append(row)
	return rows


def extract_iob_timeline_events(blob_path: str | Path) -> list[dict]:
	blob = Path(blob_path).read_bytes()
	rows: list[dict] = []
	for off in range(0, len(blob) - EVENT_SIZE + 1, EVENT_SIZE):
		rec = blob[off:off + EVENT_SIZE]
		if len(rec) != EVENT_SIZE:
			continue
		h = parse_header(rec)
		if h["id"] != ID_IOB_TIMELINE:
			continue
		value = _f32(rec, DATA_FIELDS_OFFSET + 8)
		rows.append({
			"time": tandem_seconds_to_iso(h["ts"]),
			"ts": h["ts"],
			"row_kind": "iob_timeline",
			"iob_timeline": value,
		})
	return rows


def aggregate_bolus(events: list[dict]) -> list[dict]:
	grouped: dict[int, dict] = defaultdict(dict)
	for e in sorted(events, key=lambda x: (int(x.get("BolusID", -1)), int(x.get("ts", 0)), int(x.get("seq_num", 0)))):
		bid = int(e["BolusID"])
		g = grouped[bid]
		g.setdefault("bolus_id", bid)
		if e["id"] == ID_BOLUS_REQ1:
			g["request_ts"] = e["ts"]
			g["bolus_type_raw"] = e["BolusType"]
			g["bolus_type"] = BOLUS_TYPE_LABEL.get(e["BolusType"], str(e["BolusType"]))
			g["correction_included"] = bool(e["CorrectionBolusIncluded"])
			g["carbs_g"] = e["CarbAmount"]
			g["bg_mgdl"] = e["BG"]
			g["carb_ratio_g_u"] = e["CarbRatio"]
			g["iob_on_request_u"] = e["IOB"]
		elif e["id"] == ID_BOLUS_REQ2:
			g["selected_iob_raw"] = e["SelectedIOB"]
			g["options_raw"] = e["Options"]
			g["standard_percent"] = e["StandardPercent"]
			g["duration_min"] = e["Duration"]
			g["isf_mgdl_u"] = e["ISF"]
			g["target_bg_mgdl"] = e["TargetBG"]
			g["user_override"] = bool(e["UserOverride"])
			g["declined_correction"] = bool(e["DeclinedCorrection"])
		elif e["id"] == ID_BOLUS_REQ3:
			g["food_bolus_u_req"] = e["FoodBolusSize"]
			g["correction_bolus_u_req"] = e["CorrectionBolusSize"]
			g["total_bolus_u_req"] = e["TotalBolusSize"]
		elif e["id"] == ID_BOLUS_ACTIVATED:
			g["activated_ts"] = e["ts"]
			g["iob_on_activation_u"] = e["IOB"]
			g["bolus_size_activated_u"] = e["BolusSize"]
		elif e["id"] == ID_BOLUS_COMPLETED:
			# Standard / immediate completion. Keep separate from event 21.
			g["completed_ts"] = e["ts"]
			g["completion_status_raw"] = e["CompletionStatus"]
			g["completion_status"] = COMPLETION_STATUS_LABEL.get(e["CompletionStatus"], str(e["CompletionStatus"]))
			g["standard_insulin_delivered_u"] = e["InsulinDelivered"]
			g["standard_insulin_requested_u"] = e["InsulinRequested"]
			g["insulin_delivered_u"] = e["InsulinDelivered"]
			g["insulin_requested_u"] = e["InsulinRequested"]
			g["iob_on_completion_u"] = e["IOB"]
		elif e["id"] == ID_BOLEX_COMPLETED:
			# Extended completion. Do not overwrite event 20 fields because
			# event 20 is the immediate/standard part for extended boluses.
			g["is_extended"] = True
			g["extended_completed_ts"] = e["ts"]
			g["extended_completion_status_raw"] = e["CompletionStatus"]
			g["extended_completion_status"] = COMPLETION_STATUS_LABEL.get(e["CompletionStatus"], str(e["CompletionStatus"]))
			g["extended_insulin_delivered_u"] = e["InsulinDelivered"]
			g["extended_insulin_requested_u"] = e["InsulinRequested"]
			g["iob_on_extended_completion_u"] = e["IOB"]

	out: list[dict] = []
	for bid, g in grouped.items():
		if not g.get("request_ts"):
			continue
		if not (g.get("completed_ts") or g.get("extended_completed_ts")):
			continue

		start_ts = g.get("activated_ts") or g.get("request_ts") or g.get("completed_ts") or g.get("extended_completed_ts")
		duration_min = int(g.get("duration_min") or 0)
		standard_percent = int(g.get("standard_percent") or 100)
		has_extended_completion = g.get("extended_insulin_delivered_u") is not None
		is_extended = bool(g.get("is_extended") or has_extended_completion or duration_min > 0 or standard_percent < 100)

		if is_extended and duration_min <= 0 and g.get("completed_ts") and g.get("extended_completed_ts"):
			# Fallback for old parser versions: event 65 duration is sometimes
			# missed if parsed at the wrong offset. Event 21 gives the real end.
			duration_min = max(0, int(round((int(g["extended_completed_ts"]) - int(g["completed_ts"])) / 60.0)))

		standard_delivered = float(g.get("standard_insulin_delivered_u") or g.get("insulin_delivered_u") or 0.0)
		extended_delivered = float(g.get("extended_insulin_delivered_u") or 0.0)

		if has_extended_completion:
			delivered = standard_delivered + extended_delivered
			immediate_delivered = standard_delivered
		else:
			delivered = standard_delivered
			immediate_delivered = delivered * (standard_percent / 100.0) if is_extended else delivered
			extended_delivered = max(0.0, delivered - immediate_delivered) if is_extended else 0.0

		corr_req = float(g.get("correction_bolus_u_req") or 0.0)
		food_req = float(g.get("food_bolus_u_req") or 0.0)
		food_delivered = max(0.0, delivered - min(corr_req, delivered)) if food_req > 0 else 0.0
		correction_delivered = min(corr_req, delivered) if g.get("correction_included") else 0.0
		row = {
			"bolus_id": bid,
			"time": tandem_seconds_to_iso(start_ts) if start_ts else None,
			"ts": start_ts,
			"bolus_type": g.get("bolus_type"),
			"bolus_type_raw": g.get("bolus_type_raw"),
			"carbs_g": g.get("carbs_g"),
			"bg_mgdl": g.get("bg_mgdl"),
			"carb_ratio_g_u": g.get("carb_ratio_g_u"),
			"isf_mgdl_u": g.get("isf_mgdl_u"),
			"target_bg_mgdl": g.get("target_bg_mgdl"),
			"duration_min": duration_min,
			"standard_percent": standard_percent,
			"user_override": g.get("user_override", False),
			"insulin_delivered_u": delivered,
			"insulin_requested_u": g.get("insulin_requested_u"),
			"food_bolus_u": food_delivered,
			"correction_bolus_u": correction_delivered,
			"completion_status": g.get("completion_status") or g.get("extended_completion_status"),
			"completion_status_raw": g.get("completion_status_raw") or g.get("extended_completion_status_raw"),
			"is_extended": is_extended,
			"immediate_insulin_u": immediate_delivered if is_extended else None,
			"extended_insulin_u": extended_delivered if is_extended else None,
			"extended_duration_min": duration_min if is_extended else None,
			"extended_start_time": tandem_seconds_to_iso(start_ts) if is_extended and start_ts else None,
			"extended_completed_time": tandem_seconds_to_iso(g.get("extended_completed_ts")) if g.get("extended_completed_ts") else None,
			"extended_completion_status": g.get("extended_completion_status"),
		}
		out.append(row)
	out.sort(key=lambda x: (x.get("ts") or 0, x["bolus_id"]))
	return out


def extract_cho_from_bolus(bolus_rows: list[dict]) -> list[dict]:
	out = []
	for row in bolus_rows:
		carbs = row.get("carbs_g")
		if carbs is None:
			continue
		try:
			carbs_f = float(carbs)
		except Exception:
			continue
		if carbs_f <= 0:
			continue
		out.append({
			"bolus_id": row.get("bolus_id"),
			"time": row.get("time"),
			"ts": row.get("ts"),
			"carbs_g": carbs_f,
			"bolus_type": row.get("bolus_type"),
			"bg_mgdl": row.get("bg_mgdl"),
		})
	return out




def build_out_csv(
	blob_path: str | Path,
	blob_iob_candidates: list[dict] | None = None,
) -> list[dict]:
	cgm_raw = extract_blob_cgm(blob_path)
	cgm = reconstruct_gt_rows(cgm_raw)
	bolus_events = extract_blob_bolus_events(blob_path)
	bolus = aggregate_bolus(bolus_events)
	cho = {int(row["bolus_id"]): row for row in extract_cho_from_bolus(bolus)}
	state_events = extract_blob_basal_device_events(blob_path)
	iob_timeline_events = extract_iob_timeline_events(blob_path)
	blob_iob_candidates = blob_iob_candidates or []
	def _candidate_column(candidate: dict) -> str | None:
		column = candidate.get("column") or {
			"completion_events_f32": "iob_completion",
			"activation_events_f32": "iob_activation",
			"request_events_f32": "iob_request",
		}.get(candidate.get("label"))
		if column in {"iob_request", "iob_activation", "iob_completion"}:
			return None
		return column

	# Anti-regression:
	# Keep all source events only to update current state over time, but emit rows
	# only for CGM points and bolus events. This preserves the latest known values
	# without creating standalone rows for non-bolus events.
	all_events: list[tuple[int, int, str, dict]] = []

	for row in cgm:
		ts = row.get("egv_ts")
		if ts is None:
			continue
		all_events.append((
			int(ts), 0, "emit_cgm",
			{
				"time": row.get("time"),
				"ts": int(ts),
				"row_kind": "cgm",
				"cgm_source_ts": row.get("source_ts"),
				"CommandedBasalRate": None,
				"cgm": row.get("value"),
				"iob_timeline": None,
				"cho": None,
				"bolo": None,
				"bolus_types": None,
				"food_bolus_u": None,
				"correction_bolus_u": None,
				"is_extended": None,
				"extended_insulin_u": None,
				"extended_duration_sec": None,
				"CurrentUserMode": None,
			}
		))

	for row in bolus:
		ts = row.get("ts")
		if ts is None:
			continue
		cho_row = cho.get(int(row["bolus_id"]))
		is_extended = bool(row.get("is_extended") or (row.get("duration_min") or 0) > 0)
		all_events.append((
			int(ts), 1, "emit_bolus",
			{
				"time": row.get("time"),
				"ts": int(ts),
				"row_kind": "bolus",
				"cgm_source_ts": None,
				"CommandedBasalRate": None,
				"cgm": None,
				"iob_timeline": None,
				"cho": cho_row.get("carbs_g") if cho_row else None,
				"bolo": row.get("insulin_delivered_u"),
				"bolus_types": row.get("bolus_type"),
				"food_bolus_u": row.get("food_bolus_u"),
				"correction_bolus_u": row.get("correction_bolus_u"),
				"is_extended": is_extended,
				"extended_insulin_u": row.get("extended_insulin_u") if is_extended else None,
				"extended_duration_sec": int(row.get("duration_min") * 60) if row.get("duration_min") is not None and is_extended else None,
				"CurrentUserMode": None,
			}
		))

	for candidate in blob_iob_candidates:
		column_name = _candidate_column(candidate)
		if column_name is None:
			continue
		if column_name == "iob_request":
			continue
		series = candidate.get("series") or []
		for point in series:
			ts = point.get("ts")
			value = None
			if isinstance(point, dict):
				value = point.get(column_name)
				if value is None:
					value = point.get("value") or point.get("iob")
			if ts is None or value is None:
				continue
			time_value = point.get("time") or tandem_seconds_to_iso(int(ts))
			all_events.append((int(ts), 3, "iob_candidate", {
				"column": column_name,
				"value": value,
				"time": time_value,
			}))

	for row in state_events:
		ts = row.get("ts")
		if ts is None:
			continue
		if row.get("CommandedBasalRate") is not None:
			all_events.append((int(ts), 2, "basal", {"CommandedBasalRate": row.get("CommandedBasalRate")}))
		if row.get("PumpControlState") is not None:
			all_events.append((int(ts), 2, "pump", {"PumpControlState": row.get("PumpControlState")}))
		if row.get("CurrentUserMode") is not None:
			all_events.append((int(ts), 2, "user", {"CurrentUserMode": row.get("CurrentUserMode")}))

	for row in iob_timeline_events:
		ts = row.get("ts")
		if ts is None:
			continue
		all_events.append((int(ts), 2, "emit_iob_timeline", {
			"time": row.get("time"),
			"ts": int(ts),
			"row_kind": "iob_timeline",
			"iob_timeline": row.get("iob_timeline"),
		}))

	all_events.sort(key=lambda x: (x[0], x[1]))

	current_basal = None
	current_pump = None
	current_user = None

	rows: list[dict] = []
	for ts, order_key, kind, payload in all_events:
		if kind == "basal":
			current_basal = payload.get("CommandedBasalRate")
			continue
		if kind == "pump":
			# PumpControlState intentionally ignored in final CSV layout.
			current_pump = payload.get("PumpControlState")
			continue
		if kind == "user":
			current_user = payload.get("CurrentUserMode")
			continue
		if kind == "iob_candidate":
			column = payload.get("column")
			if not column:
				continue
			target_row = _pick_row_for_ts(rows, ts)
			if target_row is None:
				target_row = {
					"time": payload.get("time"),
					"ts": ts,
					"row_kind": "iob_candidate",
				}
				rows.append(target_row)
			target_row[column] = payload.get("value")
			continue
		if kind == "emit_iob_timeline":
			rows.append(payload)
			continue

		payload["CommandedBasalRate"] = current_basal
		payload["CurrentUserMode"] = current_user
		rows.append(payload)

	rows.sort(key=lambda r: (r.get("ts") or 0, ROW_KIND_ORDER.get(r.get("row_kind"), 99)))
	return rows


def _pick_row_for_ts(rows: list[dict], ts: int) -> dict | None:
	for preferred_kind in ("cgm", "bolus"):
		for row in rows:
			if row.get("ts") == ts and row.get("row_kind") == preferred_kind:
				return row
	for row in rows:
		if row.get("ts") == ts:
			return row
	return None


def _candidate_sort_key(name: str) -> tuple[int, int | str]:
	try:
		suffix = int(name.split("_", 1)[1])
		return (0, suffix)
	except (IndexError, ValueError):
		return (1, name)


def _csv_safe_value(value):
	"""
	Anti-regression note:
	- delimiter must remain ';'
	- floats are serialized explicitly
	- all decimal values are rounded to 3 digits exactly as requested
	- no thousands separators
	- decimal comma is used because delimiter stays ';'
	"""
	if value is None:
		return ""
	if isinstance(value, bool):
		return "TRUE" if value else "FALSE"
	if isinstance(value, float):
		return f"{value:.3f}".replace(".", ",")
	return value


def save_csv(rows: list[dict], out_path: str | Path) -> None:
	base_fieldnames = [
		"time",
		"ts",
		"row_kind",
		"cgm_source_ts",
		"CommandedBasalRate",
		"cgm",
		"iob_timeline",
		"cho",
		"bolo",
		"CurrentUserMode",
		"bolus_types",
		"food_bolus_u",
		"correction_bolus_u",
		"is_extended",
		"extended_insulin_u",
		"extended_duration_sec",
	]
	fieldnames = base_fieldnames[:]
	with Path(out_path).open("w", newline="", encoding="utf-8") as f:
		writer = csv.DictWriter(f, fieldnames=fieldnames, delimiter=";")
		writer.writeheader()
		for row in rows:
			# Anti-regression:
			# serialize each cell explicitly before DictWriter sees it, otherwise some
			# spreadsheet apps may reinterpret selected float columns with locale-dependent
			# heuristics and display impossible integer-like values.
			writer.writerow({k: _csv_safe_value(row.get(k)) for k in fieldnames})
