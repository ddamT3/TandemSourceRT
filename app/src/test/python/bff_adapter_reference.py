from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timedelta, timezone
from typing import Any


TANDEM_EPOCH = datetime(2008, 1, 1, tzinfo=timezone.utc)


def tandem_seconds_to_iso(seconds: int) -> str:
	return (TANDEM_EPOCH + timedelta(seconds=int(seconds))).strftime("%Y-%m-%dT%H:%M:%SZ")


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
	1: "Start Sleep",
	2: "Stop Sleep",
	3: "Start Exercise",
	4: "Stop Exercise",
}

EXERCISE_CHOICE = {
	0: "Continuous",
}

BOLUS_TYPE = {
	1: "carb",
	2: "automatic_correction",
	3: "quick",
	4: "remote",
}

# These events already have a dedicated Android representation. All other BFF
# events are retained in supplementalEvents for future use, except for the two
# explicitly excluded low-level diagnostic/firmware streams.
SPECIALIZED_EVENT_CODES = {
	3, 11, 12, 20, 21, 33, 55, 64, 65, 66, 81, 229, 230, 279, 313, 399, 447,
}
EXCLUDED_EVENT_CODES = {219, 307}


def _properties(event: dict[str, Any]) -> dict[str, Any]:
	value = event.get("eventProperties")
	return value if isinstance(value, dict) else {}


def _time(event: dict[str, Any]) -> str | None:
	"""Return the pump wall-clock timeline used by Tandem Source."""
	# estimatedDateTime is UTC/corrected time and shifts chart events relative
	# to the wall-clock time displayed by Tandem Source. Keep pumpDateTime as
	# the canonical timeline, matching the wall-clock values shown by Tandem Source.
	value = event.get("pumpDateTime") or event.get("estimatedDateTime")
	if not value:
		return None

	text = str(value)
	if text.endswith("Z") or "+" in text[10:] or text[10:].count("-"):
		return text
	return text + "Z"


def _sort_key(event: dict[str, Any]):
	return (
		_time(event) or "",
		int(event.get("sequenceGroup") or 0),
		int(event.get("sequenceNumber") or 0),
	)


def _float(value: Any, default: float = 0.0) -> float:
	try:
		return float(value)
	except (TypeError, ValueError):
		return default


def _int(value: Any, default: int = 0) -> int:
	try:
		return int(value)
	except (TypeError, ValueError):
		return default


