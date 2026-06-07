from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass
class BlobInspection:
	path: str
	size_bytes: int
	first_32_bytes_hex: str


class BlobDecoderNotImplementedError(NotImplementedError):
	pass


def inspect_blob(path: str | Path) -> BlobInspection:
	p = Path(path)
	raw = p.read_bytes()
	return BlobInspection(
		path=str(p),
		size_bytes=len(raw),
		first_32_bytes_hex=raw[:32].hex(),
	)


def decode_blob_records(path: str | Path) -> list[dict]:
	raise BlobDecoderNotImplementedError(
		"Il decoder proprietario del blob .bin non è ancora implementato in questo scaffold. "
		"La struttura del progetto è pronta per aggiungerlo qui."
	)
