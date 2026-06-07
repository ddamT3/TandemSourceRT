package com.example.tandemapp.model

import kotlinx.serialization.Serializable

@Serializable
data class CgmPoint(
	val time: String,
	val value: Int
)

@Serializable
data class BolusEvent(
	val time: String,
	val insulin_delivered_u: Double,
	val bolus_type: String,
	val bg_mgdl: Double? = null,
	val carbs_g: Double = 0.0,
	val is_extended: Boolean? = null,
	val immediate_insulin_u: Double? = null,
	val extended_insulin_u: Double? = null,
	val extended_duration_min: Int? = null,
	val duration_min: Int? = null
)

@Serializable
data class CarbEvent(
	val time: String,
	val carbs_g: Double
)

@Serializable
data class IobPoint(
	val time: String,
	val iob: Double
)

@Serializable
data class BasalPoint(
	val time: String,
	val CommandedBasalRate: Double
)

@Serializable
data class DeviceStateEvent(
	val time: String,
	val PumpControlState: String? = null,
	val CurrentUserMode: String? = null,
	val PreviousUserMode: String? = null,
	val RequestedAction: String? = null,
	val ExerciseChoice: String? = null,
	val SensorType: String? = null,
	val SleepStartedByGUI: Boolean? = null,
	val ExerciseStoppedByTimer: Boolean? = null,
	val EatingSoonStoppedByTimer: Boolean? = null,
	val ExerciseTimeMin: Int? = null,
	val eventType: String? = null,
	val eventLabel: String? = null,
	val eventSubtype: String? = null,
	val sourceEventId: Int? = null
)

@Serializable
data class DayDataset(
	val cgm: List<CgmPoint>,
	val bolus: List<BolusEvent>,
	val carbs: List<CarbEvent>,
	val iob: List<IobPoint>,
	val basal: List<BasalPoint>,
	val deviceStates: List<DeviceStateEvent>
)
