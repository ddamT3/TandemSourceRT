package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PumpPayloadDescriptorTest {
	@get:Rule
	val folder = TemporaryFolder()

	private val reference = "synthetic.json"
	private val payload = """{"events":[{"id":7,"unknown":{"x":true}},{"id":2},{"id":7,"unknown":{"x":true}}],"clockChanges":[{"synthetic":true}]}"""

	private fun write(value: String = payload): File =
		File(folder.root, reference).apply { writeText(value, Charsets.UTF_8) }

	private fun describe(maxBytes: Long = 1048576): PumpPayloadDescriptor =
		PumpPayloadAccess.describe(folder.root, reference, "synthetic", maxBytes)

	private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
		try {
			block()
		} catch (error: Throwable) {
			if (error is T) return
			throw error
		}
		fail("Expected rejection")
	}

	@Test
	fun descriptorAndOriginalOrder() {
		val file = write()
		val descriptor = describe()
		assertEquals("synthetic", descriptor.requestedAssignmentId)
		assertEquals(reference, descriptor.archiveReference)
		assertEquals(3L, descriptor.eventCount)
		assertEquals(file.length(), descriptor.archiveBytes)
		val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
			.joinToString("") { "%02x".format(it.toInt() and 255) }
		assertEquals(hash, descriptor.sha256)
		assertEquals(Json.parseToJsonElement("""[{"synthetic":true}]"""), descriptor.clockChanges)
		val first = PumpPayloadAccess.readEvent(folder.root, descriptor, 0)
		assertEquals(Json.parseToJsonElement("""{"id":7,"unknown":{"x":true}}"""), first)
		assertEquals(Json.parseToJsonElement("""{"id":2}"""),
			PumpPayloadAccess.readEvent(folder.root, descriptor, 1))
		assertEquals(first, PumpPayloadAccess.readEvent(folder.root, descriptor, 2))
		val encoded = Json.encodeToString(PumpPayloadDescriptor.serializer(), descriptor)
		assertFalse((Json.parseToJsonElement(encoded) as JsonObject).containsKey("events"))
		assertEquals(descriptor, Json.decodeFromString(PumpPayloadDescriptor.serializer(), encoded))
	}

	@Test
	fun absentAndExplicitNullClockRemainDistinct() {
		write("""{"events":[]}""")
		val absent = describe()
		assertNull(absent.clockChanges)
		assertEquals(0L, absent.eventCount)
		write("""{"events":[],"clockChanges":null}""")
		val explicit = describe()
		assertEquals(JsonNull, explicit.clockChanges)
		for (descriptor in listOf(absent, explicit)) {
			assertEquals(descriptor, Json.decodeFromString(PumpPayloadDescriptor.serializer(),
				Json.encodeToString(PumpPayloadDescriptor.serializer(), descriptor)))
		}
	}

	@Test
	fun rejectsInvalidIndices() {
		write()
		val descriptor = describe()
		rejects<IllegalArgumentException> { PumpPayloadAccess.readEvent(folder.root, descriptor, -1) }
		rejects<IllegalArgumentException> { PumpPayloadAccess.readEvent(folder.root, descriptor, 3) }
	}

	@Test
	fun rejectsSameSizeModification() {
		val file = write()
		val descriptor = describe()
		write(payload.replace("\"id\":2", "\"id\":3"))
		assertEquals(descriptor.archiveBytes, file.length())
		rejects<IOException> { PumpPayloadAccess.readEvent(folder.root, descriptor, 0) }
	}

	@Test
	fun rejectsSizeLimitAndGrowth() {
		val file = write()
		val descriptor = describe(file.length())
		rejects<IOException> { describe(file.length() - 1) }
		file.appendText(" ")
		rejects<IOException> { PumpPayloadAccess.readEvent(folder.root, descriptor, 0) }
	}

	@Test
	fun rejectsMissingAndUnsafeReferences() {
		rejects<IOException> { describe() }
		rejects<IllegalArgumentException> {
			PumpPayloadAccess.describe(folder.root, "../synthetic.json", null, 1000)
		}
	}

	@Test
	fun rejectsInvalidPayloads() {
		for (value in listOf("", "{}", """{"events":[{}]} trailing""",
			"""{"events":[{}""", """{"events":[null]}""")) {
			write(value)
			rejects<IOException> { describe() }
		}
	}

	@Test
	fun rejectsForgedDescriptorMetadata() {
		write()
		val descriptor = describe()
		rejects<IOException> {
			PumpPayloadAccess.readEvent(folder.root, PumpPayloadDescriptor.create(descriptor.requestedAssignmentId,
				descriptor.archiveReference, descriptor.clockChanges, 4,
				descriptor.archiveBytes, descriptor.sha256), 0)
		}
		rejects<IOException> {
			PumpPayloadAccess.readEvent(folder.root, PumpPayloadDescriptor.create(descriptor.requestedAssignmentId,
				descriptor.archiveReference, null, descriptor.eventCount,
				descriptor.archiveBytes, descriptor.sha256), 0)
		}
	}
}
