package com.example.tandemapp.data

import com.example.tandemapp.model.CgmPoint
import com.example.tandemapp.model.CgmSourceMetadata
import com.example.tandemapp.model.DayDataset
import com.example.tandemapp.model.PumpEventsSource
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

internal fun reading(
	sequence: Int?, wall: String, estimated: String? = null, value: Int = 150,
	recovered: Boolean = false, group: Int? = 1, assignment: String? = "A"
): CgmPoint = CgmPoint("2026-10-25T${wall}Z", value, recovered, CgmSourceMetadata(
	payloadIndex = 0, eventIndex = sequence ?: 0, sequenceNumber = sequence, sequenceGroup = group,
	pumpDateTime = "2026-10-25T${wall}Z", estimatedDateTime = estimated?.let { "2026-10-25T${it}Z" },
	deviceAssignmentId = assignment,
	egvTimeStamp = JsonPrimitive(Instant.parse("2026-10-25T${wall}Z").epochSecond -
		Instant.parse("2008-01-01T00:00:00Z").epochSecond)
))

internal fun cgmDataset(vararg points: CgmPoint): DayDataset = DayDataset(
	cgm = points.toList(), bolus = emptyList(), carbs = emptyList(), iob = emptyList(),
	basal = emptyList(), deviceStates = emptyList(),
	pumpEventsSources = listOf(PumpEventsSource("A", Json.parseToJsonElement(
		"""{"events":${Json.encodeToString(points.toList())},"clockChanges":{"opaque":true}}"""
	) as kotlinx.serialization.json.JsonObject))
)

class CgmTimelineTest {
	private fun instant(time: String) = Instant.parse("2026-10-25T${time}Z")

	// Extracted from cgm_export.json, 25 October 2026. Only the existing assignment ID is anonymized.
	// Temporal fields, sequenceGroup, CGM data type and event 13 properties are unchanged.
	private fun octoberClockPayload(withNeighbours: Boolean = false): String {
		val records = mutableListOf(
			Json.parseToJsonElement("""{"deviceAssignmentId":"anonymized-pump","eventCode":399,"sequenceGroup":0,"sequenceNumber":494723,"pumpDateTime":"2026-10-25T08:02:49","eventProperties":{"cgmDataType":[0],"currentGlucoseDisplayValue":127,"egvTimeStamp":593769766},"estimatedDateTime":"2026-10-25T06:08:29Z"}"""),
			Json.parseToJsonElement("""{"deviceAssignmentId":"anonymized-pump","eventCode":399,"sequenceGroup":0,"sequenceNumber":494850,"pumpDateTime":"2026-10-25T08:02:44","eventProperties":{"cgmDataType":[0],"currentGlucoseDisplayValue":98,"egvTimeStamp":593769761},"estimatedDateTime":"2026-10-25T07:03:29Z"}""")
		)
		if (withNeighbours) records += listOf(
			Json.parseToJsonElement("""{"deviceAssignmentId":"anonymized-pump","eventCode":399,"sequenceGroup":0,"sequenceNumber":494703,"pumpDateTime":"2026-10-25T07:52:50","eventProperties":{"cgmDataType":[0],"currentGlucoseDisplayValue":132,"egvTimeStamp":593769167},"estimatedDateTime":"2026-10-25T05:58:30Z"}"""),
			Json.parseToJsonElement("""{"deviceAssignmentId":"anonymized-pump","eventCode":399,"sequenceGroup":0,"sequenceNumber":494754,"pumpDateTime":"2026-10-25T08:17:49","eventProperties":{"cgmDataType":[0],"currentGlucoseDisplayValue":119,"egvTimeStamp":593770666},"estimatedDateTime":"2026-10-25T06:23:29.034Z"}"""),
			Json.parseToJsonElement("""{"deviceAssignmentId":"anonymized-pump","eventCode":399,"sequenceGroup":0,"sequenceNumber":494764,"pumpDateTime":"2026-10-25T08:22:50","eventProperties":{"cgmDataType":[0],"currentGlucoseDisplayValue":117,"egvTimeStamp":593770966},"estimatedDateTime":"2026-10-25T06:28:30.034Z"}"""),
			Json.parseToJsonElement("""{"deviceAssignmentId":"anonymized-pump","eventCode":399,"sequenceGroup":0,"sequenceNumber":494839,"pumpDateTime":"2026-10-25T07:57:45","eventProperties":{"cgmDataType":[0],"currentGlucoseDisplayValue":103,"egvTimeStamp":593769462},"estimatedDateTime":"2026-10-25T06:58:30Z"}""")
		)
		val change = Json.parseToJsonElement("""{"deviceAssignmentId":"anonymized-pump","eventCode":13,"sequenceGroup":0,"sequenceNumber":494826,"pumpDateTime":"2026-10-25T07:53:00","eventProperties":{"timePrior":31684966,"timeAfter":28380000,"rawRtcTime":1482981511},"estimatedDateTime":"2026-10-25T06:53:45Z"}""")
		records += change
		return buildJsonObject {
			put("events", JsonArray(records.sortedBy { it.jsonObject.getValue("sequenceNumber").jsonPrimitive.int }))
			put("clockChanges", JsonArray(listOf(change)))
		}.toString()
	}

