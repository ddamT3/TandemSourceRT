import base64
import hashlib
import json
import os
import re
import secrets
import tempfile
from datetime import datetime, timedelta
from urllib.parse import parse_qs, urlparse

import requests

from decoder_adapter import decode_blob_to_dataset


SOURCE_BASE_URL = "https://source.eu.tandemdiabetes.com"
ACCOUNTS_BASE_URL = "https://tdcservices.eu.tandemdiabetes.com/accounts/api"
CLIENT_ID = "1519e414-eeec-492e-8c5e-97bea4815a10"
REDIRECT_URI = "https://source.eu.tandemdiabetes.com/authorize/callback"
USER_AGENT = "Mozilla/5.0"
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


def _base64url_no_padding(data: bytes) -> str:
	return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _base64url_decode(data: str) -> bytes:
	padding = "=" * (-len(data) % 4)
	return base64.urlsafe_b64decode(data + padding)


def _decode_jwt_payload(token: str):
	"""Decode JWT payload without verifying signature.

	Used only for discovery/debug of Tandem user identifiers already returned
	by the authenticated token exchange. This is not used as a security check.
	"""
	try:
		parts = token.split(".")
		if len(parts) < 2:
			return {}
		return json.loads(_base64url_decode(parts[1]).decode("utf-8"))
	except Exception:
		return {}


def _iter_nested_values(value):
	if isinstance(value, dict):
		for v in value.values():
			yield from _iter_nested_values(v)
	elif isinstance(value, list):
		for v in value:
			yield from _iter_nested_values(v)
	else:
		yield value


def _unique_keep_order(values):
	seen = set()
	out = []
	for value in values:
		if value in seen:
			continue
		seen.add(value)
		out.append(value)
	return out


def _auth_headers(access_token: str):
	return {
		"Accept": "application/json",
		"User-Agent": USER_AGENT,
		"Authorization": f"Bearer {access_token}",
	}


def build_pkce_values():
	code_verifier = _base64url_no_padding(secrets.token_bytes(64))
	code_challenge = _base64url_no_padding(
		hashlib.sha256(code_verifier.encode("ascii")).digest()
	)
	state = _base64url_no_padding(secrets.token_bytes(24))
	nonce = _base64url_no_padding(secrets.token_bytes(24))

	return {
		"code_verifier": code_verifier,
		"code_challenge": code_challenge,
		"state": state,
		"nonce": nonce,
	}


def start_authorize_flow(session: requests.Session):
	pkce = build_pkce_values()

	params = {
		"client_id": CLIENT_ID,
		"redirect_uri": REDIRECT_URI,
		"response_mode": "query",
		"response_type": "code",
		"scope": "openid email profile tandem.devices.assign",
		"code_challenge": pkce["code_challenge"],
		"code_challenge_method": "S256",
		"nonce": pkce["nonce"],
		"state": pkce["state"],
	}

	url = f"{ACCOUNTS_BASE_URL}/connect/authorize"
	response = session.get(url, params=params, allow_redirects=False, timeout=30)

	return {
		"pkce": pkce,
		"status_code": response.status_code,
		"headers": dict(response.headers),
		"location": response.headers.get("Location"),
	}


def login_with_context(session: requests.Session, username: str, password: str):
	login_url = f"{ACCOUNTS_BASE_URL}/login"

	payload = {
		"username": username,
		"password": password
	}

	headers = {
		"Content-Type": "application/json",
		"Accept": "application/json",
		"Origin": SOURCE_BASE_URL,
		"Referer": f"{SOURCE_BASE_URL}/",
		"User-Agent": USER_AGENT,
	}

	return session.post(
		login_url,
		json=payload,
		headers=headers,
		allow_redirects=True,
		timeout=30,
	)


