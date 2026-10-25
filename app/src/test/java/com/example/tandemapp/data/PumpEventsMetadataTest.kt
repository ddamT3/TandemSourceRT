package com.example.tandemapp.data

import com.example.tandemapp.model.CgmPoint
import com.example.tandemapp.model.DayDataset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpEventsMetadataTest {
	private val json = Json { ignoreUnknownKeys = true }

	@Test
	fun `clock changes and unknown source fields survive without a schema`() {
		val changes = listOf("[]", "null", "42", "{\"unknown\":[{\"nested\":true}]}")
		for (rawChanges in changes) {
			val payload = """{"events":[],"clockChanges":$rawChanges,"futureField":{"keep":true}}"""
			val dataset = PumpEventsAdapter.decode(payload, requestedAssignmentId = "request-A")
			val source = dataset.pumpEventsSources.single()
			assertEquals(json.parseToJsonElement(rawChanges), source.clockChanges)
			assertEquals(json.parseToJsonElement(payload), source.rawPayload)
			assertEquals("request-A", source.requestedAssignmentId)
			assertEquals(dataset, json.decodeFromString<DayDataset>(json.encodeToString(dataset)))
		}
		val typed = PumpEventsPayloadDto(clockChanges = json.parseToJsonElement("{\"opaque\":true}"))
		assertEquals(typed.clockChanges, PumpEventsAdapter.decode(typed).pumpEventsSources.single().clockChanges)
		assertNull(PumpEventsAdapter.decode("{}").pumpEventsSources.single().clockChanges)
	}

	@Test
	fun `recovered acquisition timestamps stay separate from response position and pump receipt time`() {
		val payload = """{"events":[
			{"eventCode":399,"sequenceGroup":2,"sequenceNumber":90,"deviceAssignmentId":"event-A",
			 "pumpDateTime":"2026-10-25T04:00:00","estimatedDateTime":"2026-10-25T03:00:00Z",
			 "eventProperties":{"egvTimeStamp":600,"currentGlucoseDisplayValue":160,"cgmDataType":0}},
			{"eventCode":399,"sequenceGroup":2,"sequenceNumber":90,"deviceAssignmentId":"event-A",
			 "pumpDateTime":"2026-10-25T04:00:00","estimatedDateTime":"2026-10-25T03:00:00Z",
			 "eventProperties":{"egvTimeStamp":300,"currentGlucoseDisplayValue":150,"cgmDataType":[1]}}
		]}"""
		val dataset = PumpEventsAdapter.decode(payload, "request-A")
		val (recovered, live) = dataset.cgm
		assertEquals(listOf("2008-01-01T00:05:00Z", "2008-01-01T00:10:00Z"), dataset.cgm.map { it.time })
		assertEquals(listOf(150, 160), dataset.cgm.map { it.value })
		assertTrue(recovered.is_recovered)
		assertFalse(live.is_recovered)
		assertEquals(1, recovered.source!!.eventIndex)
		assertEquals(0, live.source!!.eventIndex)
		val source = requireNotNull(recovered.source)
		assertEquals(JsonPrimitive(300), source.egvTimeStamp)
		assertEquals(90, source.sequenceNumber)
		assertEquals(2, source.sequenceGroup)
		assertEquals("2026-10-25T04:00:00", source.pumpDateTime)
		assertEquals("2026-10-25T03:00:00Z", source.estimatedDateTime)
		assertEquals("event-A", source.deviceAssignmentId)
		assertEquals(json.parseToJsonElement(payload), dataset.pumpEventsSources.single().rawPayload)
	}

	@Test
	fun `clock rollback does not rewrite sequence or original timestamps`() {
		val dataset = PumpEventsAdapter.decode("""{"events":[
			{"eventCode":399,"sequenceGroup":4,"sequenceNumber":10,"pumpDateTime":"2026-10-25T03:00:00",
			 "eventProperties":{"currentGlucoseDisplayValue":140}},
			{"eventCode":399,"sequenceGroup":4,"sequenceNumber":11,"pumpDateTime":"2026-10-25T02:00:00",
			 "eventProperties":{"currentGlucoseDisplayValue":150}}
		]}""")
		assertEquals(listOf(11, 10), dataset.cgm.map { it.source!!.sequenceNumber })
		assertEquals(listOf(1, 0), dataset.cgm.map { it.source!!.eventIndex })
		assertEquals(listOf("2026-10-25T02:00:00", "2026-10-25T03:00:00"), dataset.cgm.map { it.source!!.pumpDateTime })
		assertTrue(dataset.cgm.all { it.source!!.egvTimeStamp == null })
	}

	@Test
	fun `equal acquisition timestamps retain previous display order and original provenance`() {
		val dataset = PumpEventsAdapter.decode("""{"events":[
			{"eventCode":399,"sequenceNumber":20,"pumpDateTime":"2026-10-25T04:00:00Z",
			 "eventProperties":{"egvTimeStamp":600,"currentGlucoseDisplayValue":160}},
			{"eventCode":399,"sequenceNumber":10,"pumpDateTime":"2026-10-25T03:00:00Z",
			 "eventProperties":{"egvTimeStamp":600,"currentGlucoseDisplayValue":150}}
		]}""")
		assertEquals(listOf(150, 160), dataset.cgm.map { it.value })
		assertEquals(listOf(1, 0), dataset.cgm.map { it.source!!.eventIndex })
		assertEquals(listOf(10, 20), dataset.cgm.map { it.source!!.sequenceNumber })
	}

	@Test
	fun `legacy events distinguish absent sequences from explicit zero`() {
		val dataset = PumpEventsAdapter.decode("""{"events":[
			{"eventCode":399,"estimatedDateTime":"2026-10-25T02:00:00Z",
			 "eventProperties":{"currentGlucoseDisplayValue":140}},
			{"eventCode":399,"sequenceGroup":0,"sequenceNumber":0,"pumpDateTime":"2026-10-25T03:00:00Z",
			 "eventProperties":{"egvTimeStamp":0,"currentGlucoseDisplayValue":150}}
		]}""")
		val missing = dataset.cgm.first().source!!
		val zero = dataset.cgm.last().source!!
		assertNull(missing.sequenceNumber)
		assertNull(missing.sequenceGroup)
		assertNull(missing.pumpDateTime)
		assertEquals("2026-10-25T02:00:00Z", missing.estimatedDateTime)
		assertEquals(0, zero.sequenceNumber)
		assertEquals(0, zero.sequenceGroup)
		assertEquals(JsonPrimitive(0), zero.egvTimeStamp)
	}

	@Test
	fun `merge preserves every CGM occurrence and rebases source references across assignments`() {
		val event = """{"eventCode":399,"deviceAssignmentId":"event-A","sequenceNumber":7,
			"pumpDateTime":"2026-10-25T02:00:00Z","eventProperties":{"currentGlucoseDisplayValue":150}}"""
		val incomplete = """{"eventCode":399,"eventProperties":{"unknown":"retained"}}"""
		val payload = """{"events":[$event,$event,$incomplete],"clockChanges":{"opaque":true}}"""
		val first = PumpEventsAdapter.decode(payload, "request-A")
		val second = PumpEventsAdapter.decode(payload, "request-B")
		val repository = PumpEventsRepository()
		val merged = repository.merge(listOf(first, second))
		assertEquals(4, merged.cgm.size)
		assertEquals(listOf(150, 150, 150, 150), merged.cgm.map { it.value })
		assertEquals(listOf(0, 0, 1, 1), merged.cgm.map { it.source!!.payloadIndex })
		assertEquals(listOf(0, 1, 0, 1), merged.cgm.map { it.source!!.eventIndex })
		assertEquals(listOf("request-A", "request-B"), merged.pumpEventsSources.map { it.requestedAssignmentId })
		assertTrue(merged.cgm.all { it.source!!.deviceAssignmentId == "event-A" })
		assertTrue(merged.pumpEventsSources.all { it.rawPayload == json.parseToJsonElement(payload) })
		assertEquals(merged, json.decodeFromString<DayDataset>(json.encodeToString(merged)))
		val nestedMerge = repository.merge(listOf(first, merged))
		assertEquals(listOf(0, 0, 1, 1, 2, 2), nestedMerge.cgm.map { it.source!!.payloadIndex })
		assertEquals(first, PumpEventsAdapter.decode(payload, "request-A"))
	}

	@Test
	fun `previous normalized datasets still deserialize without metadata`() {
		val legacy = """{"cgm":[{"time":"2026-10-25T02:00:00Z","value":150}],
			"bolus":[],"carbs":[],"iob":[],"basal":[],"deviceStates":[]}"""
		val dataset = json.decodeFromString<DayDataset>(legacy)
		assertEquals(CgmPoint("2026-10-25T02:00:00Z", 150), dataset.cgm.single())
		assertTrue(dataset.pumpEventsSources.isEmpty())
		val merged = PumpEventsRepository().merge(listOf(dataset, dataset))
		assertEquals(2, merged.cgm.size)
		assertTrue(merged.cgm.all { it.source == null })
	}
}
