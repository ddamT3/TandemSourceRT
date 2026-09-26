package com.example.tandemapp.data

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object CalendarReminderTime {
	fun toEpochMillis(value: String, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
		return parsePumpLocalDateTime(value)?.atZone(zoneId)?.toInstant()?.toEpochMilli()
	}

	fun sensorEventEpochMillis(value: String, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
		val estimatedEnd = parsePumpLocalDateTime(value) ?: return null
		val eventTime = when {
			estimatedEnd.toLocalTime().isBefore(SENSOR_WINDOW_START) ->
				estimatedEnd.minusDays(1).with(SENSOR_WINDOW_END)
			estimatedEnd.toLocalTime().isAfter(SENSOR_WINDOW_END) ->
				estimatedEnd.with(SENSOR_WINDOW_END)
			else -> estimatedEnd
		}
		return eventTime.atZone(zoneId).toInstant().toEpochMilli()
	}

	fun displayTime(value: String): String? = parsePumpLocalDateTime(value)
		?.format(DISPLAY_FORMATTER)

	private fun parsePumpLocalDateTime(value: String): LocalDateTime? {
		val localValue = value.removeSuffix("Z").substringBeforeLast('+').let {
			if (it.length > 10 && it.lastIndexOf('-') > 10) it.substring(0, it.lastIndexOf('-')) else it
		}
		return runCatching { LocalDateTime.parse(localValue) }.getOrNull()
	}

	private val SENSOR_WINDOW_START = LocalTime.of(10, 0)
	private val SENSOR_WINDOW_END = LocalTime.of(20, 0)
	private val DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
}