def continue_authorize_flow(session: requests.Session, authorize_location: str):
	"""Continue the original OAuth/PKCE flow after a successful login.

	The first /connect/authorize call redirects to the Tandem SSO React portal,
	with the real OAuth continuation URL encoded in the ReturnUrl query
	parameter. After login succeeds, requests must call that ReturnUrl directly;
	loading the SSO portal URL only returns the JavaScript application page and
	does not produce the OAuth authorization code.
	"""
	if not authorize_location:
		raise RuntimeError("Authorize Location mancante dopo start_authorize_flow")

	parsed = urlparse(authorize_location)
	query = parse_qs(parsed.query)
	return_url = query.get("ReturnUrl", [None])[0]

	if not return_url:
		raise RuntimeError(f"ReturnUrl mancante in authorize Location: {authorize_location}")

	response = session.get(
		return_url,
		allow_redirects=True,
		timeout=30,
	)

	return {
		"status_code": response.status_code,
		"headers": dict(response.headers),
		"location": response.headers.get("Location"),
		"url": response.url,
		"body": response.text[:2000] if hasattr(response, "text") else "",
		"return_url": return_url,
	}


def extract_code_from_location(location: str):
	parsed = urlparse(location)
	params = parse_qs(parsed.query)

	code = params.get("code", [None])[0]
	state = params.get("state", [None])[0]

	return {
		"code": code,
		"state": state,
	}


def exchange_code_for_token(code: str, code_verifier: str):
	url = f"{ACCOUNTS_BASE_URL}/connect/token"

	data = {
		"client_id": CLIENT_ID,
		"grant_type": "authorization_code",
		"code": code,
		"code_verifier": code_verifier,
		"redirect_uri": REDIRECT_URI,
	}

	headers = {
		"Content-Type": "application/x-www-form-urlencoded",
		"Accept": "application/json",
		"Origin": SOURCE_BASE_URL,
		"Referer": f"{SOURCE_BASE_URL}/",
		"User-Agent": USER_AGENT,
	}

	response = requests.post(url, data=data, headers=headers, timeout=30)
	if response.status_code != 200:
		raise RuntimeError(f"Token exchange fallito: {response.status_code} {response.text[:500]}")

	return response.json()



def _date_window_from_selected(selected_date: str = None, days_before: int = 5, days_after: int = 5):
	"""
	Return the pumpevents download window around the requested date.

	Rules:
	- historical date: selected_date - 5 days -> selected_date + 5 days
	- today/no selected date: today - 5 days -> today

	The upper bound is never allowed to go beyond today, because Tandem Source
	does not need future dates and future windows can hide edge cases in live mode.
	"""
	today = datetime.utcnow().date()
	if selected_date:
		center = datetime.strptime(selected_date, "%Y-%m-%d").date()
	else:
		center = today

	start = center - timedelta(days=days_before)
	end = center if center >= today else center + timedelta(days=days_after)
	if end > today:
		end = today

	return start, end


def _extract_candidate_pumper_ids_from_token_result(token_result: dict):
	"""Return UUID-looking values from token claims, with likely claim names first."""
	candidates = []

	for token_key in ("id_token", "access_token"):
		token = token_result.get(token_key)
		if not token:
			continue

		payload = _decode_jwt_payload(token)

		# Prefer obvious Tandem/pumper claims if present.
		for key in (
			"pumperId",
			"pumper_id",
			"pumper",
			"patientId",
			"patient_id",
			"accountId",
			"account_id",
			"sub",
		):
			value = payload.get(key)
			if isinstance(value, str) and UUID_RE.match(value):
				candidates.append(value)

		# Fallback: collect all UUID-looking strings in the JWT payload.
		for value in _iter_nested_values(payload):
			if isinstance(value, str) and UUID_RE.match(value):
				candidates.append(value)

	return _unique_keep_order(candidates)


def get_pumper(session: requests.Session, access_token: str, pumper_id: str):
	url = f"{SOURCE_BASE_URL}/api/pumpers/{pumper_id}"
	response = session.get(url, headers=_auth_headers(access_token), timeout=60)
	if response.status_code != 200:
		raise RuntimeError(f"Errore pumper {pumper_id}: {response.status_code} {response.text[:500]}")
	return response.json()

def discover_pumper_id(session: requests.Session, access_token: str, token_result: dict):
	candidates = _extract_candidate_pumper_ids_from_token_result(token_result)
	errors = []

	for candidate in candidates:
		try:
			metadata = get_pump_event_metadata(session, access_token, candidate)
			sources = _normalize_metadata_items(metadata)

			if sources:
				print(f"[PYTHON] discovered pumperId={candidate}")
				print(
					"[PYTHON] validated pumpeventmetadata sources: "
					+ ", ".join(f"{s['serialNumber']}->{s['tconnectDeviceId']}" for s in sources)
				)
				return candidate

		except Exception as exc:
			errors.append(f"{candidate}: {exc}")

	raise RuntimeError(
		"pumperId non trovato dinamicamente tramite pumpeventmetadata. "
		f"candidate={candidates} errors={errors[:5]}"
	)

