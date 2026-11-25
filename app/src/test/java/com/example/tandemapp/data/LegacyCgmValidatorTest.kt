package com.example.tandemapp.data

import com.example.tandemapp.model.CgmPoint
import com.example.tandemapp.model.CgmSourceMetadata
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyCgmValidatorTest {
	@get:Rule val temp = TemporaryFolder()

	private val raw = """{"events":[{"eventCode":13},{"eventCode":399,"sequenceNumber":7,"sequenceGroup":2,"pumpDateTime":"synthetic","estimatedDateTime":"estimated","deviceAssignmentId":"test","eventProperties":{"currentGlucoseDisplayValue":100,"egvTimeStamp":123}}]}"""
	private val source = CgmSourceMetadata(0, 1, 7, 2, "synthetic", "estimated", JsonPrimitive(123), "test")
	private val point = CgmPoint("synthetic", 100, source = source)

	private fun archive(index: Int = 0): PumpPayloadDescriptor {
		val name = "payload_${index}.json"
		File(temp.root, name).writeText(raw)
		return PumpPayloadAccess.describe(temp.root, name, "test", 10000)
	}

	private fun reject(block: () -> Unit) {
		try {
			block()
			fail("Expected rejection")
		} catch (expected: IOException) {
			// Expected validation failure.
		}
	}

	@Test fun validAndDuplicatePoints() {
		val descriptor = archive()
		LegacyCgmValidator.validate(temp.root, listOf(descriptor), listOf(point, point))
	}

	@Test fun multiplePayloads() {
		val descriptors = listOf(archive(), archive(1))
		LegacyCgmValidator.validate(temp.root, descriptors,
			listOf(point, point.copy(source = source.copy(payloadIndex = 1))))
	}

	@Test fun missingProvenance() {
		val descriptor = archive()
		reject { LegacyCgmValidator.validate(temp.root, listOf(descriptor), listOf(point.copy(source = null))) }
	}

	@Test fun invalidIndices() {
		val descriptor = archive()
		for (metadata in listOf(source.copy(payloadIndex = -1), source.copy(payloadIndex = 1),
			source.copy(eventIndex = -1), source.copy(eventIndex = 2), source.copy(eventIndex = 0))) {
			reject { LegacyCgmValidator.validate(temp.root, listOf(descriptor), listOf(point.copy(source = metadata))) }
		}
	}

	@Test fun incorrectValue() {
		val descriptor = archive()
		reject { LegacyCgmValidator.validate(temp.root, listOf(descriptor), listOf(point.copy(value = 101))) }
	}

	@Test fun incorrectMetadata() {
		val descriptor = archive()
		for (metadata in listOf(source.copy(sequenceNumber = 8), source.copy(sequenceGroup = 3),
			source.copy(pumpDateTime = null), source.copy(estimatedDateTime = null),
			source.copy(deviceAssignmentId = null), source.copy(egvTimeStamp = JsonPrimitive(124)))) {
			reject { LegacyCgmValidator.validate(temp.root, listOf(descriptor), listOf(point.copy(source = metadata))) }
		}
	}

	@Test fun sameSizeMutation() {
		val descriptor = archive()
		File(temp.root, descriptor.archiveReference).writeText(raw.replace("13}", "14}"))
		reject { LegacyCgmValidator.validate(temp.root, listOf(descriptor), listOf(point)) }
	}

	@Test fun unreferencedArchiveStillChecked() {
		val descriptors = listOf(archive(), archive(1))
		File(temp.root, descriptors[1].archiveReference).writeText(raw.replace("13}", "14}"))
		reject { LegacyCgmValidator.validate(temp.root, descriptors, listOf(point)) }
	}

	@Test fun missingArchive() {
		val descriptor = archive()
		assertTrue(File(temp.root, descriptor.archiveReference).delete())
		reject { LegacyCgmValidator.validate(temp.root, listOf(descriptor), listOf(point)) }
	}

	@Test fun pointLimit() {
		val descriptor = archive()
		try {
			LegacyCgmValidator.validate(temp.root, listOf(descriptor), listOf(point, point), maxPoints = 1)
			fail("Expected rejection")
		} catch (expected: IllegalArgumentException) {
			// Expected limit failure.
		}
	}
}
