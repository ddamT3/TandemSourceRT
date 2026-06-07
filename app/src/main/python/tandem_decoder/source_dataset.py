from __future__ import annotations

import json
from pathlib import Path
from typing import Any

def load_source_dataset(path: str | Path) -> dict[str, Any]:
	with Path(path).open("r", encoding="utf-8") as f:
		return json.load(f)

def get_section(source_dataset: dict[str, Any], name: str) -> list[dict[str, Any]]:
	data = source_dataset.get(name)
	return data if isinstance(data, list) else []

def get_cgm_records(source_dataset: dict[str, Any]) -> list[dict[str, Any]]:
	return get_section(source_dataset, "cgm")
