package com.example.tandemapp.model

import kotlinx.serialization.Serializable

@Serializable
data class LiveDayDataset(
	val cgm: List<CgmPoint>,
	val bolus: List<BolusEvent>,
	val basal: List<BasalPoint>,
	val iob: List<IobPoint>,
	val cho: List<CarbEvent>,
	val deviceState: List<DeviceStateEvent>,
	val supplementalEvents: List<SupplementalPumpEvent> = emptyList()
) {
	fun toDayDataset(): DayDataset {
		return DayDataset(
			cgm = cgm,
			bolus = bolus,
			carbs = cho,
			iob = iob,
			basal = basal,
			deviceStates = deviceState,
			supplementalEvents = supplementalEvents
		)
	}
}