def get_pump_event_metadata(session: requests.Session, access_token: str, pumper_id: str):
	"""Return Tandem report metadata, including tconnectDeviceId per pump."""
	url = f"{SOURCE_BASE_URL}/api/reports/reportsfacade/{pumper_id}/pumpeventmetadata"
	response = session.get(url, headers=_auth_headers(access_token), timeout=60)
	if response.status_code != 200:
		raise RuntimeError(f"Errore pumpeventmetadata: {response.status_code} {response.text[:500]}")

	data = response.json()
	if isinstance(data, dict):
		for key in ("pumpEventMetadata", "metadata", "devices", "items", "data"):
			if isinstance(data.get(key), list):
				return data.get(key)
	if isinstance(data, list):
		return data

	raise RuntimeError(f"Formato pumpeventmetadata inatteso: {type(data).__name__}")


def _normalize_metadata_items(metadata):
	items = []
	for item in metadata or []:
		if not isinstance(item, dict):
			continue

		tconnect_device_id = item.get("tconnectDeviceId")
		if tconnect_device_id is None:
			tconnect_device_id = item.get("tConnectDeviceId")
		if tconnect_device_id is None:
			tconnect_device_id = item.get("deviceId")
		if tconnect_device_id is None:
			tconnect_device_id = item.get("id")

		if tconnect_device_id is None:
			continue

		items.append({
			"tconnectDeviceId": str(tconnect_device_id),
			"serialNumber": str(item.get("serialNumber", "unknown")),
			"modelNumber": item.get("modelNumber"),
			"minDateWithEvents": item.get("minDateWithEvents"),
			"maxDateWithEvents": item.get("maxDateWithEvents"),
			"lastUpload": item.get("lastUpload"),
			"raw": item,
		})

	return items


def get_pump_event_sources(session: requests.Session, access_token: str, pumper_id: str):
	metadata = get_pump_event_metadata(session, access_token, pumper_id)
	sources = _normalize_metadata_items(metadata)
	if not sources:
		raise RuntimeError("Nessun tconnectDeviceId trovato in pumpeventmetadata")

	print(
		"[PYTHON] pump event sources: "
		+ ", ".join(
			f"{s['serialNumber']}->{s['tconnectDeviceId']} "
			f"min={s.get('minDateWithEvents')} max={s.get('maxDateWithEvents')} "
			f"lastUpload={s.get('lastUpload')}"
			for s in sources
		)
	)
	return sources


def download_pump_events_blob(session: requests.Session, access_token: str, pumper_id: str, tconnect_device_id: str, selected_date: str = None):
	url = f"{SOURCE_BASE_URL}/api/reports/reportsfacade/pumpevents/{pumper_id}/{tconnect_device_id}"

	min_date, max_date = _date_window_from_selected(selected_date)
	print(
		f"[PYTHON] download_pump_events_blob pumperId={pumper_id} "
		f"tconnectDeviceId={tconnect_device_id} selected_date={selected_date} "
		f"min_date={min_date} max_date={max_date}"
	)

	params = {
		"minDate": min_date.isoformat(),
		"maxDate": max_date.isoformat(),
		"eventIds": "3,11,12,20,21,33,55,64,65,66,81,229,313,399,447",
	}

	response = session.get(url, params=params, headers=_auth_headers(access_token), timeout=60)

	body_len = len(response.text or "")
	print(
		f"[PYTHON] pumpevents response "
		f"device={tconnect_device_id} status={response.status_code} body_chars={body_len}"
	)

	if response.status_code != 200:
		raise RuntimeError(f"Errore pumpevents: {response.status_code} {response.text[:500]}")

	return response.text


