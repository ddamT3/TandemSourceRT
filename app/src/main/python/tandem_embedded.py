import base64
import hashlib
import json
import os
import re
import secrets
import threading
import time
from datetime import datetime, timedelta
from urllib.parse import parse_qs, urlparse

import requests

SOURCE_BASE_URL = "https://source.eu.tandemdiabetes.com"
ACCOUNTS_BASE_URL = "https://tdcservices.eu.tandemdiabetes.com/accounts/api"
CLIENT_ID = "1519e414-eeec-492e-8c5e-97bea4815a10"
REDIRECT_URI = "https://source.eu.tandemdiabetes.com/authorize/callback"
USER_AGENT = "Mozilla/5.0"
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
AUTH_EXPIRY_SKEW_SECONDS = 90


_auth_cache = {}
_auth_lock = threading.RLock()


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


def _token_is_usable(access_token: str) -> bool:
	"""Return True while a cached access token is safely before expiry."""
	if not access_token:
		return False

	payload = _decode_jwt_payload(access_token)
	expires_at = payload.get("exp")
	try:
		return float(expires_at) > time.time() + AUTH_EXPIRY_SKEW_SECONDS
	except (TypeError, ValueError):
		return False


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
	Return the reports-BFF pump-logs window around the requested date.

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


def _short_response_body(response, limit=300):
	"""Return a compact, single-line response preview for diagnostics."""
	try:
		body = response.text or ""
	except Exception:
		return ""

	body = re.sub(r"\s+", " ", body).strip()
	body = re.sub(
		r'(?i)("?(?:access_token|id_token|refresh_token|token|password|username|email)"?\s*[:=]\s*")[^"]*"',
		r'\1[REDACTED]"',
		body,
	)
	body = re.sub(
		r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b",
		"[EMAIL_REDACTED]",
		body,
	)
	body = re.sub(
		r"\b[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\b",
		"[JWT_REDACTED]",
		body,
	)
	return body[:limit]


def _safe_diagnostic_url(value):
	"""Redact OAuth secrets from a URL before writing it to Logcat."""
	if not value:
		return value
	return re.sub(
		r"(?i)([?&](?:code|token|access_token|id_token|refresh_token)=)[^&\s]+",
		r"\1[REDACTED]",
		str(value),
	)


def _log_http_diagnostic(label, response, include_body=False):
	"""Log routing information without tokens, cookies or request headers."""
	body_head = _short_response_body(response) if include_body else "[OMITTED]"
	print(
		f"[PYTHON][HTTP] {label} "
		f"status={response.status_code} "
		f"final_url={_safe_diagnostic_url(response.url)} "
		f"content_type={response.headers.get('Content-Type')} "
		f"location={_safe_diagnostic_url(response.headers.get('Location'))} "
		f"history={[item.status_code for item in response.history]} "
		f"body_head={body_head}"
	)


def get_pumper(session: requests.Session, access_token: str, pumper_id: str):
	"""Return pumper information from the current pumpers API."""
	url = f"{SOURCE_BASE_URL}/api/pumpers/pumpers/{pumper_id}"
	response = session.get(
		url,
		headers=_auth_headers(access_token),
		allow_redirects=True,
		timeout=60,
	)
	_log_http_diagnostic(f"pumper candidate={pumper_id}", response)

	if response.status_code != 200:
		raise RuntimeError(f"Errore pumper {pumper_id}: {response.status_code}")

	try:
		data = response.json()
	except Exception as exc:
		raise RuntimeError(
			f"Risposta pumper non JSON: {exc.__class__.__name__}"
		) from exc

	if not isinstance(data, dict):
		raise RuntimeError(f"Formato pumper inatteso: {type(data).__name__}")

	returned_id = data.get("id")
	if returned_id and returned_id != pumper_id:
		raise RuntimeError(f"Risposta relativa a un altro pumperId: {returned_id}")

	print(
		f"[PYTHON][DISCOVERY] pumper_endpoint={response.url} "
		f"devices={len(data.get('devices') or [])}"
	)
	return data

