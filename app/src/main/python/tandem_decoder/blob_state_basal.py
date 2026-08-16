
from __future__ import annotations

import struct
from pathlib import Path

from tandem_decoder.blob_cgm_g7 import EVENT_SIZE, DATA_FIELDS_OFFSET, parse_header, read_u8
from tandem_decoder.tandem_time import tandem_seconds_to_iso

ID_BASAL_RATE_CHANGE = 3
ID_PUMPING_SUSPENDED = 11
ID_PUMPING_RESUMED = 12
ID_REPLACE_CARTRIDGE_SITE = 33
ID_SENSOR_SESSION_ENDED = 447
ID_DAILY_STATUS = 313
ID_USER_MODE_CHANGE = 229

PUMP_CONTROL_STATE = {
	0: "No Control",
	1: "Open Loop",
	2: "Pinning",
	3: "Closed Loop",
}

USER_MODE = {
	0: "Normal",
	1: "Sleep",
	2: "Exercise",
	3: "EatingSoon",
}

SENSOR_TYPE = {
	1: "Dexcom G6",
	3: "Dexcom G7",
	4: "Libre 2",
	5: "Libre 3",
}

REQUESTED_ACTION = {
	3: "Start Exercise",
	4: "Stop Exercise",
}

EXERCISE_CHOICE = {
	0: "Continuous",
}

def _f32(buf: bytes, off: int) -> float:
	return struct.unpack_from(">f", buf, off)[0]

def _u16le(buf: bytes, off: int) -> int:
	return struct.unpack_from("<H", buf, off)[0]

def extract_blob_basal_device_events(blob_path: str | Path) -> list[dict]:
	blob = Path(blob_path).read_bytes()
	out: list[dict] = []

	for off in range(0, len(blob) - EVENT_SIZE + 1, EVENT_SIZE):
		rec = blob[off:off+EVENT_SIZE]
		if len(rec) != EVENT_SIZE:
			continue
		h = parse_header(rec)
		ev_id = h["id"]
		base = DATA_FIELDS_OFFSET

		row = {
			"ts": h["ts"],
			"seq_num": h["seq_num"],
			"offset": off,
			"time": tandem_seconds_to_iso(h["ts"]),
			"CommandedBasalRate": None,
			"PumpControlState": None,
			"CurrentUserMode": None,
			"PreviousUserMode": None,
			"RequestedAction": None,
			"ExerciseChoice": None,
			"SensorType": None,
			"SleepStartedByGUI": None,
			"ExerciseStoppedByTimer": None,
			"EatingSoonStoppedByTimer": None,
			"ExerciseTimeMin": None,
			"eventType": None,
			"eventLabel": None,
			"eventSubtype": None,
			"sourceEventId": ev_id,
		}
		
		if ev_id == ID_BASAL_RATE_CHANGE:
			row["CommandedBasalRate"] = _f32(rec, base + 0)
			out.append(row)
		elif ev_id == ID_PUMPING_SUSPENDED:
			row["eventType"] = "pump_suspended"
			row["eventLabel"] = "Stop"
			out.append(row)
		elif ev_id == ID_PUMPING_RESUMED:
			row["eventType"] = "pump_resumed"
			row["eventLabel"] = "Restart"
			out.append(row)
		elif ev_id == ID_REPLACE_CARTRIDGE_SITE:
			row["eventType"] = "cartridge_site_change"
			row["eventLabel"] = "Change set"
			out.append(row)
		elif ev_id == ID_SENSOR_SESSION_ENDED:
			row["eventType"] = "sensor_session_ended"
			row["eventLabel"] = "End Sensor"
			out.append(row)
		elif ev_id == ID_DAILY_STATUS:
			row["CurrentUserMode"] = USER_MODE.get(read_u8(rec, base + 0), str(read_u8(rec, base + 0)))
			row["PumpControlState"] = PUMP_CONTROL_STATE.get(read_u8(rec, base + 1), str(read_u8(rec, base + 1)))
			row["SensorType"] = SENSOR_TYPE.get(read_u8(rec, base + 3), str(read_u8(rec, base + 3)))
			out.append(row)
		elif ev_id == ID_USER_MODE_CHANGE:
			row["ExerciseChoice"] = EXERCISE_CHOICE.get(read_u8(rec, base + 0), str(read_u8(rec, base + 0)))
			row["RequestedAction"] = REQUESTED_ACTION.get(read_u8(rec, base + 1), str(read_u8(rec, base + 1)))
			row["PreviousUserMode"] = USER_MODE.get(read_u8(rec, base + 2), str(read_u8(rec, base + 2)))
			row["CurrentUserMode"] = USER_MODE.get(read_u8(rec, base + 3), str(read_u8(rec, base + 3)))
			row["SleepStartedByGUI"] = bool(read_u8(rec, base + 4))
			row["ExerciseStoppedByTimer"] = bool(read_u8(rec, base + 5))
			row["EatingSoonStoppedByTimer"] = bool(read_u8(rec, base + 8))
			row["ExerciseTimeMin"] = _u16le(rec, base + 9)
			out.append(row)

	out.sort(key=lambda x: (x["ts"], x["offset"]))
	return out