def download_full_pump_events_blob(session: requests.Session, access_token: str, pumper_id: str, tconnect_device_id: str, selected_date: str = None):
	"""Download broad pumpevents blob for reverse-engineering/debug exports."""
	url = f"{SOURCE_BASE_URL}/api/reports/reportsfacade/pumpevents/{pumper_id}/{tconnect_device_id}"

	min_date, max_date = _date_window_from_selected(selected_date)
	print(
		f"[PYTHON] download_full_pump_events_blob pumperId={pumper_id} "
		f"tconnectDeviceId={tconnect_device_id} selected_date={selected_date} "
		f"min_date={min_date} max_date={max_date}"
	)
	event_ids = ",".join(str(i) for i in range(1, 601))

	params = {
		"minDate": min_date.isoformat(),
		"maxDate": max_date.isoformat(),
		"eventIds": event_ids,
	}

	response = session.get(url, params=params, headers=_auth_headers(access_token), timeout=120)
	if response.status_code != 200:
		raise RuntimeError(f"Errore full pumpevents: {response.status_code} {response.text[:500]}")

	return response.text


def download_pump_settings_blob(session: requests.Session, access_token: str):
	"""Placeholder: insert real Tandem Source pump settings endpoint here."""
	raise NotImplementedError("Endpoint pump settings non ancora configurato")



def _get_access_token_for_user(username: str, password: str):
	session = requests.Session()
	
	start = start_authorize_flow(session)
	login = login_with_context(session, username, password)
	after = continue_authorize_flow(session, start.get("location"))

	# With allow_redirects=True, requests stores the final redirect target in
	# response.url. Some Tandem SSO flows do not leave a Location header on the
	# final response, so check the final URL first and fall back to Location.
	location = after.get("url") or after.get("location")
	if location and "code=" not in location:
		location = after.get("location") or location
	code_info = extract_code_from_location(location) if location else {"code": None, "state": None}

	if not code_info["code"]:
		print("[DEBUG][AUTH] start status:", start.get("status_code"))
		print("[DEBUG][AUTH] start location:", start.get("location"))
		print("[DEBUG][AUTH] login status:", login.status_code)
		print("[DEBUG][AUTH] login url:", login.url)
		print("[DEBUG][AUTH] login location:", login.headers.get("Location"))
		print("[DEBUG][AUTH] login body head:", login.text[:2000] if hasattr(login, "text") else None)
		print("[DEBUG][AUTH] after status:", after.get("status_code"))
		print("[DEBUG][AUTH] after return_url:", after.get("return_url"))
		print("[DEBUG][AUTH] after url:", after.get("url"))
		print("[DEBUG][AUTH] after location:", after.get("location"))
		print("[DEBUG][AUTH] after body head:", after.get("body"))
		print("[DEBUG][AUTH] cookies:", session.cookies.get_dict())
		raise RuntimeError(
			"Authorization code non trovato "
			f"(start={start.get('status_code')}, login={login.status_code}, after={after.get('status_code')})"
		)

	if code_info.get("state") and code_info["state"] != start["pkce"]["state"]:
		raise RuntimeError("OAuth state mismatch")

	token_result = exchange_code_for_token(
		code=code_info["code"],
		code_verifier=start["pkce"]["code_verifier"]
	)

	access_token = token_result.get("access_token")
	if not access_token:
		raise RuntimeError("Access token non trovato")

	return session, access_token, token_result


def _get_authenticated_context(username: str, password: str):
	session, access_token, token_result = _get_access_token_for_user(username, password)
	pumper_id = discover_pumper_id(session, access_token, token_result)
	return session, access_token, pumper_id


def _safe_export_dir(export_dir: str):
	if not export_dir:
		raise RuntimeError("export_dir mancante")
	os.makedirs(export_dir, exist_ok=True)
	return export_dir


def _export_timestamp():
	return datetime.utcnow().strftime("%Y%m%d_%H%M%S")


def _safe_filename_part(value):
	value = str(value or "unknown")
	return re.sub(r"[^A-Za-z0-9._-]+", "_", value)


def _base64_blob_to_bytes(base64_blob: str):
	clean_blob = base64_blob.strip().strip('"')
	return base64.b64decode(clean_blob)


def _sort_key(row):
	if not isinstance(row, dict):
		return ""
	return str(row.get("time") or row.get("ts") or row.get("timestamp") or "")


