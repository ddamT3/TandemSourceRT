from __future__ import annotations

import struct
from pathlib import Path

from tandem_decoder.blob_cgm_g7 import EVENT_SIZE, DATA_FIELDS_OFFSET, parse_header

ID_BASAL_RATE_CHANGE = 3
ID_AA_USER_MODE_CHANGE = 229
ID_AA_PCM_CHANGE = 230
ID_BASAL_DELIVERY = 279
ID_AA_DAILY_STATUS = 313


def _u8(buf: bytes, off: int) -> int:
	return struct.unpack_from('>B', buf, off)[0]


def _u16(buf: bytes, off: int) -> int:
	return struct.unpack_from('>H', buf, off)[0]


def _u16le(buf: bytes, off: int) -> int:
	return struct.unpack_from('<H', buf, off)[0]


def _f32(buf: bytes, off: int) -> float:
	return struct.unpack_from('>f', buf, off)[0]


PCM_LABEL = {
	0: 'No Control',
	1: 'Open Loop',
	2: 'Pinning',
	3: 'Closed Loop',
}
PCM_DAILY_LABEL = {
	0: 'PCM No Control',
	1: 'PCM Open Loop',
	2: 'PCM Pinning',
	3: 'PCM Closed Loop',
}
USERMODE_LABEL = {
	0: 'Normal',
	1: 'Sleeping',
	2: 'Exercising',
}
SENSOR_LABEL = {
	1: 'CGM_TYPE_DEXCOM_G6',
	3: 'CGM_TYPE_DEXCOM_G7',
	4: 'CGM_TYPE_ABBOTT_FSL2',
	5: 'CGM_TYPE_ABBOTT_FSL3',
}
BOOL_LABEL = {0: 'FALSE', 1: 'TRUE'}
BOOL_LABEL_ALT = {0: 'False', 1: 'TRUE'}
REQUESTED_ACTION_LABEL = {
	3: 'Start Exercise',
	4: 'Stop Exercise',
}
EXERCISE_CHOICE_LABEL = {0: 'Continuous'}
CHANGE_TYPE_LABEL = {
	1: ['"timed segment" - change by timed segment (because either the segment advanced based on time, the user changed the pump time, the user changed the active segment or changed by an AID algorithm.)']
}
RATE_SOURCE_LABEL = {
	0: 'Profile',
	1: 'Temp',
	2: 'User',
	3: 'Algorithm',
}


def _basal_delivery_fields(rec: bytes) -> dict:
	b = DATA_FIELDS_OFFSET
	source = _u8(rec, b + 3)
	commanded = _u16(rec, b + 4)
	profile = _u16(rec, b + 6)
	temp = _u16(rec, b + 8)
	algorithm = _u16(rec, b + 10)
	return {
		'Commanded Rate Source': {
			'rawValue': source,
			'value': RATE_SOURCE_LABEL.get(source, source),
		},
		'Commanded Rate': {'rawValue': commanded, 'value': commanded, 'uom': 'milliunits/hr'},
		'Profile Basal Rate': {'rawValue': profile, 'value': profile, 'uom': 'milliunits/hr'},
		'Algorithm Rate': {'rawValue': algorithm, 'value': algorithm, 'uom': 'milliunits/hr'},
		'Temp Rate': {'rawValue': temp, 'value': temp, 'uom': 'milliunits/hr'},
	}


def _basal_rate_change_fields(rec: bytes) -> dict:
	b = DATA_FIELDS_OFFSET
	commanded = _f32(rec, b + 0)
	base = _f32(rec, b + 4)
	max_rate = _f32(rec, b + 8)
	change_type = _u8(rec, b + 13)
	idp = _u16(rec, b + 14)
	return {
		'CommandedBasalRate': {'rawValue': commanded, 'value': commanded, 'uom': 'units/hour'},
		'BaseBasalRate': {'rawValue': base, 'value': base, 'uom': 'units/hour'},
		'maxBasalRate': {'rawValue': max_rate, 'value': max_rate, 'uom': 'units/hour'},
		'IDP': {'rawValue': idp, 'value': idp},
		'ChangeType': {'rawValue': change_type, 'value': CHANGE_TYPE_LABEL.get(change_type, change_type)},
	}


def _pcm_change_fields(rec: bytes) -> dict:
	b = DATA_FIELDS_OFFSET
	sufficient = _u8(rec, b + 0)
	pump_suspended = _u8(rec, b + 1)
	prev = _u8(rec, b + 2)
	cur = _u8(rec, b + 3)
	calc = _u8(rec, b + 5)
	cgm = _u8(rec, b + 6)
	preferred = _u8(rec, b + 7)
	return {
		'CurrentPCM': {'rawValue': cur, 'value': PCM_LABEL.get(cur, cur)},
		'PreviousPCM': {'rawValue': prev, 'value': PCM_LABEL.get(prev, prev)},
		'PumpSuspended': {'rawValue': pump_suspended, 'value': BOOL_LABEL.get(pump_suspended, pump_suspended)},
		'CalculationAvailable': {'rawValue': calc, 'value': BOOL_LABEL.get(calc, calc)},
		'CgmAvailable': {'rawValue': cgm, 'value': BOOL_LABEL.get(cgm, cgm)},
		'ClosedLoopPreferred': {'rawValue': preferred, 'value': BOOL_LABEL.get(preferred, preferred)},
		'SufficientClosedLoopParams': {'rawValue': sufficient, 'value': BOOL_LABEL.get(sufficient, sufficient)},
	}


