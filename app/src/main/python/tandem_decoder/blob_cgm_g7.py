from __future__ import annotations

import csv
import struct
from pathlib import Path
from tandem_decoder.tandem_time import tandem_seconds_to_iso

EVENT_SIZE = 26
TIMESTAMP_OFFSET = 2
SEQ_NUM_OFFSET = 6
DATA_FIELDS_OFFSET = 10

def read_u16(buf: bytes, off: int) -> int:
	return struct.unpack_from(">H", buf, off)[0]

def read_u32(buf: bytes, off: int) -> int:
	return struct.unpack_from(">I", buf, off)[0]

def read_u8(buf: bytes, off: int) -> int:
	return struct.unpack_from(">B", buf, off)[0]

def read_i8(buf: bytes, off: int) -> int:
	return struct.unpack_from(">b", buf, off)[0]

def parse_header(rec: bytes) -> dict:
	source_and_id = read_u16(rec, 0)
	return {
		"source": (source_and_id & 0xF000) >> 12,
		"id": source_and_id & 0x0FFF,
		"ts": read_u32(rec, TIMESTAMP_OFFSET),
		"seq_num": read_u32(rec, SEQ_NUM_OFFSET),
	}

def parse_399(rec: bytes) -> dict:
	h = parse_header(rec)
	base = DATA_FIELDS_OFFSET
	rate_raw = read_i8(rec, base + 0)
	cgm_data_type = read_u8(rec, base + 1)
	glucose_value_status = read_u16(rec, base + 2)
	value = read_u16(rec, base + 4)
	rssi = read_i8(rec, base + 6)
	algorithm_state = read_u8(rec, base + 7)
	egv_ts = read_u32(rec, base + 8)
	interval = read_u8(rec, base + 13)
	egv_info_bitmask = read_u16(rec, base + 14)

	return {
		"source": h["source"],
		"id": h["id"],
		"time_ts": tandem_seconds_to_iso(h["ts"]),
		"ts": h["ts"],
		"seq_num": h["seq_num"],
		"time_egv_ts": tandem_seconds_to_iso(egv_ts),
		"egv_ts": egv_ts,
		"value": value,
		"glucose_value_status_raw": glucose_value_status,
		"rate_raw": rate_raw,
		"rate_mgdl_min": rate_raw * 0.1,
		"cgm_data_type_raw": cgm_data_type,
		"rssi_dbm": rssi,
		"algorithm_state_raw": algorithm_state,
		"interval_raw": interval,
		"egv_info_bitmask_raw": egv_info_bitmask,
	}

def extract_blob_cgm(blob_path: str | Path) -> list[dict]:
	blob = Path(blob_path).read_bytes()
	rows = []
	for off in range(0, len(blob) - EVENT_SIZE + 1, EVENT_SIZE):
		rec = blob[off:off + EVENT_SIZE]
		if len(rec) != EVENT_SIZE:
			continue
		h = parse_header(rec)
		if h["id"] != 399:
			continue
		row = parse_399(rec)
		row["offset"] = off
		rows.append(row)
	return rows

def save_blob_csv(rows: list[dict], out_path: str | Path) -> None:
	out_path = Path(out_path)
	fieldnames = [
		"offset", "source", "id", "time_ts", "ts", "seq_num",
		"time_egv_ts", "egv_ts", "value",
		"glucose_value_status_raw", "rate_raw", "rate_mgdl_min",
		"cgm_data_type_raw", "rssi_dbm", "algorithm_state_raw",
		"interval_raw", "egv_info_bitmask_raw",
	]
	with out_path.open("w", newline="", encoding="utf-8") as f:
		writer = csv.DictWriter(f, fieldnames=fieldnames, delimiter=";")
		writer.writeheader()
		for row in rows:
			writer.writerow({k: row.get(k) for k in fieldnames})

def load_blob_csv(path: str | Path) -> list[dict]:
	rows = []
	with Path(path).open("r", encoding="utf-8", newline="") as f:
		reader = csv.DictReader(f, delimiter=";")
		for row in reader:
			parsed = dict(row)
			for k in (
				"offset","source","id","ts","seq_num","egv_ts","value",
				"glucose_value_status_raw","rate_raw","cgm_data_type_raw",
				"rssi_dbm","algorithm_state_raw","interval_raw","egv_info_bitmask_raw"
			):
				if parsed.get(k) not in (None, ""):
					parsed[k] = int(float(parsed[k]))
			if parsed.get("rate_mgdl_min") not in (None, ""):
				parsed["rate_mgdl_min"] = float(parsed["rate_mgdl_min"])
			rows.append(parsed)
	return rows

def reconstruct_gt_rows(rows: list[dict]) -> list[dict]:
	groups = {}
	counts = {}
	for row in rows:
		egv_ts = row.get("egv_ts")
		if egv_ts is None:
			continue
		counts[egv_ts] = counts.get(egv_ts, 0) + 1
		prev = groups.get(egv_ts)
		if prev is None or int(row.get("seq_num", -1)) >= int(prev.get("seq_num", -1)):
			groups[egv_ts] = row

	out = []
	for egv_ts in sorted(groups):
		row = groups[egv_ts]
		out.append({
			"time": row.get("time_egv_ts"),
			"value": row.get("value"),
			"egv_ts": row.get("egv_ts"),
			"seq_num": row.get("seq_num"),
			"source_ts": row.get("ts"),
			"source_time_ts": row.get("time_ts"),
			"same_egv_ts_count": counts.get(egv_ts, 1),
			"glucose_value_status_raw": row.get("glucose_value_status_raw"),
			"rate_raw": row.get("rate_raw"),
			"rate_mgdl_min": row.get("rate_mgdl_min"),
			"algorithm_state_raw": row.get("algorithm_state_raw"),
			"interval_raw": row.get("interval_raw"),
			"egv_info_bitmask_raw": row.get("egv_info_bitmask_raw"),
		})
	return out

def save_reconstructed_gt(rows: list[dict], out_path: str | Path) -> None:
	out_path = Path(out_path)
	fieldnames = [
		"time", "value", "egv_ts", "seq_num", "source_ts", "source_time_ts",
		"same_egv_ts_count", "glucose_value_status_raw", "rate_raw",
		"rate_mgdl_min", "algorithm_state_raw", "interval_raw", "egv_info_bitmask_raw",
	]
	with out_path.open("w", newline="", encoding="utf-8") as f:
		writer = csv.DictWriter(f, fieldnames=fieldnames, delimiter=";")
		writer.writeheader()
		for row in rows:
			writer.writerow({k: row.get(k) for k in fieldnames})