def _merge_datasets(datasets: list[dict]):
	merged = {}
	for dataset in datasets:
		for key, value in (dataset or {}).items():
			if isinstance(value, list):
				merged.setdefault(key, []).extend(value)
			else:
				# Preserve non-list fields only if no previous value exists.
				merged.setdefault(key, value)

	for key, value in list(merged.items()):
		if not isinstance(value, list):
			continue

		deduped = []
		seen = set()
		for row in value:
			try:
				marker = json.dumps(row, sort_keys=True, ensure_ascii=False)
			except Exception:
				marker = repr(row)
			if marker in seen:
				continue
			seen.add(marker)
			deduped.append(row)

		merged[key] = sorted(deduped, key=_sort_key)

	return merged


def export_full_pump_events_bin(username: str, password: str, export_dir: str, selected_date: str = None):
	"""Export raw pumpevents blobs as .bin, one file per Tandem source/pump.

	The Android layer passes an app-specific external downloads folder.
	"""
	try:
		session, access_token, pumper_id = _get_authenticated_context(username, password)
		sources = get_pump_event_sources(session, access_token, pumper_id)

		out_dir = _safe_export_dir(export_dir)
		exported = []
		timestamp = _export_timestamp()

		for source in sources:
			tconnect_device_id = source["tconnectDeviceId"]
			serial_number = source["serialNumber"]

			base64_blob = download_full_pump_events_blob(
				session,
				access_token,
				pumper_id,
				tconnect_device_id,
				selected_date
			)

			blob_bytes = _base64_blob_to_bytes(base64_blob)

			filename = (
				f"full_pumpevents_{selected_date or 'latest'}_"
				f"{_safe_filename_part(serial_number)}_"
				f"{_safe_filename_part(tconnect_device_id)}_"
				f"{timestamp}.bin"
			)
			path = os.path.join(out_dir, filename)

			with open(path, "wb") as f:
				f.write(blob_bytes)

			exported.append({
				"serialNumber": serial_number,
				"tconnectDeviceId": tconnect_device_id,
				"path": path,
				"bytes": len(blob_bytes),
				"minDateWithEvents": source.get("minDateWithEvents"),
				"maxDateWithEvents": source.get("maxDateWithEvents"),
				"lastUpload": source.get("lastUpload"),
			})

		return json.dumps({
			"status": "ok",
			"type": "multi_bin",
			"pumperId": pumper_id,
			"count": len(exported),
			"files": exported,
		})
	except Exception as exc:
		return json.dumps({
			"status": "error",
			"type": "bin",
			"detail": {
				"message": str(exc),
				"exception": exc.__class__.__name__,
			}
		})



def export_pump_events_bin(username: str, password: str, export_dir: str, selected_date: str = None):
	return export_full_pump_events_bin(username, password, export_dir, selected_date)


def export_dataset_json(username: str, password: str, export_dir: str):
	"""Export decoded multi-pump dataset as .json."""
	try:
		session, access_token, pumper_id = _get_authenticated_context(username, password)
		sources = get_pump_event_sources(session, access_token, pumper_id)

		datasets = []
		for source in sources:
			base64_blob = download_pump_events_blob(
				session,
				access_token,
				pumper_id,
				source["tconnectDeviceId"],
				None
			)
			datasets.append(decode_pump_events_blob(base64_blob))

		dataset = _merge_datasets(datasets)

		out_dir = _safe_export_dir(export_dir)
		filename = f"dataset_{_export_timestamp()}.json"
		path = os.path.join(out_dir, filename)

		payload = {
			"status": "ok",
			"pumperId": pumper_id,
			"sources": [
				{
					"serialNumber": s["serialNumber"],
					"tconnectDeviceId": s["tconnectDeviceId"],
					"minDateWithEvents": s.get("minDateWithEvents"),
					"maxDateWithEvents": s.get("maxDateWithEvents"),
					"lastUpload": s.get("lastUpload"),
				}
				for s in sources
			],
			"data": dataset,
			"counts": {k: len(v) for k, v in dataset.items() if isinstance(v, list)},
		}

		with open(path, "w", encoding="utf-8") as f:
			json.dump(payload, f, ensure_ascii=False, indent=2)

		return json.dumps({
			"status": "ok",
			"type": "json",
			"path": path,
			"counts": payload["counts"],
			"sources": payload["sources"],
		})
	except Exception as exc:
		return json.dumps({
			"status": "error",
			"type": "json",
			"detail": {
				"message": str(exc),
				"exception": exc.__class__.__name__,
			}
		})


