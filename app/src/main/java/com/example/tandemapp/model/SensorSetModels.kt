package com.example.tandemapp.model

import kotlinx.serialization.Serializable

@Serializable
data class SensorSetData(
	val dataTimestamp: String,
	val sourceTimestamp: String? = null,
	val sourceTimestampIsPumpLocal: Boolean = false,
	val sensorType: String? = null,
	val sensorObservedSince: String? = null,
	val estimatedSensorEnd: String? = null,
	val lastSetChange: String? = null,
	val nextSetChangeDue: String? = null,
	val remainingInsulinUnits: Int? = null,
	val remainingInsulinTimestamp: String? = null,
	val batteryPercent: Int? = null,
	val batteryTimestamp: String? = null,
	val siteChangeReminderDays: Int? = null,
	val siteChangeReminderMinutes: Int? = null,
	val isFromCache: Boolean = false
)

sealed interface SensorSetUiState {
	data object Idle : SensorSetUiState
	data object Loading : SensorSetUiState
	data class Ready(val data: SensorSetData) : SensorSetUiState
	data class Error(val message: String) : SensorSetUiState
}
