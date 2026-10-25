package com.example.tandemapp.data

import com.example.tandemapp.model.DeviceStateEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TimeEventTest {
	@Test
	fun `forward TIME uses the new pump time and the clock correction excluding elapsed acquisition time`() {
		val original = cgmDataset(
			reading(1, "02:00:00", "01:00:00"), reading(2, "02:10:00", "01:05:00")
		)
		val before = Json.encodeToString(original)
		val timeline = CgmTimelineResolver.resolve(original)
		val event = timeline.timeEvents.single()
		assertEquals("2026-10-25T02:10:00Z", event.time)
		assertEquals(CgmTimeline.TIME_EVENT_TYPE, event.eventType)
		assertEquals("TIME", event.eventLabel)
		assertNull(event.sourceEventId)
		assertTrue(event.eventSubtype!!.contains("Previous observed pump time: 2026-10-25T02:00:00Z"))
		assertTrue(event.eventSubtype!!.contains("New observed pump time: 2026-10-25T02:10:00Z"))
		assertTrue(event.eventSubtype!!.contains("Clock difference: +00:05:00"))
		assertEquals(before, Json.encodeToString(original))
		assertFalse(CgmTimeline.canConnect(timeline.points[0], timeline.points[1]))
	}

	@Test
	fun `backward TIME reports negative clock correction instead of the difference between samples`() {
		val event = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:05:00", "01:05:00"), reading(2, "02:00:00", "01:10:00")
		)).timeEvents.single()
		assertEquals("2026-10-25T02:00:00Z", event.time)
		assertTrue(event.eventSubtype!!.contains("Previous observed pump time: 2026-10-25T02:05:00Z"))
		assertTrue(event.eventSubtype!!.contains("Clock difference: -00:10:00"))
		assertTrue(event.eventSubtype!!.contains("exact adjustment time is unavailable"))
	}

	@Test
	fun `validated rollback without elapsed reference does not invent a clock difference`() {
		val event = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:05:00"), reading(2, "02:00:00")
		)).timeEvents.single()
		assertTrue(event.eventSubtype!!.contains("Clock difference: Not available"))
	}

	@Test
	fun `normal sampling uncertain breaks recovered and missing acquisition metadata do not create TIME`() {
		val cases = listOf(
			cgmDataset(reading(1, "02:00:00", "01:00:00"), reading(2, "02:05:00", "01:05:00")),
			cgmDataset(reading(1, "02:00:00"), reading(2, "02:10:00")),
			cgmDataset(reading(1, "02:00:00"), reading(2, "02:00:00")),
			cgmDataset(reading(1, "02:05:00"), reading(999, "01:00:00", recovered = true)),
			cgmDataset(reading(null, "02:05:00"), reading(null, "02:00:00")),
			cgmDataset(reading(1, "02:05:00", group = null), reading(2, "02:00:00", group = null)),
			cgmDataset(reading(1, "02:05:00"), reading(1, "02:00:00")),
			cgmDataset(*listOf(reading(1, "02:05:00"), reading(2, "02:00:00")).map {
				it.copy(source = requireNotNull(it.source).copy(egvTimeStamp = null))
			}.toTypedArray())
		)
		cases.forEach { assertTrue(CgmTimelineResolver.resolve(it).timeEvents.isEmpty()) }
	}

	@Test
	fun `duplicate payloads produce a single TIME marker`() {
		val original = cgmDataset(reading(1, "02:05:00", "01:05:00"), reading(2, "02:00:00", "01:10:00"))
		val expected = CgmTimelineResolver.resolve(original).timeEvents
		val merged = PumpEventsRepository().merge(listOf(original, original))
		assertEquals(4, merged.cgm.size)
		assertEquals(expected, CgmTimelineResolver.resolve(merged).timeEvents)
		assertEquals(1, expected.size)
	}

	@Test
	fun `Dashboard retains other events unchanged and only uses currently validated TIME events`() {
		val timeline = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:05:00", "01:05:00"), reading(2, "02:00:00", "01:10:00")
		))
		val stop = DeviceStateEvent("2026-10-25T01:55:00Z", eventType = "pump_suspended", eventLabel = "Stop")
		val fake = DeviceStateEvent("2026-10-25T03:00:00Z", eventType = CgmTimeline.TIME_EVENT_TYPE, eventLabel = "TIME")
		val originals = listOf(stop, stop, timeline.timeEvents.single(), fake)
		val dashboard = timeline.dashboardEvents(originals)
		assertEquals(listOf(stop, stop) + timeline.timeEvents, dashboard)
		assertEquals(4, originals.size)
		assertEquals(listOf(stop, stop), CgmTimeline().dashboardEvents(originals))
	}

	@Test
	fun `distinct validated changes remain separate events`() {
		val timeline = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:00:00", "01:00:00"), reading(2, "02:10:00", "01:05:00"),
			reading(3, "02:00:00", "01:10:00")
		))
		assertEquals(2, timeline.timeEvents.size)
		assertEquals(listOf("2026-10-25T02:00:00Z", "2026-10-25T02:10:00Z"), timeline.timeEvents.map { it.time })
	}
}
