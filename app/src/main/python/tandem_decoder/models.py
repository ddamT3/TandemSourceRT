from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Any


@dataclass
class MetaSource:
	blobFiles: list[str] = field(default_factory=list)
	metadataVersion: str | None = None


@dataclass
class DeviceMeta:
	tconnectDeviceId: str | None = None
	serialNumber: str | None = None
	modelNumber: str | None = None
	softwareVersion: str | None = None
	partNumber: str | None = None


@dataclass
class PatientMeta:
	name: str | None = None
	dateOfBirth: str | None = None
	careGiver: str | None = None


@dataclass
class SettingsMeta:
	cgm_min: int | None = None
	cgm_max: int | None = None
	glucoseUom: str = "mg/dL"
	profiles: list[dict[str, Any]] = field(default_factory=list)
	activeProfileId: int | None = None
	controlIQ: dict[str, Any] = field(default_factory=dict)
	alerts: dict[str, Any] = field(default_factory=dict)
	pumpSettings: dict[str, Any] = field(default_factory=dict)


@dataclass
class Meta:
	source: MetaSource = field(default_factory=MetaSource)
	device: DeviceMeta = field(default_factory=DeviceMeta)
	patient: PatientMeta = field(default_factory=PatientMeta)
	settings: SettingsMeta = field(default_factory=SettingsMeta)


@dataclass
class BasalTimeline:
	profile: list[dict[str, Any]] = field(default_factory=list)
	delivered: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class Timeline:
	glucose: list[dict[str, Any]] = field(default_factory=list)
	bolus: list[dict[str, Any]] = field(default_factory=list)
	basal: BasalTimeline = field(default_factory=BasalTimeline)
	deviceStates: list[dict[str, Any]] = field(default_factory=list)
	events: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class DebugInfo:
	recordCounts: dict[str, int] = field(default_factory=dict)
	unknownRecordTypes: list[str] = field(default_factory=list)
	warnings: list[str] = field(default_factory=list)


@dataclass
class TandemDataset:
	meta: Meta = field(default_factory=Meta)
	timeline: Timeline = field(default_factory=Timeline)
	debug: DebugInfo = field(default_factory=DebugInfo)

	def to_dict(self) -> dict[str, Any]:
		return asdict(self)
