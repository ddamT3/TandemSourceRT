package com.example.tandemapp.data

import android.accounts.Account
import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import com.example.tandemapp.model.SensorSetData
import java.time.ZoneId

class CalendarReminderRepository(context: Context) {
	private val appContext = context.applicationContext
	private val resolver = appContext.contentResolver
	private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

	fun setEnabled(enabled: Boolean) {
		preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
	}

	fun synchronize(data: SensorSetData): CalendarSyncResult = runCatching {
		val calendar = writableCalendar()
			?: return CalendarSyncResult.Failure("No writable Android calendar is available")
		val specs = listOfNotNull(
			data.estimatedSensorEnd?.let { value ->
				val estimatedTime = CalendarReminderTime.displayTime(value)
					?: return CalendarSyncResult.Failure("Invalid sensor expiration time")
				CalendarEventSpec(
					key = SENSOR_KEY,
					title = "Sensor estimated: $estimatedTime",
					startMillis = CalendarReminderTime.sensorEventEpochMillis(value)
						?: return CalendarSyncResult.Failure("Invalid sensor expiration time")
				)
			},
			data.nextSetChangeDue?.let { value ->
				val dueTime = CalendarReminderTime.displayTime(value)
					?: return CalendarSyncResult.Failure("Invalid infusion set change time")
				CalendarEventSpec(
					key = INFUSION_SET_KEY,
					title = "Infusion set due: $dueTime",
					startMillis = CalendarReminderTime.toEpochMillis(value)
						?: return CalendarSyncResult.Failure("Invalid infusion set change time")
				)
			}
		)
		if (specs.isEmpty()) return CalendarSyncResult.Failure("No reminder dates are available")
		val changed = specs.fold(false) { wasChanged, spec ->
			synchronizeEvent(calendar.id, spec) || wasChanged
		}
		if (changed) notifyCalendarChanged(calendar)
		CalendarSyncResult.Success
	}.getOrElse { CalendarSyncResult.Failure(it.message ?: "Calendar synchronization failed") }

	fun deleteManagedEvents(): CalendarSyncResult = runCatching {
		val calendar = writableCalendar()
		var changed = false
		listOf(SENSOR_KEY, INFUSION_SET_KEY).forEach { key ->
			findManagedEventIds(key).forEach { eventId ->
				changed = deleteEvent(eventId) || changed
			}
			preferences.edit().remove(idPreferenceKey(key)).apply()
		}
		if (changed) notifyCalendarChanged(calendar)
		CalendarSyncResult.Success
	}.getOrElse { CalendarSyncResult.Failure(it.message ?: "Unable to remove calendar reminders") }

	private fun synchronizeEvent(calendarId: Long, spec: CalendarEventSpec): Boolean {
		val matchingIds = findManagedEventIds(spec.key)
		val storedId = preferences.getLong(idPreferenceKey(spec.key), NO_EVENT_ID)
		var changed = false
		val eventId = when {
			storedId in matchingIds -> storedId
			matchingIds.isNotEmpty() -> matchingIds.first()
			else -> createEvent(calendarId, spec).also { changed = true }
		}
		matchingIds.filter { it != eventId }.forEach { duplicateId ->
			changed = deleteEvent(duplicateId) || changed
		}
		preferences.edit().putLong(idPreferenceKey(spec.key), eventId).apply()

		if (!eventMatches(eventId, calendarId, spec)) {
			changed = resolver.update(
				ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
				eventValues(calendarId, spec),
				null,
				null
			) > 0 || changed
		}
		if (!remindersMatch(eventId)) {
			replaceReminders(eventId)
			changed = true
		}
		return changed
	}

