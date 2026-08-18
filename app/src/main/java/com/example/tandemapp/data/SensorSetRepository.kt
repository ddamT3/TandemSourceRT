package com.example.tandemapp.data

import android.content.Context
import com.example.tandemapp.model.DayDataset
import com.example.tandemapp.model.PumpSettingsData
import com.example.tandemapp.model.SensorSetData
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class SensorSetRepository(context: Context) {
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
	private val cacheFile = context.applicationContext.filesDir.resolve("latest_sensor_set.json")

	fun readCached(): SensorSetData? = runCatching {
		if (!cacheFile.isFile) return null
		json.decodeFromString<SensorSetData>(cacheFile.readText()).copy(isFromCache = true)
	}.getOrNull()

	fun update(dataset: DayDataset, settings: PumpSettingsData?): SensorSetData? {
		val cached = readCached()?.copy(isFromCache = false)
		val dataTimestamp = latestTimestamp(dataset) ?: return cached?.copy(isFromCache = true)
		val (incomingTimestamp, sourceTimestampIsPumpLocal) = latestSourceTimestamp(dataTimestamp, settings, cached)
		val cachedTimestamp = cached?.dataTimestamp
		if (cachedTimestamp != null && epochMillis(dataTimestamp) < epochMillis(cachedTimestamp)) {
			return requireNotNull(cached).copy(isFromCache = false)
		}

		val sensorType = dataset.deviceStates
			.asSequence()
			.filter { !it.SensorType.isNullOrBlank() }
			.maxByOrNull { epochMillis(it.time) }
			?.SensorType
			?: cached?.sensorType
		val lastSessionBoundary = dataset.deviceStates
			.asSequence()
			.filter { it.sourceEventId == 447 }
			.maxByOrNull { epochMillis(it.time) }
			?.time
		val observedSince = dataset.cgm
			.asSequence()
			.filter { lastSessionBoundary == null || epochMillis(it.time) > epochMillis(lastSessionBoundary) }
			.minByOrNull { epochMillis(it.time) }
			?.time
			?: cached?.sensorObservedSince
		val estimatedEnd = observedSince?.let { plusDays(it, 10) }
			?: cached?.estimatedSensorEnd

		val completedSetChange = dataset.supplementalEvents
			.asSequence()
			.filter { it.eventCode == 61 }
			.filter { (it.eventProperties["completionStatus"] as? JsonPrimitive)?.intOrNull == 3 }
			.filter { !it.time.isNullOrBlank() }
			.maxByOrNull { epochMillis(requireNotNull(it.time)) }
			?.time
			?: cached?.lastSetChange

		val reminder = settings?.reminders?.siteChangeReminder
		val reminderDays = settings?.reminders?.siteChangeDays
			?.takeIf { reminder?.enabled == true && it > 0 }
			?: cached?.siteChangeReminderDays
		val reminderMinutes = reminder?.frequencyMinutes
			?.takeIf { reminder.enabled && it in 0 until 24 * 60 }
			?: cached?.siteChangeReminderMinutes
		val nextSetChange = if (completedSetChange != null && reminderDays != null && reminderMinutes != null) {
			setReminderTime(completedSetChange, reminderDays, reminderMinutes)
		} else cached?.nextSetChangeDue

		val remainingInsulin = dataset.supplementalEvents
			.asSequence()
			.filter { it.eventCode == 9 && !it.time.isNullOrBlank() }
			.mapNotNull { event ->
				val value = (event.eventProperties["insulin"] as? JsonPrimitive)?.intOrNull
				value?.let { Triple(requireNotNull(event.time), event.sequenceNumber, it) }
			}
			.maxWithOrNull(compareBy<Triple<String, Int, Int>>({ epochMillis(it.first) }, { it.second }))
		val battery = dataset.supplementalEvents
			.asSequence()
			.filter { it.eventCode == 9 && !it.time.isNullOrBlank() }
			.mapNotNull { event ->
				val value = (event.eventProperties["abc"] as? JsonPrimitive)?.intOrNull
				value?.let { Triple(requireNotNull(event.time), event.sequenceNumber, it) }
			}
			.maxWithOrNull(compareBy<Triple<String, Int, Int>>({ epochMillis(it.first) }, { it.second }))

		val updated = SensorSetData(
			dataTimestamp = dataTimestamp,
			sourceTimestamp = incomingTimestamp,
			sourceTimestampIsPumpLocal = sourceTimestampIsPumpLocal,
			sensorType = sensorType,
			sensorObservedSince = observedSince,
			estimatedSensorEnd = estimatedEnd,
			lastSetChange = completedSetChange,
			nextSetChangeDue = nextSetChange,
			remainingInsulinUnits = remainingInsulin?.third ?: cached?.remainingInsulinUnits,
			remainingInsulinTimestamp = remainingInsulin?.first ?: cached?.remainingInsulinTimestamp,
			batteryPercent = battery?.third ?: cached?.batteryPercent,
			batteryTimestamp = battery?.first ?: cached?.batteryTimestamp,
			siteChangeReminderDays = reminderDays,
			siteChangeReminderMinutes = reminderMinutes
		)
		writeCache(updated)
		return updated
	}

	private fun latestTimestamp(dataset: DayDataset): String? = buildList {
		addAll(dataset.cgm.map { it.time })
		addAll(dataset.deviceStates.map { it.time })
		addAll(dataset.supplementalEvents.mapNotNull { it.time })
	}.maxByOrNull(::epochMillis)

	private fun latestSourceTimestamp(
		dataTimestamp: String,
		settings: PumpSettingsData?,
		cached: SensorSetData?
	): Pair<String, Boolean> {
		val serverTimestamp = listOfNotNull(settings?.lastUploadDate, settings?.settingsTimestamp)
			.maxByOrNull(::epochMillis)
		return when {
			serverTimestamp != null -> serverTimestamp to false
			cached?.sourceTimestamp != null && !cached.sourceTimestampIsPumpLocal -> cached.sourceTimestamp to false
			else -> dataTimestamp to true
		}
	}

	private fun plusDays(value: String, days: Long): String = runCatching {
		OffsetDateTime.parse(value).plusDays(days).toString()
	}.recoverCatching {
		LocalDateTime.parse(value.removeSuffix("Z")).plusDays(days).toString()
	}.getOrDefault(value)

	private fun setReminderTime(value: String, days: Int, minutes: Int): String = runCatching {
		OffsetDateTime.parse(value).plusDays(days.toLong())
			.withHour(minutes / 60).withMinute(minutes % 60).withSecond(0).withNano(0).toString()
	}.recoverCatching {
		LocalDateTime.parse(value.removeSuffix("Z")).plusDays(days.toLong())
			.withHour(minutes / 60).withMinute(minutes % 60).withSecond(0).withNano(0).toString()
	}.getOrDefault(value)

	private fun epochMillis(value: String): Long =
		runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
			?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
			?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
			?: Long.MIN_VALUE

	private fun writeCache(data: SensorSetData) {
		val temporary = cacheFile.resolveSibling("${cacheFile.name}.tmp")
		temporary.writeText(json.encodeToString(data.copy(isFromCache = false)))
		if (!temporary.renameTo(cacheFile)) {
			cacheFile.writeText(temporary.readText())
			temporary.delete()
		}
	}
}