def get_reports_pumper(
	session: requests.Session,
	access_token: str,
	pumper_id: str,
):
	"""Return the pumper payload used by the Tandem Source Reports BFF."""
	url = f"{SOURCE_BASE_URL}/api/reports/bff/pumper/{pumper_id}"
	response = session.get(
		url,
		headers=_auth_headers(access_token),
		allow_redirects=True,
		timeout=60,
	)
	_log_http_diagnostic(f"reports-pumper candidate={pumper_id}", response)

	if response.status_code != 200:
		raise RuntimeError(f"Errore reports pumper {pumper_id}: {response.status_code}")

	try:
		data = response.json()
	except Exception as exc:
		raise RuntimeError(
			f"Risposta reports pumper non JSON: {exc.__class__.__name__}"
		) from exc

	if not isinstance(data, dict):
		raise RuntimeError(
			f"Formato reports pumper inatteso: {type(data).__name__}"
		)

	pumps = data.get("pumps")
	if not isinstance(pumps, list):
		raise RuntimeError("Campo pumps mancante nella risposta reports pumper")

	print(
		f"[PYTHON][DISCOVERY] reports_pumper_endpoint={response.url} "
		f"pumps={len(pumps)}"
	)
	return data

def discover_pumper_id(
	session: requests.Session,
	access_token: str,
	token_result: dict,
):
	"""Discover the pumperId from JWT claims and validate it via the current API."""
	preferred = []

	for token_name, claim_names in (
		("id_token", ("pumperId", "pumper_id")),
		("access_token", ("tandem_pumper_id", "pumperId", "pumper_id")),
	):
		token = token_result.get(token_name)
		if not token:
			continue

		payload = _decode_jwt_payload(token)
		for claim_name in claim_names:
			value = payload.get(claim_name)
			if isinstance(value, str) and UUID_RE.match(value):
				preferred.append(value)
				print(
					f"[PYTHON][DISCOVERY] pumper claim "
					f"token={token_name} claim={claim_name}"
				)

	candidates = _unique_keep_order(
		preferred
		+ _extract_candidate_pumper_ids_from_token_result(token_result)
	)

	errors = []
	for candidate in candidates:
		try:
			data = get_pumper(session, access_token, candidate)
			if data.get("id") == candidate:
				print(f"[PYTHON][DISCOVERY] validated_pumper_id={candidate}")
				return candidate
		except Exception as exc:
			errors.append(f"{candidate}: {exc}")

	raise RuntimeError(
		"pumperId non trovato tramite i token e la API corrente. "
		f"candidates={candidates} errors={errors[:8]}"
	)

def _normalize_pump_assignments(devices):
	"""Normalize pump assignments returned by the current pumper endpoint."""
	items = []

	for item in devices or []:
		if not isinstance(item, dict):
			continue

		assignment_id = item.get("assignmentId")
		if assignment_id is None:
			continue

		items.append({
			"assignmentId": str(assignment_id),
			"serialNumber": str(item.get("serialNumber", "unknown")),
			"modelNumber": item.get("modelNumber"),
			"modelName": item.get("modelName"),
			"assignedAt": item.get("assignedAt"),
			"minDateWithEvents": item.get("minDateWithEvents"),
			"maxDateWithEvents": item.get("maxDateWithEvents"),
			"lastUpload": item.get("lastUpload"),
			"settings": item.get("settings"),
		})

	return items

def get_pump_assignments(
	session: requests.Session,
	access_token: str,
	pumper_id: str,
):
	"""Return pump assignments used by the current reports BFF."""
	pumper = get_pumper(session, access_token, pumper_id)
	assignments = _normalize_pump_assignments(pumper.get("devices") or [])

	if not assignments:
		raise RuntimeError(
			"Nessun assignmentId trovato nella risposta pumper corrente"
		)

	print(
		"[PYTHON] pump assignments: "
		+ ", ".join(
			f"{assignment['serialNumber']}->{assignment['assignmentId']} "
			f"assignedAt={assignment.get('assignedAt')}"
			for assignment in assignments
		)
	)

	return assignments

