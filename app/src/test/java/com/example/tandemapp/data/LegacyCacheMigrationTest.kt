package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
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

	private fun rejected(input: String, expected: Class<out Exception>, message: String? = null,
		limits: LegacyCacheExtractor.Limits = LegacyCacheExtractor.Limits()) {
		val source = temp.newFile()
		source.writeText(input)
		val stage = File(temp.root, "stage")
		val marker = temp.newFile()
		marker.writeText("synthetic-version")
		try {
			LegacyCacheMigration.prepare(source, stage, limits)
			fail("Expected migration rejection")
		} catch (failure: Exception) {
			assertTrue("Unexpected exception type", expected.isInstance(failure))
			if (message != null) assertEquals(message, failure.message)
			assertFalse(stage.exists())
			assertEquals(input, source.readText())
			assertEquals("synthetic-version", marker.readText())
		}
	}

	@Test(timeout = 30000L) fun rejectsMissingRequiredSeries() {
		val input = envelope("[${point(0)}]", "[${wrapper(raw, "test")}]")
			.replace("\"carbs\":[{\"time\":\"synthetic\",\"carbs_g\":2.0}],", "")
		rejected(input, SerializationException::class.java)
	}

	@Test(timeout = 30000L) fun rejectsIncorrectCgmValue() {
		rejected(envelope("[${point(0).replace("\"value\":100", "\"value\":101")}]",
			"[${wrapper(raw, "test")}]"), IOException::class.java, "CGM provenance mismatch")
	}

	@Test(timeout = 30000L) fun rejectsInvalidPayloadReference() {
		rejected(envelope("[${point(1)}]", "[${wrapper(raw, "test")}]"),
			IOException::class.java, "Invalid CGM payload index")
	}

	@Test(timeout = 30000L) fun rejectsInvalidEventReference() {
		rejected(envelope("[${point(0).replace("\"eventIndex\":1", "\"eventIndex\":2")}]",
			"[${wrapper(raw, "test")}]"), IOException::class.java, "Invalid CGM event index")
	}

	@Test(timeout = 30000L) fun rejectsMissingProvenance() {
		rejected(envelope("""[{"time":"synthetic","value":100}]""", "[${wrapper(raw, "test")}]"),
			IOException::class.java, "Missing CGM provenance")
	}

	@Test(timeout = 30000L) fun propagatesExtractionBudgetFailure() {
		rejected(envelope("[${point(0)}]", "[${wrapper(raw, "test")}]"),
			IOException::class.java, "File output limit exceeded",
			LegacyCacheExtractor.Limits(maxDatasetBytes = 10))
	}

	@Test(timeout = 30000L) fun existingStageUntouched() {
		val source = temp.newFile()
		val input = envelope("[]", "[]")
		source.writeText(input)
		val stage = temp.newFolder()
		val sentinel = File(stage, "sentinel")
		sentinel.writeText("keep")
		try {
			LegacyCacheMigration.prepare(source, stage)
			fail("Expected existing stage rejection")
		} catch (expected: IllegalArgumentException) {
			assertEquals("keep", sentinel.readText())
			assertEquals(input, source.readText())
		}
	}
}
