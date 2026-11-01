package com.example.tandemapp.viewmodel

import com.example.tandemapp.data.CgmTimeline
import com.example.tandemapp.model.DeviceStateEvent
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A shared view of selectable Event positions; original events and marker details are untouched. */
internal object DashboardEventTimeline {
	private data class Entry(val time: OffsetDateTime, val label: String, val priority: Int)

	private fun entries(events: List<DeviceStateEvent>): List<Entry> = events.mapNotNull { event ->
		val time = runCatching { OffsetDateTime.parse(event.time) }.getOrNull()
			?: runCatching { LocalDateTime.parse(event.time).atOffset(ZoneOffset.UTC) }.getOrNull()
			?: return@mapNotNull null
		val label = label(event)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
		val priority = when {
			event.eventType == CgmTimeline.TIME_EVENT_TYPE -> 0
			!event.eventType.isNullOrBlank() -> 1
			else -> 2
		}
		Entry(time, label, priority)
	}.sortedWith(compareBy({ it.time.toInstant() }, { it.priority }))
		// One cursor position per instant. This does not remove events or icons from the Dashboard.
		.distinctBy { it.time.toInstant() }

	fun labelAt(events: List<DeviceStateEvent>, cursor: OffsetDateTime): String? = entries(events)
		.filter { Duration.between(it.time.toInstant(), cursor.toInstant()).abs() <= Duration.ofMinutes(15) }
		.minWithOrNull(compareBy({ Duration.between(it.time.toInstant(), cursor.toInstant()).abs() }, { it.priority }))?.label

	fun navigate(events: List<DeviceStateEvent>, cursor: OffsetDateTime, direction: NavigationDirection): OffsetDateTime? {
		val positions = entries(events)
		return when (direction) {
			NavigationDirection.PREVIOUS -> positions.lastOrNull { it.time.toInstant() < cursor.toInstant() }
			NavigationDirection.NEXT -> positions.firstOrNull { it.time.toInstant() > cursor.toInstant() }
		}?.time
	}

	private fun label(event: DeviceStateEvent): String? {
		if (listOf("Pinning", "Pining", "Closed Loop", "Close Loop").any { event.PumpControlState.equals(it, true) }) return null
		return when {
			event.eventType == CgmTimeline.TIME_EVENT_TYPE -> "TIME"
			event.eventType.equals("profile_changed", true) -> event.eventSubtype?.takeIf { it.isNotBlank() } ?: "Change profile"
			event.eventType.equals("pump_suspended", true) -> "Stop"
			event.eventType.equals("pump_resumed", true) -> "Restart"
			event.eventType.equals("cartridge_site_change", true) -> "Change set"
			event.eventType.equals("sensor_session_ended", true) -> "End Sensor"
			!event.eventLabel.isNullOrBlank() -> event.eventLabel
			event.CurrentUserMode.equals("exercise", true) || event.CurrentUserMode.equals("exercising", true) -> "EX"
			listOf("sleep", "sleeping", "night", "notte").any { event.CurrentUserMode.equals(it, true) } -> "Sleep"
			event.PumpControlState.equals("No Control", true) -> "!"
			else -> event.CurrentUserMode ?: event.PumpControlState
		}
	}
}