	@Test
	fun `real October acquisition timestamps normalize both samples three seconds before receipt`() {
		val dataset = PumpEventsAdapter.decode(octoberClockPayload())
		assertEquals(2, dataset.cgm.size)
		val old = dataset.cgm.single { it.source!!.sequenceNumber == 494723 }
		val newer = dataset.cgm.single { it.source!!.sequenceNumber == 494850 }
		assertEquals("2026-10-25T08:02:46Z", old.time)
		assertEquals("2026-10-25T08:02:41Z", newer.time)
		assertEquals(127, old.value)
		assertEquals(98, newer.value)
		for (point in dataset.cgm) {
			val pump = java.time.LocalDateTime.parse(point.source!!.pumpDateTime).toInstant(java.time.ZoneOffset.UTC)
			assertEquals(3L, Duration.between(Instant.parse(point.time), pump).seconds)
			assertEquals(0, point.source!!.sequenceGroup)
			assertFalse(point.is_recovered)
		}
	}

	@Test
	fun `real October rollback keeps newer reading and exact TIME while preserving original samples`() {
		val dataset = PumpEventsAdapter.decode(octoberClockPayload())
		val original = Json.encodeToString(dataset)
		val timeline = CgmTimelineResolver.resolve(dataset)
		assertEquals(listOf(98), timeline.points.map { it.point.value })
		assertEquals(98.0, timeline.valueAt(instant("08:02:41"))!!, 0.0)
		assertNull(timeline.valueAt(instant("08:02:43")))
		val event = timeline.timeEvents.single()
		assertEquals("2026-10-25T07:53:00Z", event.time)
		assertEquals(13, event.sourceEventId)
		assertTrue(event.eventSubtype!!.contains("08:48:04.966"))
		assertTrue(event.eventSubtype!!.contains("07:53:00"))
		assertTrue(event.eventSubtype!!.contains("-00:55:04.966"))
		assertEquals(original, Json.encodeToString(dataset))
		assertEquals(2, dataset.cgm.size)
	}

	@Test
	fun `real neighbours do not bridge rollback or turn receipt latency into extra TIME events`() {
		val dataset = PumpEventsAdapter.decode(octoberClockPayload(withNeighbours = true))
		val original = Json.encodeToString(dataset)
		val timeline = CgmTimelineResolver.resolve(dataset)
		assertEquals(listOf(132, 103, 98), timeline.points.map { it.point.value })
		assertFalse(CgmTimeline.canConnect(timeline.points[0], timeline.points[1]))
		assertNull(timeline.valueAt(instant("07:54:00")))
		assertTrue(CgmTimeline.canConnect(timeline.points[1], timeline.points[2]))
		assertEquals(1, timeline.timeEvents.size)
		assertEquals(original, Json.encodeToString(dataset))
		assertEquals(6, dataset.cgm.size)
	}

