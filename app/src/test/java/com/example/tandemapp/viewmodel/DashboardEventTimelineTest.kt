package com.example.tandemapp.viewmodel

import com.example.tandemapp.data.CgmTimeline
import com.example.tandemapp.model.DeviceStateEvent
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class DashboardEventTimelineTest {
	private fun time(value: String) = OffsetDateTime.parse("2026-10-25T${value}Z")
	private fun clock(value: String, detail: String = value) = DeviceStateEvent(
		time(value).toString(), eventType = CgmTimeline.TIME_EVENT_TYPE, eventLabel = "TIME", eventSubtype = detail
	)
	private fun sleep(value: String) = DeviceStateEvent(time(value).toString(), CurrentUserMode = "Sleep")

	@Test
	fun `TIME wins over Sleep at the same cursor instant regardless of input order`() {
		val clock = clock("07:53:00")
		val sleep = sleep("07:53:00")
		assertEquals("TIME", DashboardEventTimeline.labelAt(listOf(sleep, clock), time("07:53:00")))
		assertEquals("TIME", DashboardEventTimeline.labelAt(listOf(clock, sleep), time("07:53:00")))
	}

	@Test
	fun `subsecond proximity cannot hide TIME exactly under the cursor`() {
		val events = listOf(sleep("07:52:59.750"), clock("07:53:00"))
		assertEquals("TIME", DashboardEventTimeline.labelAt(events, time("07:53:00")))
	}

	@Test
	fun `Event arrows move strictly backwards and forwards without returning the same instant`() {
		val events = listOf(sleep("08:00:00"), sleep("07:53:00"), clock("07:53:00"), clock("05:40:00"))
		assertEquals(time("05:40:00"), DashboardEventTimeline.navigate(events, time("07:53:00"), NavigationDirection.PREVIOUS))
		assertEquals(time("08:00:00"), DashboardEventTimeline.navigate(events, time("07:53:00"), NavigationDirection.NEXT))
		assertEquals(time("07:53:00"), DashboardEventTimeline.navigate(events, time("08:00:00"), NavigationDirection.PREVIOUS))
		assertEquals(time("05:40:00"), DashboardEventTimeline.navigate(events, time("06:30:00"), NavigationDirection.PREVIOUS))
		assertEquals(time("07:53:00"), DashboardEventTimeline.navigate(events, time("06:30:00"), NavigationDirection.NEXT))
		assertNull(DashboardEventTimeline.navigate(events, time("05:40:00"), NavigationDirection.PREVIOUS))
		assertNull(DashboardEventTimeline.navigate(events, time("08:00:00"), NavigationDirection.NEXT))
	}

	@Test
	fun `both TIME positions and their original touch details remain intact`() {
		val first = clock("05:40:00", "First clock change details")
		val second = clock("07:53:00", "Second clock change details")
		val events = listOf(first, second)
		val forward = DashboardEventTimeline.navigate(events, time("05:40:00"), NavigationDirection.NEXT)
		assertEquals(time("07:53:00"), forward)
		assertEquals("TIME", DashboardEventTimeline.labelAt(events, requireNotNull(forward)))
		val backward = DashboardEventTimeline.navigate(events, forward, NavigationDirection.PREVIOUS)
		assertEquals(time("05:40:00"), backward)
		assertEquals("TIME", DashboardEventTimeline.labelAt(events, requireNotNull(backward)))
		assertEquals(listOf(first, second), events)
		assertEquals(listOf("First clock change details", "Second clock change details"), events.map { it.eventSubtype })
	}

	@Test
	fun `equivalent offset timestamps share one cursor position and cannot block navigation`() {
		val equivalentSleep = DeviceStateEvent("2026-10-25T08:53:00+01:00", CurrentUserMode = "Sleep")
		val events = listOf(equivalentSleep, clock("07:53:00"), clock("05:40:00"))
		assertEquals("TIME", DashboardEventTimeline.labelAt(events, time("07:53:00")))
		assertEquals(time("05:40:00"), DashboardEventTimeline.navigate(events, time("07:53:00"), NavigationDirection.PREVIOUS))
	}

	@Test
	fun `selection and navigation ignore the same technical or unlabelled records`() {
		val events = listOf(clock("05:40:00"),
			DeviceStateEvent(time("07:00:00").toString(), PumpControlState = "Closed Loop", CurrentUserMode = "Sleep"),
			DeviceStateEvent("invalid", eventLabel = "Broken timestamp"),
			DeviceStateEvent(time("07:10:00").toString()), clock("07:53:00"))
		assertEquals(time("05:40:00"), DashboardEventTimeline.navigate(events, time("07:53:00"), NavigationDirection.PREVIOUS))
		assertNull(DashboardEventTimeline.labelAt(events, time("07:00:00")))
		assertEquals("TIME", DashboardEventTimeline.labelAt(events, time("07:53:00")))
	}
}
