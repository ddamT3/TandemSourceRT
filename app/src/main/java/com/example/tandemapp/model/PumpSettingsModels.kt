package com.example.tandemapp.model

import kotlinx.serialization.Serializable

@Serializable
data class ReportsPumperDto(
	val pumps: List<ReportsPumpDto> = emptyList()
)

@Serializable
data class ReportsPumpDto(
	val assignmentId: String,
	val serialNumber: String,
	val modelNumber: String? = null,
	val modelName: String? = null,
	val softwareVersion: String? = null,
	val algorithm: String? = null,
	val lastUpload: String? = null,
	val lastUploadDate: String? = null,
	val settings: PumpSettingsEnvelopeDto? = null
)

@Serializable
data class PumpSettingsCacheDto(
	val readAt: String,
	val pump: ReportsPumpDto
)

@Serializable
data class PumpSettingsEnvelopeDto(
	val uploadedTimeStamp: String? = null,
	val details: PumpSettingsDetailsDto
)

@Serializable
data class PumpSettingsDetailsDto(
	val basalLimitSettings: BasalLimitSettingsDto? = null,
	val cgmSettings: CgmSettingsDto? = null,
	val controlIqSettings: ControlIqSettingsDto? = null,
	val globalMaxBolusSettings: GlobalMaxBolusSettingsDto? = null,
	val profiles: PumpProfilesDto,
	val pumpSettings: GeneralPumpSettingsDto? = null,
	val reminders: PumpRemindersDto? = null,
	val localizationSettings: LocalizationSettingsDto? = null
)

@Serializable
data class PumpProfilesDto(
	val activeIdp: Int,
	val profile: List<PumpProfileDto> = emptyList()
)

@Serializable
data class PumpProfileDto(
	val idp: Int,
	val name: String,
	val carbEntry: String? = null,
	val maxBolus: Int = 0,
	val insulinDuration: Int = 0,
	val timeDependentSegments: List<PumpProfileSegmentDto> = emptyList()
)

@Serializable
data class PumpProfileSegmentDto(
	val startTime: Int,
	val basalRate: Int,
	val carbRatio: Int,
	val targetBg: Int,
	val isf: Int
)

@Serializable
data class BasalLimitSettingsDto(val basalLimit: Int = 0)

@Serializable
data class GlobalMaxBolusSettingsDto(val maxBolus: Int = 0)

@Serializable
data class GeneralPumpSettingsDto(
	val lowInsulinThreshold: Int? = null,
	val cannulaPrimeSize: Int? = null,
	val autoShutdownEnabled: Boolean? = null,
	val autoShutdownDuration: Int? = null
)

@Serializable
data class CgmSettingsDto(
	val highGlucoseAlertMgPerDl: Int? = null,
	val highGlucoseAlertEnabled: Boolean? = null,
	val highGlucoseAlertDurationMin: Int? = null,
	val lowGlucoseAlertMgPerDl: Int? = null,
	val lowGlucoseAlertEnabled: Boolean? = null,
	val lowGlucoseAlertDurationMin: Int? = null,
	val riseRateAlertEnabled: Boolean? = null,
	val fallRateAlertEnabled: Boolean? = null,
	val sensorTimeoutMinutes: Int? = null,
	val sensorTimeoutEnabled: Boolean? = null
)

@Serializable
data class ControlIqSettingsDto(
	val weight: Int? = null,
	val totalDailyInsulin: Int? = null,
	val closedLoop: Boolean? = null,
	val weightUnit: String? = null,
	val sleepSchedule0: SleepScheduleDto? = null,
	val sleepSchedule1: SleepScheduleDto? = null,
	val sleepSchedule2: SleepScheduleDto? = null,
	val sleepSchedule3: SleepScheduleDto? = null
)

@Serializable
data class SleepScheduleDto(
	val activeDays: List<String> = emptyList(),
	val startTime: Int = 0,
	val enabled: Boolean = false,
	val endTime: Int = 0
)

@Serializable
data class PumpRemindersDto(
	val siteChangeReminder: ReminderDto? = null,
	val lowBgReminder: ReminderDto? = null,
	val highBgReminder: ReminderDto? = null,
	val missedBolusReminders: List<ReminderDto> = emptyList(),
	val afterBolusReminder: ReminderDto? = null,
	val siteChangeDays: Int? = null
)

@Serializable
data class ReminderDto(
	val frequencyMinutes: Int = 0,
	val enabled: Boolean = false
)

@Serializable
data class LocalizationSettingsDto(val glucoseUom: String? = null)

data class PumpSettingsData(
	val requestedDate: String,
	val readAt: String,
	val isFromCache: Boolean,
	val serialNumber: String,
	val modelName: String,
	val softwareVersion: String?,
	val algorithm: String?,
	val lastUploadDate: String?,
	val settingsTimestamp: String?,
	val activeProfileName: String?,
	val profiles: List<PumpProfile>,
	val basalLimitUnitsPerHour: Double?,
	val maxBolusUnits: Double?,
	val pumpSettings: GeneralPumpSettingsDto?,
	val cgmSettings: CgmSettingsDto?,
	val controlIqSettings: ControlIqSettingsDto?,
	val reminders: PumpRemindersDto?,
	val glucoseUnit: String
)

data class PumpProfile(
	val id: Int,
	val name: String,
	val isActive: Boolean,
	val carbEntryEnabled: Boolean,
	val maxBolusUnits: Double,
	val insulinDurationMinutes: Int,
	val dailyBasalUnits: Double,
	val segments: List<PumpProfileSegment>
)

data class PumpProfileSegment(
	val startMinutes: Int,
	val basalUnitsPerHour: Double,
	val correctionFactor: Int,
	val carbRatioGrams: Double,
	val targetMgDl: Int
)

sealed interface PumpSettingsUiState {
	data object Idle : PumpSettingsUiState
	data object Loading : PumpSettingsUiState
	data class Ready(val data: PumpSettingsData) : PumpSettingsUiState
	data class Error(val message: String) : PumpSettingsUiState
}