	private fun createEvent(calendarId: Long, spec: CalendarEventSpec): Long {
		val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, eventValues(calendarId, spec))
			?: error("Unable to create calendar event")
		val eventId = ContentUris.parseId(uri)
		replaceReminders(eventId)
		return eventId
	}

	private fun eventValues(calendarId: Long, spec: CalendarEventSpec) = ContentValues().apply {
		put(CalendarContract.Events.CALENDAR_ID, calendarId)
		put(CalendarContract.Events.TITLE, spec.title)
		put(CalendarContract.Events.DESCRIPTION, marker(spec.key))
		put(CalendarContract.Events.DTSTART, spec.startMillis)
		put(CalendarContract.Events.DTEND, spec.startMillis + EVENT_DURATION_MILLIS)
		put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
		put(CalendarContract.Events.HAS_ALARM, 1)
	}

	private fun eventMatches(eventId: Long, calendarId: Long, spec: CalendarEventSpec): Boolean {
		val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
		return resolver.query(uri, EVENT_PROJECTION, null, null, null)?.use { cursor ->
			if (!cursor.moveToFirst()) return@use false
			cursor.getLong(0) == calendarId &&
				cursor.getString(1) == spec.title &&
				cursor.getString(2) == marker(spec.key) &&
				cursor.getLong(3) == spec.startMillis &&
				cursor.getLong(4) == spec.startMillis + EVENT_DURATION_MILLIS &&
				cursor.getString(5) == ZoneId.systemDefault().id &&
				cursor.getInt(6) == 1
		} ?: false
	}

	private fun remindersMatch(eventId: Long): Boolean {
		val reminders = mutableListOf<Pair<Int, Int>>()
		resolver.query(
			CalendarContract.Reminders.CONTENT_URI,
			REMINDER_PROJECTION,
			"${CalendarContract.Reminders.EVENT_ID} = ?",
			arrayOf(eventId.toString()),
			null
		)?.use { cursor ->
			while (cursor.moveToNext()) reminders += cursor.getInt(0) to cursor.getInt(1)
		}
		return reminders.sortedBy { it.first } == REQUIRED_REMINDERS.sortedBy { it.first }
	}

	private fun replaceReminders(eventId: Long) {
		resolver.delete(
			CalendarContract.Reminders.CONTENT_URI,
			"${CalendarContract.Reminders.EVENT_ID} = ?",
			arrayOf(eventId.toString())
		)
		REQUIRED_REMINDERS.forEach { (minutes, method) ->
			resolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
				put(CalendarContract.Reminders.EVENT_ID, eventId)
				put(CalendarContract.Reminders.MINUTES, minutes)
				put(CalendarContract.Reminders.METHOD, method)
			}) ?: error("Unable to create calendar reminder")
		}
	}

	private fun findManagedEventIds(key: String): List<Long> {
		val ids = mutableListOf<Long>()
		resolver.query(
			CalendarContract.Events.CONTENT_URI,
			arrayOf(CalendarContract.Events._ID),
			"${CalendarContract.Events.DESCRIPTION} = ? AND ${CalendarContract.Events.DELETED} = 0",
			arrayOf(marker(key)),
			null
		)?.use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
		return ids
	}

	private fun deleteEvent(eventId: Long): Boolean {
		return resolver.delete(
			ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
			null,
			null
		) > 0
	}

	private fun writableCalendar(): CalendarTarget? = resolver.query(
		CalendarContract.Calendars.CONTENT_URI,
		arrayOf(
			CalendarContract.Calendars._ID,
			CalendarContract.Calendars.ACCOUNT_NAME,
			CalendarContract.Calendars.ACCOUNT_TYPE
		),
		"${CalendarContract.Calendars.VISIBLE} = 1 AND " +
			"${CalendarContract.Calendars.SYNC_EVENTS} = 1 AND " +
			"${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
		arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
		"${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
	)?.use { cursor ->
		if (!cursor.moveToFirst()) null else CalendarTarget(
			id = cursor.getLong(0),
			accountName = cursor.getString(1),
			accountType = cursor.getString(2)
		)
	}

	private fun notifyCalendarChanged(calendar: CalendarTarget?) {
		resolver.notifyChange(
			CalendarContract.Events.CONTENT_URI,
			null,
			ContentResolver.NOTIFY_SYNC_TO_NETWORK
		)
		resolver.notifyChange(
			CalendarContract.Instances.CONTENT_URI,
			null,
			ContentResolver.NOTIFY_SYNC_TO_NETWORK
		)

		val accountName = calendar?.accountName?.takeIf { it.isNotBlank() } ?: return
		val accountType = calendar.accountType?.takeIf { it.isNotBlank() } ?: return
		if (accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) return
		runCatching {
			ContentResolver.requestSync(
				Account(accountName, accountType),
				CalendarContract.AUTHORITY,
				Bundle().apply {
					putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
					putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
				}
			)
		}
	}

	private fun marker(key: String) = "$MARKER_PREFIX$key"
	private fun idPreferenceKey(key: String) = "event_id_$key"

	private data class CalendarEventSpec(val key: String, val title: String, val startMillis: Long)
	private data class CalendarTarget(
		val id: Long,
		val accountName: String?,
		val accountType: String?
	)

	companion object {
		private const val PREFERENCES_NAME = "calendar_reminders"
		private const val KEY_ENABLED = "enabled"
		private const val SENSOR_KEY = "sensor_expiration"
		private const val INFUSION_SET_KEY = "infusion_set_change"
		private const val MARKER_PREFIX = "Managed by TandemSourceRT:"
		private const val NO_EVENT_ID = -1L
		private const val EVENT_DURATION_MILLIS = 15 * 60 * 1000L
		private val EVENT_PROJECTION = arrayOf(
			CalendarContract.Events.CALENDAR_ID,
			CalendarContract.Events.TITLE,
			CalendarContract.Events.DESCRIPTION,
			CalendarContract.Events.DTSTART,
			CalendarContract.Events.DTEND,
			CalendarContract.Events.EVENT_TIMEZONE,
			CalendarContract.Events.HAS_ALARM
		)
		private val REMINDER_PROJECTION = arrayOf(
			CalendarContract.Reminders.MINUTES,
			CalendarContract.Reminders.METHOD
		)
		private val REQUIRED_REMINDERS = listOf(
			120 to CalendarContract.Reminders.METHOD_ALERT,
			0 to CalendarContract.Reminders.METHOD_ALERT
		)
	}
}

sealed interface CalendarSyncResult {
	data object Success : CalendarSyncResult
	data class Failure(val message: String) : CalendarSyncResult
}