def _daily_status_fields(rec: bytes) -> dict:
	b = DATA_FIELDS_OFFSET
	usermode = _u8(rec, b + 0)
	pcs = _u8(rec, b + 1)
	sensor = _u8(rec, b + 3)
	return {
		'PumpControlState': {'rawValue': pcs, 'value': PCM_DAILY_LABEL.get(pcs, pcs)},
		'usermode': {'rawValue': usermode, 'value': USERMODE_LABEL.get(usermode, usermode)},
		'SensorType': {'rawValue': sensor, 'value': SENSOR_LABEL.get(sensor, sensor)},
	}


def _user_mode_change_fields(rec: bytes) -> dict:
	b = DATA_FIELDS_OFFSET
	exercise_choice = _u8(rec, b + 0)
	action = _u8(rec, b + 1)
	previous = _u8(rec, b + 2)
	current = _u8(rec, b + 3)
	sleep_gui = _u8(rec, b + 4)
	exercise_timer = _u8(rec, b + 5)
	eating_timer = _u8(rec, b + 8)
	exercise_time = _u16le(rec, b + 9)
	return {
		'ExerciseChoice': {'rawValue': exercise_choice, 'value': EXERCISE_CHOICE_LABEL.get(exercise_choice, exercise_choice)},
		'ExerciseTime': {'rawValue': exercise_time, 'value': exercise_time, 'uom': 'minutes'},
		'CurrentUserMode': {'rawValue': current, 'value': USERMODE_LABEL.get(current, current)},
		'PreviousUserMode': {'rawValue': previous, 'value': USERMODE_LABEL.get(previous, previous)},
		'RequestedAction': {'rawValue': action, 'value': REQUESTED_ACTION_LABEL.get(action, action)},
		'SleepStartedByGUI': {'rawValue': sleep_gui, 'value': BOOL_LABEL.get(sleep_gui, sleep_gui)},
		'ExerciseStoppedByTimer': {'rawValue': exercise_timer, 'value': BOOL_LABEL_ALT.get(exercise_timer, exercise_timer)},
		'ActiveSleepSchedule': {'rawValue': 0, 'value': []},
		'EatingSoonStoppedByTimer': {'rawValue': eating_timer, 'value': BOOL_LABEL_ALT.get(eating_timer, eating_timer)},
	}


TYPE_BY_ID = {
	ID_BASAL_DELIVERY: 'LID_BASAL_DELIVERY',
	ID_BASAL_RATE_CHANGE: 'LID_BASAL_RATE_CHANGE',
	ID_AA_PCM_CHANGE: 'LID_AA_PCM_CHANGE',
	ID_AA_USER_MODE_CHANGE: 'LID_AA_USER_MODE_CHANGE',
	ID_AA_DAILY_STATUS: 'LID_AA_DAILY_STATUS',
}


PARSER_BY_ID = {
	ID_BASAL_DELIVERY: _basal_delivery_fields,
	ID_BASAL_RATE_CHANGE: _basal_rate_change_fields,
	ID_AA_PCM_CHANGE: _pcm_change_fields,
	ID_AA_USER_MODE_CHANGE: _user_mode_change_fields,
	ID_AA_DAILY_STATUS: _daily_status_fields,
}


def _extract_by_ids(blob_path: str | Path, ids: set[int]) -> list[dict]:
	blob = Path(blob_path).read_bytes()
	out: list[dict] = []
	for off in range(0, len(blob) - EVENT_SIZE + 1, EVENT_SIZE):
		rec = blob[off:off + EVENT_SIZE]
		if len(rec) != EVENT_SIZE:
			continue
		h = parse_header(rec)
		if h['id'] not in ids:
			continue
		parser = PARSER_BY_ID[h['id']]
		out.append({
			'ts': h['ts'],
			'seq_num': h['seq_num'],
			'offset': off,
			'type': TYPE_BY_ID[h['id']],
			'fields': parser(rec),
		})
	out.sort(key=lambda x: (int(x['ts']), int(x['seq_num'])))
	return out


def extract_blob_basal(blob_path: str | Path) -> list[dict]:
	return _extract_by_ids(blob_path, {ID_BASAL_DELIVERY, ID_BASAL_RATE_CHANGE})


def extract_blob_device_state(blob_path: str | Path) -> list[dict]:
	return _extract_by_ids(blob_path, {ID_AA_USER_MODE_CHANGE, ID_AA_PCM_CHANGE, ID_AA_DAILY_STATUS})