	@Test
	fun `rollback replaces the full overlap without deleting any originals`() {
		val original = cgmDataset(
			reading(1, "01:55:00", "00:55:00", 100), reading(2, "02:00:00", "01:00:00", 110),
			reading(3, "02:05:00", "01:05:00", 120), reading(4, "02:10:00", "01:10:00", 130),
			reading(5, "02:15:00", "01:15:00", 140), reading(6, "02:00:00", "01:20:00", 180),
			reading(7, "02:05:00", "01:25:00", 200)
		)
		val before = Json.encodeToString(original)
		val timeline = CgmTimelineResolver.resolve(original)
		assertEquals(listOf(100, 180, 200), timeline.points.map { it.point.value })
		assertNull(timeline.valueAt(instant("02:10:00")))
		assertEquals(190.0, timeline.valueAt(instant("02:02:30"))!!, 0.001)
		assertFalse(CgmTimeline.canConnect(timeline.points[0], timeline.points[1]))
		assertEquals(before, Json.encodeToString(original))
		assertEquals(7, original.cgm.size)
	}

	@Test
	fun `short forward jump is blank for both rendering and cursor`() {
		val timeline = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "01:55:00", "00:55:00", 100), reading(2, "02:00:00", "01:00:00", 120),
			reading(3, "02:10:00", "01:05:00", 180), reading(4, "02:15:00", "01:10:00", 200)
		))
		assertEquals(4, timeline.points.size)
		assertTrue(CgmTimeline.canConnect(timeline.points[0], timeline.points[1]))
		assertFalse(CgmTimeline.canConnect(timeline.points[1], timeline.points[2]))
		assertTrue(CgmTimeline.canConnect(timeline.points[2], timeline.points[3]))
		assertNull(timeline.valueAt(instant("02:05:00")))
		assertEquals(120.0, timeline.valueAt(instant("02:00:00"))!!, 0.0)
		assertEquals(180.0, timeline.valueAt(instant("02:10:00"))!!, 0.0)
		assertEquals(110.0, timeline.valueAt(instant("01:57:30"))!!, 0.001)
	}

	@Test
	fun `backward sequence detection also works without estimated time`() {
		val timeline = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:00:00", value = 100), reading(2, "02:05:00", value = 110),
			reading(3, "02:00:00", value = 180), reading(4, "02:05:00", value = 190)
		))
		assertEquals(listOf(180, 190), timeline.points.map { it.point.value })
	}

	@Test
	fun `recovered reception sequence cannot manufacture a rollback or override new readings`() {
		val recovered = reading(999, "01:00:00", "01:10:00", 90, recovered = true).let {
			it.copy(source = requireNotNull(it.source).copy(
				pumpDateTime = "2026-10-25T02:10:00Z", egvTimeStamp = JsonPrimitive(123)
			))
		}
		val timeline = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:00:00", "01:00:00", 150), reading(2, "02:05:00", "01:05:00", 160), recovered
		))
		assertEquals(listOf(90, 150, 160), timeline.points.map { it.point.value })
		assertTrue(CgmTimeline.canConnect(timeline.points[1], timeline.points[2]))
		assertFalse(CgmTimeline.canConnect(timeline.points[0], timeline.points[1]))
		assertEquals(155.0, timeline.valueAt(instant("02:02:30"))!!, 0.001)
		val overlap = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:00:00", value = 100), reading(2, "02:05:00", value = 110),
			reading(3, "02:00:00", value = 180), reading(4, "02:05:00", value = 190),
			reading(999, "02:00:00", value = 80, recovered = true)
		))
		assertEquals(listOf(180, 190), overlap.points.map { it.point.value })
	}

	@Test
	fun `missing or conflicting sequences do not establish precedence or interpolation`() {
		val missing = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:00:00"), reading(null, "02:05:00"), reading(2, "02:10:00")
		))
		assertEquals(3, missing.points.size)
		assertNull(missing.valueAt(instant("02:02:30")))
		assertNull(missing.valueAt(instant("02:07:30")))
		val conflicting = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:00:00"), reading(1, "02:05:00"), reading(2, "01:55:00")
		))
		assertEquals(3, conflicting.points.size)
		assertTrue(conflicting.points.zipWithNext().none { (a, b) -> CgmTimeline.canConnect(a, b) })
		val sameTime = cgmDataset(reading(null, "02:00:00", value = 100), reading(null, "02:00:00", value = 200))
		assertTrue(CgmTimelineResolver.resolve(sameTime).points.isEmpty())
		assertEquals(2, sameTime.cgm.size)
	}

	@Test
	fun `groups and pumps are not ordered against each other`() {
		val timeline = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:00:00", group = 1), reading(2, "02:05:00", group = 1),
			reading(1, "01:55:00", group = 2), reading(1, "02:10:00", assignment = "B")
		))
		assertEquals(4, timeline.points.size)
		assertNull(timeline.valueAt(instant("01:57:30")))
		assertNull(timeline.valueAt(instant("02:07:30")))
	}

	@Test
	fun `non monotonic estimated time invalidates the candidate acquisition order`() {
		val original = cgmDataset(
			reading(1, "02:05:00", "01:05:00"), reading(2, "02:00:00", "01:00:00")
		)
		val timeline = CgmTimelineResolver.resolve(original)
		assertEquals(2, timeline.points.size)
		assertFalse(CgmTimeline.canConnect(timeline.points[0], timeline.points[1]))
	}

	@Test
	fun `missing elapsed reference cannot bridge a possible short forward jump`() {
		val timeline = CgmTimelineResolver.resolve(cgmDataset(
			reading(1, "02:00:00"), reading(2, "02:10:00")
		))
		assertEquals(2, timeline.points.size)
		assertFalse(CgmTimeline.canConnect(timeline.points[0], timeline.points[1]))
		assertNull(timeline.valueAt(instant("02:05:00")))
	}

	@Test
	fun `unorderable conflicting samples cannot be interpolated over after omission`() {
		val original = cgmDataset(
			reading(1, "02:00:00", "01:00:00", 100), reading(2, "02:10:00", "01:10:00", 120),
			reading(null, "02:05:00", value = 90), reading(null, "02:05:00", value = 200)
		)
		val timeline = CgmTimelineResolver.resolve(original)
		assertEquals(listOf(100, 120), timeline.points.map { it.point.value })
		assertFalse(CgmTimeline.canConnect(timeline.points[0], timeline.points[1]))
		assertNull(timeline.valueAt(instant("02:05:00")))
		assertEquals(4, original.cgm.size)
	}

	@Test
	fun `multiple backward and forward changes keep only the latest acquisition in each overlap`() {
		val original = cgmDataset(
			reading(1, "01:55:00", "00:55:00", 100), reading(2, "02:00:00", "01:00:00", 110),
			reading(3, "02:05:00", "01:05:00", 120), reading(4, "01:55:00", "01:10:00", 180),
			reading(5, "02:00:00", "01:15:00", 190), reading(6, "02:10:00", "01:20:00", 200),
			reading(7, "02:00:00", "01:25:00", 210), reading(8, "02:05:00", "01:30:00", 220)
		)
		val timeline = CgmTimelineResolver.resolve(original)
		assertEquals(listOf(180, 210, 220), timeline.points.map { it.point.value })
		assertEquals(215.0, timeline.valueAt(instant("02:02:30"))!!, 0.001)
		assertEquals(8, original.cgm.size)
	}

	@Test
	fun `receipt time fallback without acquisition timestamp cannot validate a rollback`() {
		val original = cgmDataset(*listOf(
			reading(1, "02:05:00", value = 100), reading(2, "02:00:00", value = 180)
		).map { it.copy(source = requireNotNull(it.source).copy(egvTimeStamp = null)) }.toTypedArray())
		val timeline = CgmTimelineResolver.resolve(original)
		assertEquals(listOf(180, 100), timeline.points.map { it.point.value })
		assertFalse(CgmTimeline.canConnect(timeline.points[0], timeline.points[1]))
		assertEquals(2, original.cgm.size)
	}

	@Test
	fun `long gaps and legacy samples never get a synthetic connection`() {
		val longGap = CgmTimelineResolver.resolve(cgmDataset(reading(1, "02:00:00"), reading(2, "02:20:00")))
		assertNull(longGap.valueAt(instant("02:10:00")))
		val legacy = CgmTimelineResolver.resolve(cgmDataset(
			CgmPoint("2026-10-25T02:00:00Z", 100), CgmPoint("2026-10-25T02:05:00Z", 120)
		))
		assertEquals(2, legacy.points.size)
		assertNull(legacy.valueAt(instant("02:02:30")))
	}
}
