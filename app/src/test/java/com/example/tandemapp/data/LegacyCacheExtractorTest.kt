package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyCacheExtractorTest {
	@get:Rule val temp = TemporaryFolder(File("build").apply { require(isDirectory || mkdirs()) })

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
	private fun budgetRejected(input: String, limits: LegacyCacheExtractor.Limits, message: String) {
		val source = temp.newFile()
		source.writeText(input)
		val stage = File(temp.root, "budget-stage")
		try {
			LegacyCacheExtractor.extract(source, stage, limits)
			fail("Expected budget rejection")
		} catch (expected: IOException) {
			assertEquals(message, expected.message)
			assertFalse(stage.exists())
			assertEquals(input, source.readText())
		}
	}

	private fun budgetAccepted(input: String, limits: LegacyCacheExtractor.Limits) {
		val source = temp.newFile()
		source.writeText(input)
		val stage = temp.newFolder()
		val destination = File(stage, "output")
		LegacyCacheExtractor.extract(source, destination, limits)
		assertTrue(File(destination, "dataset.json").isFile)
		assertEquals(input, source.readText())
	}

	@Test fun datasetByteBudget() {
		val input = envelope("[]")
		budgetRejected(input, LegacyCacheExtractor.Limits(maxDatasetBytes = 10), "File output limit exceeded")
	}

	@Test fun aggregateOutputBudget() {
		budgetRejected(envelope("[${wrapper()},${wrapper()}]"),
			LegacyCacheExtractor.Limits(maxOutputBytes = payload.toByteArray().size.toLong()),
			"Aggregate output limit exceeded")
	}

	@Test fun diskReserveBudget() {
		budgetRejected(envelope("[]"), LegacyCacheExtractor.Limits(reserveDiskBytes = Long.MAX_VALUE),
			"Insufficient staging disk space")
	}

	@Test fun aggregateMetadataBudget() {
		val input = envelope("[${wrapper()},${wrapper()}]")
		budgetAccepted(input, LegacyCacheExtractor.Limits(maxRetainedMetadataBytes = 26))
		budgetRejected(input, LegacyCacheExtractor.Limits(maxRetainedMetadataBytes = 25),
			"Serialized JSON element limit exceeded")
	}

	@Test fun datasetNodeBudget() {
		val input = """{"latestTimestamp":"synthetic","dataset":{"cgm":[]}}"""
		budgetAccepted(input, LegacyCacheExtractor.Limits(maxDatasetNodes = 4))
		budgetRejected(input, LegacyCacheExtractor.Limits(maxDatasetNodes = 3), "Dataset node limit exceeded")
	}

	@Test fun cgmPointBudget() {
		val input = """{"latestTimestamp":"synthetic","dataset":{"cgm":[{},{}]}}"""
		budgetAccepted(input, LegacyCacheExtractor.Limits(maxCgmPoints = 2))
		budgetRejected(input, LegacyCacheExtractor.Limits(maxCgmPoints = 1), "CGM point limit exceeded")
	}

	@Test fun payloadByteBoundary() {
		val bytes = payload.toByteArray().size.toLong()
		val input = envelope("[${wrapper()}]")
		budgetAccepted(input, LegacyCacheExtractor.Limits(maxPayloadBytes = bytes))
		budgetRejected(input, LegacyCacheExtractor.Limits(maxPayloadBytes = bytes - 1), "File output limit exceeded")
	}

}