def _extract_cgm(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
	rows = []
	for event in events:
		if event.get("eventCode") != 399:
			continue
		properties = _properties(event)
		egv_timestamp = _int(properties.get("egvTimeStamp"))
		cgm_data_type = properties.get("cgmDataType")
		is_recovered = 1 in cgm_data_type if isinstance(cgm_data_type, list) else cgm_data_type == 1
		# A single BFF response event can contain a group of historical CGM
		# readings. pumpDateTime is their common reception time, while
		# egvTimeStamp is the actual acquisition time of each reading.
		time = tandem_seconds_to_iso(egv_timestamp) if egv_timestamp > 0 else _time(event)
		value = properties.get("currentGlucoseDisplayValue")
		if time is None or value is None:
			continue
		rows.append({
			"time": time,
			"value": _int(value),
			"is_recovered": is_recovered,
		})
	return sorted(rows, key=lambda row: row["time"])


def _extract_iob(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
	rows = []
	for event in events:
		if event.get("eventCode") != 81:
			continue
		properties = _properties(event)
		time = _time(event)
		if time is None or properties.get("iob") is None:
			continue
		rows.append({
			"time": time,
			"iob": _float(properties.get("iob")),
		})
	return rows


def _extract_basal(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
	# Event 279 is the delivered/algorithmic basal series. Its commandedRate is
	# expressed in milliunits/hour, while the Android model expects units/hour.
	delivery = [event for event in events if event.get("eventCode") == 279]
	rows = []
	for event in delivery:
		properties = _properties(event)
		time = _time(event)
		if time is None or properties.get("commandedRate") is None:
			continue
		rows.append({
			"time": time,
			"CommandedBasalRate": _float(properties.get("commandedRate")) / 1000.0,
		})

	if rows:
		# Event 90 is an explicit basal snapshot, not a profile default. Use it
		# only from its own timestamp forward to cover the short interval before
		# the first delivery event, without extrapolating backwards.
		for event in events:
			if event.get("eventCode") != 90:
				continue
			properties = _properties(event)
			time = _time(event)
			if time is None or properties.get("commandedBasalRate") is None:
				continue
			rows.append({
				"time": time,
				"CommandedBasalRate": _float(properties.get("commandedBasalRate")),
			})

		by_time = {row["time"]: row for row in rows}
		return sorted(by_time.values(), key=lambda row: row["time"])

	# Some BFF responses expose only explicit rate-change events.
	for event in events:
		if event.get("eventCode") != 3:
			continue
		properties = _properties(event)
		time = _time(event)
		if time is None or properties.get("commandedBasalRate") is None:
			continue
		rows.append({
			"time": time,
			"CommandedBasalRate": _float(properties.get("commandedBasalRate")),
		})
	return rows


def _extract_bolus(events: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
	grouped: dict[int, dict[str, Any]] = defaultdict(dict)

	for event in events:
		code = event.get("eventCode")
		if code not in {20, 21, 55, 64, 65, 66, 280}:
			continue
		properties = _properties(event)
		bolus_id = properties.get("bolusId")
		if bolus_id is None:
			continue

		group = grouped[_int(bolus_id)]
		group["bolus_id"] = _int(bolus_id)
		if code == 280:
			group.setdefault("events_280", []).append(event)
			group.setdefault("properties_280", []).append(properties)
		else:
			group["event_" + str(code)] = event
			group["properties_" + str(code)] = properties

	bolus_rows = []
	cho_rows = []
	for bolus_id, group in grouped.items():
		request1 = group.get("properties_64", {})
		request2 = group.get("properties_65", {})
		request3 = group.get("properties_66", {})
		completion = group.get("properties_20", {})
		extended_completion = group.get("properties_21", {})
		delivery_updates = group.get("properties_280", [])
		terminal_delivery_updates = [
			update for update in delivery_updates
			if _int(update.get("bolusDeliveryStatus"), -1) == 0
		]
		latest_delivery = terminal_delivery_updates[-1] if terminal_delivery_updates else {}

		start_event = (
			group.get("event_55")
			or group.get("event_64")
			or group.get("event_20")
			or group.get("event_21")
		)
		time = _time(start_event or {})
		if time is None or not (completion or extended_completion):
			continue

		standard_delivered = _float(completion.get("insulinDelivered"))
		extended_delivered = _float(extended_completion.get("insulinDelivered"))
		delivered = standard_delivered + extended_delivered
		if latest_delivery.get("deliveredTotal") is not None:
			delivered = _float(latest_delivery.get("deliveredTotal")) / 1000.0
		duration = _int(request2.get("duration"))
		standard_percent = _int(request2.get("standardPercent"), 100)
		is_extended = bool(extended_completion or duration > 0 or standard_percent < 100)
		if is_extended and not extended_completion:
			immediate = delivered * (standard_percent / 100.0)
			extended_delivered = max(0.0, delivered - immediate)
		else:
			immediate = standard_delivered

		carbs = _float(request1.get("carbAmount"))
		bolus_type_raw = _int(request1.get("bolusType"))
		requested = _float(completion.get("insulinRequested"))
		if delivery_updates:
			requested = (
				_float(delivery_updates[0].get("requestedNow"))
				+ _float(delivery_updates[0].get("requestedLater"))
			) / 1000.0
		completion_status = completion.get("completionStatus")
		is_interrupted = (
			(completion_status is not None and _int(completion_status) != 3)
			or (requested > 0 and delivered + 0.005 < requested)
		)
		bolus_source = _int(delivery_updates[0].get("bolusSource")) if delivery_updates else 0
		origin = "R" if bolus_type_raw == 4 else "A" if bolus_type_raw == 2 or bolus_source == 7 else "M"
		if is_extended and is_interrupted:
			display_code = "REI" if origin == "R" else "AEI" if origin == "A" else "EI"
		elif is_interrupted:
			display_code = "RI" if origin == "R" else "AI" if origin == "A" else "I"
		elif is_extended:
			display_code = "RE" if origin == "R" else "AE" if origin == "A" else "E"
		else:
			display_code = origin
		row = {
			"time": time,
			"insulin_delivered_u": delivered,
			"bolus_type": BOLUS_TYPE.get(bolus_type_raw, str(bolus_type_raw)),
			"bg_mgdl": _float(request1.get("bg")) if request1.get("bg") is not None else None,
			"carbs_g": carbs,
			"is_extended": is_extended,
			"immediate_insulin_u": immediate if is_extended else None,
			"extended_insulin_u": extended_delivered if is_extended else None,
			"extended_duration_min": duration if is_extended else None,
			"duration_min": duration if is_extended else None,
			"display_code": display_code,
			"is_interrupted": is_interrupted,
		}
		bolus_rows.append(row)

		if carbs > 0:
			cho_rows.append({
				"time": time,
				"carbs_g": carbs,
			})

	return bolus_rows, cho_rows


def _device_row(event: dict[str, Any]) -> dict[str, Any] | None:
	code = event.get("eventCode")
	properties = _properties(event)
	time = _time(event)
	if time is None:
		return None

	row = {
		"time": time,
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
		"sourceEventId": _int(code),
	}

	if code == 229:
		row.update({
			"CurrentUserMode": USER_MODE.get(_int(properties.get("currentUserMode")), str(properties.get("currentUserMode"))),
			"PreviousUserMode": USER_MODE.get(_int(properties.get("previousUserMode")), str(properties.get("previousUserMode"))),
			"RequestedAction": REQUESTED_ACTION.get(_int(properties.get("requestedAction")), str(properties.get("requestedAction"))),
			"ExerciseChoice": EXERCISE_CHOICE.get(_int(properties.get("exerciseChoice")), str(properties.get("exerciseChoice"))),
			"SleepStartedByGUI": bool(properties.get("sleepStartedByGui")),
			"ExerciseStoppedByTimer": bool(properties.get("exerciseStoppedByTimer")),
			"EatingSoonStoppedByTimer": bool(properties.get("eatingSoonStoppedByTimer")),
			"ExerciseTimeMin": _int(properties.get("exerciseTime")),
		})
	elif code == 230:
		row["PumpControlState"] = PUMP_CONTROL_STATE.get(
			_int(properties.get("currentPcm")),
			str(properties.get("currentPcm")),
		)
	elif code == 313:
		row.update({
			"PumpControlState": PUMP_CONTROL_STATE.get(_int(properties.get("pumpControlState")), str(properties.get("pumpControlState"))),
			"CurrentUserMode": USER_MODE.get(_int(properties.get("usermode")), str(properties.get("usermode"))),
			"SensorType": SENSOR_TYPE.get(_int(properties.get("sensorType")), str(properties.get("sensorType"))),
		})
	elif code == 11:
		row.update({"eventType": "pump_suspended", "eventLabel": "Stop"})
	elif code == 12:
		row.update({"eventType": "pump_resumed", "eventLabel": "Restart"})
	elif code == 33:
		row.update({"eventType": "cartridge_site_change", "eventLabel": "Change set"})
	elif code == 69 and _int(properties.get("status")) == 3:
		name_bytes = [properties.get(f"name{index}") for index in range(16)]
		profile_name = "".join(
			chr(value) for value in name_bytes
			if isinstance(value, int) and 0 < value < 128
		)
		row.update({
			"eventType": "profile_changed",
			"eventLabel": "Change profile",
			"eventSubtype": profile_name or None,
		})
	elif code == 447:
		row.update({"eventType": "sensor_session_ended", "eventLabel": "End Sensor"})
	else:
		return None

	return row


def _extract_device_state(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
	rows = []
	manual_suspend_active = False
	for event in events:
		code = event.get("eventCode")
		if code == 11:
			if _int(_properties(event).get("suspendReason"), -1) != 0:
				manual_suspend_active = False
				continue
			manual_suspend_active = True
		elif code == 12:
			if not manual_suspend_active:
				continue
			manual_suspend_active = False

		row = _device_row(event)
		if row is not None:
			rows.append(row)
	return rows


def _extract_supplemental_events(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
	rows = []
	for event in events:
		code = event.get("eventCode")
		if not isinstance(code, int):
			continue
		if code in SPECIALIZED_EVENT_CODES or code in EXCLUDED_EVENT_CODES:
			continue

		rows.append({
			"time": _time(event),
			"estimatedDateTime": event.get("estimatedDateTime"),
			"deviceAssignmentId": event.get("deviceAssignmentId"),
			"eventCode": code,
			"sequenceGroup": _int(event.get("sequenceGroup")),
			"sequenceNumber": _int(event.get("sequenceNumber")),
			"eventProperties": _properties(event),
		})
	return rows


def decode_bff_payload(payload: dict[str, Any]) -> dict[str, list[dict[str, Any]]]:
	events = payload.get("events") or []
	if not isinstance(events, list):
		raise RuntimeError("BFF events deve essere una lista")

	events = sorted(
		(event for event in events if isinstance(event, dict)),
		key=_sort_key,
	)
	bolus, cho = _extract_bolus(events)
	device_state = _extract_device_state(events)

	return {
		"cgm": _extract_cgm(events),
		"bolus": bolus,
		"basal": _extract_basal(events),
		"iob": _extract_iob(events),
		"cho": cho,
		"deviceState": device_state,
		"supplementalEvents": _extract_supplemental_events(events),
	}
