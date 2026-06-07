from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def read_json(path: str | Path) -> Any:
	with open(path, "r", encoding="utf-8") as f:
		return json.load(f)


def write_json(path: str | Path, payload: Any) -> None:
	with open(path, "w", encoding="utf-8") as f:
		json.dump(payload, f, ensure_ascii=False, indent=2)
		f.write("\n")
