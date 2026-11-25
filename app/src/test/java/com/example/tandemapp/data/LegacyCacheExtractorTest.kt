package com.example.tandemapp.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyCacheExtractorTest {
	@get:Rule val temp = TemporaryFolder()

	private val payload = """{"events":[{"eventCode":399,"x":1},{"eventCode":399,"x":1},{"eventCode":13,"x":2}],"clockChanges":null,"unknown":{"a":[true,null,"abc",1.25]}}"""
	private fun envelope(sources: String) =
		"""{"latestTimestamp":"synthetic","dataset":{"cgm":[{"time":"synthetic","value":100,"source":{"payloadIndex":0,"eventIndex":1}}],"bolus":[],"carbs":[],"iob":[],"basal":[],"deviceStates":[],"pumpEventsSources":$sources,"unknown":[1,null]},"extra":true}"""
	private fun wrapper(raw: String = payload) =
		"""{"rawPayload":$raw,"requestedAssignmentId":"synthetic"}"""

	@Test fun preservesLogicalContentAndOrder() {
		val source = temp.newFile()
		val original = envelope("[${wrapper()},${wrapper()}]")
		source.writeText(original)
		val stage = File(temp.root, "stage")
		val descriptors = LegacyCacheExtractor.extract(source, stage)
		assertEquals(2, descriptors.size)
		assertEquals(original, source.readText())
		for ((index, descriptor) in descriptors.withIndex()) {
			assertEquals("payload_${index}.json", descriptor.archiveReference)
			assertEquals("synthetic", descriptor.requestedAssignmentId)
			assertEquals(3L, descriptor.eventCount)
			assertEquals(JsonNull, descriptor.clockChanges)
			assertEquals(Json.parseToJsonElement(payload),
				Json.parseToJsonElement(File(stage, descriptor.archiveReference).readText()))
			assertEquals(Json.parseToJsonElement(payload).jsonObject.getValue("events").jsonArray[1],
				PumpPayloadAccess.readEvent(stage, descriptor, 1))
		}
		val root = Json.parseToJsonElement(original).jsonObject
		val actual = Json.parseToJsonElement(File(stage, "dataset.json").readText()).jsonObject
		assertEquals(root - "dataset", actual - "dataset")
		assertEquals(root.getValue("dataset").jsonObject - "pumpEventsSources",
			actual.getValue("dataset").jsonObject)
	}

	private fun rejected(input: String, limits: LegacyCacheExtractor.Limits = LegacyCacheExtractor.Limits()) {
		val source = temp.newFile()
		source.writeText(input)
		val stage = File(temp.root, "stage")
		try {
			LegacyCacheExtractor.extract(source, stage, limits)
			fail("Expected rejection")
		} catch (expected: Exception) {
			assertFalse(stage.exists())
			assertEquals(input, source.readText())
		}
	}

	@Test fun truncatedEnvelope() = rejected(envelope("[${wrapper()}]").dropLast(1))
	@Test fun trailingJson() = rejected(envelope("[${wrapper()}]") + "{}")
	@Test fun duplicateEventMember() = rejected(envelope("[${wrapper("""{"events":[{"x":1,"x":2}]}""")}]"))
	@Test fun missingEvents() = rejected(envelope("[${wrapper("""{"clockChanges":null}""")}]"))
	@Test fun sourceLimit() = rejected(envelope("[${wrapper()},${wrapper()}]"),
		LegacyCacheExtractor.Limits(maxSources = 1))
	@Test fun payloadLimit() = rejected(envelope("[${wrapper()}]"),
		LegacyCacheExtractor.Limits(maxPayloadBytes = 10))
	@Test fun inputLimit() = rejected(envelope("[${wrapper()}]"),
		LegacyCacheExtractor.Limits(maxInputBytes = 10))
	@Test fun tokenLimit() = rejected(envelope("[${wrapper()}]"),
		LegacyCacheExtractor.Limits(maxTokenChars = 8))
	@Test fun existingDestinationUntouched() {
		val source = temp.newFile()
		source.writeText(envelope("[]"))
		val stage = temp.newFolder()
		val sentinel = File(stage, "sentinel")
		sentinel.writeText("keep")
		try {
			LegacyCacheExtractor.extract(source, stage)
			fail("Expected rejection")
		} catch (expected: IllegalArgumentException) {
			assertEquals("keep", sentinel.readText())
		}
	}
}