def download_pump_logs(
	session: requests.Session,
	access_token: str,
	pumper_id: str,
	assignment_id: str,
	selected_date: str = None,
):
	"""Download decoded pump events from the current reports BFF."""
	url = (
		f"{SOURCE_BASE_URL}/api/reports/bff/"
		f"pump-logs/{assignment_id}"
	)

	start_date, end_date = _date_window_from_selected(selected_date)
	params = {
		"pumperId": pumper_id,
		"startDate": f"{start_date.isoformat()}T00:00:00Z",
		"endDate": f"{end_date.isoformat()}T23:59:59Z",
		"eventIds": "229,5,28,4,26,99,279,3,16,59,21,55,20,280,64,65,66,61,33,371,171,369,460,172,370,461,372,480,399,256,213,406,477,394,212,404,214,405,486,447,313,60,14,6,90,230,140,12,11,53,13,63,203,191,81",
	}

	print(
		f"[PYTHON] download_pump_logs "
		f"pumperId={pumper_id} assignmentId={assignment_id} "
		f"selected_date={selected_date} "
		f"startDate={params['startDate']} endDate={params['endDate']}"
	)

	response = session.get(
		url,
		params=params,
		headers=_auth_headers(access_token),
		timeout=120,
	)

	_log_http_diagnostic(
		f"pump-logs assignment={assignment_id}",
		response,
	)

	if response.status_code != 200:
		raise RuntimeError(
			f"Errore pump-logs: {response.status_code} "
			f"{response.text[:500]}"
		)

	try:
		data = response.json()
	except Exception as exc:
		raise RuntimeError(
			f"Risposta pump-logs non JSON: {exc.__class__.__name__}"
		) from exc

	if not isinstance(data, dict):
		raise RuntimeError(
			f"Formato pump-logs inatteso: {type(data).__name__}"
		)

	events = data.get("events")
	clock_changes = data.get("clockChanges")

	if not isinstance(events, list):
		raise RuntimeError("Campo events mancante nella risposta pump-logs")
	if clock_changes is None:
		clock_changes = []
	if not isinstance(clock_changes, list):
		raise RuntimeError("Campo clockChanges non valido nella risposta pump-logs")

	print(
		f"[PYTHON] pump-logs assignment={assignment_id} "
		f"events={len(events)} clockChanges={len(clock_changes)}"
	)

	return {
		"events": events,
		"clockChanges": clock_changes,
	}

def _get_access_token_for_user(username: str, password: str):
	session = requests.Session()
	
	start = start_authorize_flow(session)
	login = login_with_context(session, username, password)
	_log_http_diagnostic(
		"login",
		login,
		include_body=login.status_code < 200 or login.status_code >= 300,
	)
	if login.status_code < 200 or login.status_code >= 300:
		raise RuntimeError(
			"Login Tandem rifiutato "
			f"(status={login.status_code}, "
			f"content_type={login.headers.get('Content-Type')})"
		)
	after = continue_authorize_flow(session, start.get("location"))

	# With allow_redirects=True, requests stores the final redirect target in
	# response.url. Some Tandem SSO flows do not leave a Location header on the
	# final response, so check the final URL first and fall back to Location.
	location = after.get("url") or after.get("location")
	if location and "code=" not in location:
		location = after.get("location") or location
	code_info = extract_code_from_location(location) if location else {"code": None, "state": None}

	if not code_info["code"]:
		print(
			"[PYTHON] authorization code missing "
			f"start={start.get('status_code')} "
			f"login={login.status_code} after={after.get('status_code')}"
		)
		raise RuntimeError(
			"Authorization code non trovato "
			f"(start={start.get('status_code')}, login={login.status_code}, after={after.get('status_code')})"
		)

	if code_info.get("state") != start["pkce"]["state"]:
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
	cache_key = username.strip().casefold()
	if not cache_key:
		raise RuntimeError("Username mancante")

	with _auth_lock:
		cached = _auth_cache.get(cache_key)
		if cached and _token_is_usable(cached.get("access_token")):
			print("[PYTHON] reusing authenticated Tandem session")
			return (
				cached["session"],
				cached["access_token"],
				cached["pumper_id"],
			)

		# The password is used only for this login attempt and is never cached.
		_auth_cache.pop(cache_key, None)
		print("[PYTHON] starting new Tandem authorization")
		session, access_token, token_result = _get_access_token_for_user(
			username,
			password,
		)
		pumper_id = discover_pumper_id(session, access_token, token_result)
		_auth_cache[cache_key] = {
			"session": session,
			"access_token": access_token,
			"pumper_id": pumper_id,
		}
		return session, access_token, pumper_id


