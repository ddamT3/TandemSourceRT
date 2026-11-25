package com.example.tandemapp.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyCacheMigrationTest {
	@get:Rule val temp = TemporaryFolder(File("build").apply { require(isDirectory || mkdirs()) })

	private val raw = """{"events":[{"eventCode":13},{"eventCode":399,"sequenceNumber":7,"sequenceGroup":2,"pumpDateTime":"synthetic","estimatedDateTime":"estimated","deviceAssignmentId":"test","eventProperties":{"currentGlucoseDisplayValue":100,"egvTimeStamp":123}}],"clockChanges":null,"unknown":{"x":[1,true,null]}}"""
	private fun point(index: Int) =
		"""{"time":"2008-01-01T00:02:03Z","value":100,"source":{"payloadIndex":$index,"eventIndex":1,"sequenceNumber":7,"sequenceGroup":2,"pumpDateTime":"synthetic","estimatedDateTime":"estimated","egvTimeStamp":123,"deviceAssignmentId":"test"}}"""
	private fun wrapper(payload: String, assignment: String) =
		"""{"rawPayload":$payload,"requestedAssignmentId":"$assignment"}"""
	private fun envelope(points: String, sources: String) =
		"""{"latestTimestamp":"2008-01-01T00:02:03Z","dataset":{"cgm":$points,"bolus":[{"time":"synthetic","insulin_delivered_u":1.0,"bolus_type":"test"}],"carbs":[{"time":"synthetic","carbs_g":2.0}],"iob":[{"time":"synthetic","iob":0.5}],"basal":[{"time":"synthetic","CommandedBasalRate":0.2}],"deviceStates":[{"time":"synthetic","eventLabel":"test"}],"supplementalEvents":[{"eventCode":13,"eventProperties":{"x":1}}],"pumpEventsSources":$sources,"unknown":[1,null]},"extra":true}"""

	@Test(timeout = 30000L) fun preservesAllSeriesAndOriginalReferences() {
		val second = raw.replace("\"clockChanges\":null,", "")
		val third = """{"events":[],"clockChanges":[{"synthetic":true}]}"""
		val payloads = listOf(raw, second, third)
		val input = envelope("[${point(0)},${point(0)},${point(1)}]",
			payloads.mapIndexed { index, value -> wrapper(value, "test_$index") }.joinToString(",", "[", "]"))
		val source = temp.newFile()
		source.writeText(input)
		val stage = File(temp.root, "stage")
		val descriptors = LegacyCacheMigration.prepare(source, stage)
		assertEquals(input, source.readText())
		assertEquals(3, descriptors.size)
		assertEquals(listOf(2L, 2L, 0L), descriptors.map { it.eventCount })
		assertEquals(JsonNull, descriptors[0].clockChanges)
		assertNull(descriptors[1].clockChanges)
		assertEquals(Json.parseToJsonElement(third).jsonObject["clockChanges"], descriptors[2].clockChanges)
		for ((index, descriptor) in descriptors.withIndex()) {
			assertEquals("test_$index", descriptor.requestedAssignmentId)
			assertEquals("payload_$index.json", descriptor.archiveReference)
			assertEquals(Json.parseToJsonElement(payloads[index]),
				Json.parseToJsonElement(File(stage, descriptor.archiveReference).readText()))
		}
		val original = Json.parseToJsonElement(input).jsonObject
		val staged = Json.parseToJsonElement(File(stage, "dataset.json").readText()).jsonObject
		assertEquals(original - "dataset", staged - "dataset")
		assertEquals(original.getValue("dataset").jsonObject - "pumpEventsSources",
			staged.getValue("dataset").jsonObject)
		assertEquals(setOf("dataset.json", "payload_0.json", "payload_1.json", "payload_2.json"),
			stage.list()!!.toSet())
	}

	@Test(timeout = 30000L) fun acceptsEmptySourcesAndCgm() {
		val input = envelope("[]", "[]")
		val source = temp.newFile()
		source.writeText(input)
		val stage = File(temp.root, "stage")
		assertTrue(LegacyCacheMigration.prepare(source, stage).isEmpty())
		assertEquals(input, source.readText())
		assertEquals(setOf("dataset.json"), stage.list()!!.toSet())
	}
}