def export_pump_settings_bin(username: str, password: str, export_dir: str):
	"""Export raw pump settings blob as .bin once endpoint is configured."""
	try:
		session, access_token, _pumper_id = _get_authenticated_context(username, password)
		raw_payload = download_pump_settings_blob(session, access_token)

		if isinstance(raw_payload, str):
			clean_payload = raw_payload.strip().strip('"')
			try:
				payload_bytes = base64.b64decode(clean_payload)
			except Exception:
				payload_bytes = raw_payload.encode("utf-8")
		else:
			payload_bytes = bytes(raw_payload)

		out_dir = _safe_export_dir(export_dir)
		filename = f"pumpsettings_{_export_timestamp()}.bin"
		path = os.path.join(out_dir, filename)

		with open(path, "wb") as f:
			f.write(payload_bytes)

		return json.dumps({
			"status": "ok",
			"type": "settings_bin",
			"path": path,
			"bytes": len(payload_bytes),
		})
	except Exception as exc:
		return json.dumps({
			"status": "error",
			"type": "settings_bin",
			"detail": {
				"message": str(exc),
				"exception": exc.__class__.__name__,
			}
		})


def _app_download_dir(app_files_dir: str) -> str:
	"""Return app-specific export folder: <filesDir>/Download/TandemSourceRT."""
	base_dir = app_files_dir or "."
	path = os.path.join(base_dir, "Download", "TandemSourceRT")
	os.makedirs(path, exist_ok=True)
	return path


def decode_pump_events_blob(base64_blob: str):
	blob_bytes = _base64_blob_to_bytes(base64_blob)

	with tempfile.NamedTemporaryFile(delete=False, suffix=".bin") as tmp:
		tmp.write(blob_bytes)
		tmp_path = tmp.name

	try:
		return decode_blob_to_dataset(tmp_path)
	finally:
		try:
			os.unlink(tmp_path)
		except Exception:
			pass


def fetch_live_dataset(username: str, password: str, selected_date: str = None):
	try:
		print(f"[PYTHON] fetch_live_dataset selected_date={selected_date}")

		session, access_token, pumper_id = _get_authenticated_context(username, password)
		sources = get_pump_event_sources(session, access_token, pumper_id)

		datasets = []
		source_summaries = []

		for source in sources:
			tconnect_device_id = source["tconnectDeviceId"]
			serial_number = source["serialNumber"]

			base64_blob = download_pump_events_blob(
				session,
				access_token,
				pumper_id,
				tconnect_device_id,
				selected_date
			)

			print(
				f"[PYTHON] downloaded device={tconnect_device_id} "
				f"serial={serial_number} base64_chars={len(base64_blob)}"
			)

			decoded_dataset = decode_pump_events_blob(base64_blob)
			decoded_counts = {
				k: len(v)
				for k, v in decoded_dataset.items()
				if isinstance(v, list)
			}
			print(
				f"[PYTHON] decoded device={tconnect_device_id} "
				f"serial={serial_number} counts={decoded_counts}"
			)

			datasets.append(decoded_dataset)
			source_summaries.append({
				"serialNumber": serial_number,
				"tconnectDeviceId": tconnect_device_id,
				"minDateWithEvents": source.get("minDateWithEvents"),
				"maxDateWithEvents": source.get("maxDateWithEvents"),
				"lastUpload": source.get("lastUpload"),
				"base64Chars": len(base64_blob),
				"counts": decoded_counts,
			})

		dataset = _merge_datasets(datasets)
		merged_counts = {
			k: len(v)
			for k, v in dataset.items()
			if isinstance(v, list)
		}
		print(f"[PYTHON] merged counts={merged_counts}")

		return json.dumps({
			"status": "ok",
			"pumperId": pumper_id,
			"sources": source_summaries,
			"counts": merged_counts,
			"data": dataset,
		})
	except Exception as exc:
		import traceback
		err = traceback.format_exc().replace("\n", " | ")
		print(f"[PYTHON][ERROR] {err}")
		return json.dumps({
			"status": "error",
			"detail": {
				"message": str(exc),
				"type": exc.__class__.__name__,
				"traceback": err,
			}
		})
	
def decode_test_blob(path):
	data = decode_blob_to_dataset(path)
	return json.dumps({
		"status": "ok",
		"counts": {k: len(v) for k, v in data.items()},
		"data": data
	})