def get_authenticated_context_json(username: str, password: str):
	"""Return the short-lived auth context used by native Kotlin repositories."""
	try:
		_session, access_token, pumper_id = _get_authenticated_context(username, password)
		return json.dumps({
			"status": "ok",
			"accessToken": access_token,
			"pumperId": pumper_id,
		})
	except Exception as exc:
		return json.dumps({
			"status": "error",
			"detail": {
				"message": str(exc),
				"exception": exc.__class__.__name__,
			},
		})


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


def export_pump_logs_json(
	username: str,
	password: str,
	export_dir: str,
	selected_date: str = None,
):
	"""Export current BFF pump logs as JSON, one file per assignment."""
	try:
		session, access_token, pumper_id = _get_authenticated_context(
			username,
			password,
		)
		assignments = get_pump_assignments(
			session,
			access_token,
			pumper_id,
		)

		out_dir = _safe_export_dir(export_dir)
		exported = []
		timestamp = _export_timestamp()

		for assignment in assignments:
			assignment_id = assignment["assignmentId"]
			serial_number = assignment["serialNumber"]

			payload = download_pump_logs(
				session,
				access_token,
				pumper_id,
				assignment_id,
				selected_date,
			)

			filename = (
				f"full_pump_logs_{selected_date or 'latest'}_"
				f"{_safe_filename_part(serial_number)}_"
				f"{_safe_filename_part(assignment_id)}_"
				f"{timestamp}.json"
			)
			path = os.path.join(out_dir, filename)

			with open(path, "w", encoding="utf-8") as output_file:
				json.dump(payload, output_file, ensure_ascii=False)

			exported.append({
				"serialNumber": serial_number,
				"assignmentId": assignment_id,
				"path": path,
				"events": len(payload.get("events") or []),
				"clockChanges": len(payload.get("clockChanges") or []),
				"assignedAt": assignment.get("assignedAt"),
			})

		return json.dumps({
			"status": "ok",
			"type": "pump_logs_json",
			"path": out_dir,
			"pumperId": pumper_id,
			"count": len(exported),
			"files": exported,
		})
	except Exception as exc:
		return json.dumps({
			"status": "error",
			"type": "json",
			"detail": {
				"message": str(exc),
				"exception": exc.__class__.__name__,
			},
		})

def export_pump_settings_json(username: str, password: str, export_dir: str):
	"""Export latest BFF pump settings as JSON, one file per assignment."""
	try:
		session, access_token, pumper_id = _get_authenticated_context(username, password)
		reports_pumper = get_reports_pumper(session, access_token, pumper_id)
		assignments = _normalize_pump_assignments(reports_pumper.get("pumps") or [])
		if not assignments:
			raise RuntimeError(
				"Nessun assignmentId trovato nella risposta reports pumper"
			)
		out_dir = _safe_export_dir(export_dir)
		timestamp = _export_timestamp()
		exported = []

		for assignment in assignments:
			assignment_id = assignment["assignmentId"]
			serial_number = assignment["serialNumber"]
			settings = assignment.get("settings")
			if not isinstance(settings, dict):
				print(
					f"[PYTHON] pump-settings assignment={assignment_id} "
					"settings=missing"
				)

			payload = {
				"source": "reports-bff-pumper",
				"pumperId": pumper_id,
				"assignmentId": assignment_id,
				"serialNumber": serial_number,
				"modelNumber": assignment.get("modelNumber"),
				"modelName": assignment.get("modelName"),
				"assignedAt": assignment.get("assignedAt"),
				"lastUpload": assignment.get("lastUpload"),
				"settings": settings,
			}
			filename = (
				f"pump_settings_{_safe_filename_part(serial_number)}_"
				f"{_safe_filename_part(assignment_id)}_{timestamp}.json"
			)
			path = os.path.join(out_dir, filename)
			with open(path, "w", encoding="utf-8") as output_file:
				json.dump(payload, output_file, ensure_ascii=False, indent=2)

			exported.append({
				"serialNumber": serial_number,
				"assignmentId": assignment_id,
				"path": path,
				"hasSettings": isinstance(settings, dict),
			})

		return json.dumps({
			"status": "ok",
			"type": "pump_settings_json",
			"path": out_dir,
			"pumperId": pumper_id,
			"count": len(exported),
			"files": exported,
		})
	except Exception as exc:
		return json.dumps({
			"status": "error",
			"type": "pump_settings_json",
			"detail": {
				"message": str(exc),
				"exception": exc.__class__.__name__,
			}
		})
