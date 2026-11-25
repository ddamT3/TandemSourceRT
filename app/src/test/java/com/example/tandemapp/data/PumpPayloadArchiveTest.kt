package com.example.tandemapp.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PumpPayloadArchiveTest {
	@get:Rule
	val temporary = TemporaryFolder()

	private fun target() = File(temporary.root, "payload.json")

	private fun hash(bytes: ByteArray): String =
		MessageDigest.getInstance("SHA-256")
			.digest(bytes)
			.joinToString("") {
				(it.toInt() and 0xff).toString(16).padStart(2, '0')
			}

	private fun expectIoFailure(block: () -> Unit) {
		try {
			block()
			fail("Expected IOException")
		} catch (_: IOException) {
			// Expected.
		}
	}

	private fun assertNoTemporaryFiles() {
		assertTrue(
			temporary.root.listFiles()!!.none {
				it.name.startsWith(".payload-")
			}
		)
	}

	@Test
	fun preservesOriginalBytes() {
		val bytes = """{"events":[],"unknown":{"x":[1,true,null]}}"""
			.toByteArray(Charsets.UTF_8)

		val result = PumpPayloadArchive.archive(
			ByteArrayInputStream(bytes), target(), 1024
		)

		assertArrayEquals(bytes, target().readBytes())
		assertEquals(bytes.size.toLong(), result.bytes)
		assertEquals(hash(bytes), result.sha256)
		assertNoTemporaryFiles()
	}

	@Test
	fun preservesNonAsciiAndWhitespace() {
		val bytes = "{\r\n \"value\":\"\u00e8\" \r\n}\n"
			.toByteArray(Charsets.UTF_8)

		PumpPayloadArchive.archive(
			ByteArrayInputStream(bytes), target(), 1024
		)

		assertArrayEquals(bytes, target().readBytes())
	}

	@Test
	fun acceptsExactSizeLimit() {
		val bytes = ByteArray(100) { it.toByte() }

		val result = PumpPayloadArchive.archive(
			ByteArrayInputStream(bytes), target(), 100
		)

		assertEquals(100L, result.bytes)
		assertArrayEquals(bytes, target().readBytes())
	}

	@Test
	fun rejectsSizeLimitAndPreservesPreviousFile() {
		target().writeText("previous")

		expectIoFailure {
			PumpPayloadArchive.archive(
				ByteArrayInputStream(ByteArray(101)),
				target(),
				100
			)
		}

		assertEquals("previous", target().readText())
		assertNoTemporaryFiles()
	}

	@Test
	fun rejectsEmptyInput() {
		expectIoFailure {
			PumpPayloadArchive.archive(
				ByteArrayInputStream(ByteArray(0)),
				target(),
				1024
			)
		}

		assertFalse(target().exists())
		assertNoTemporaryFiles()
	}

	@Test
	fun preservesPreviousFileOnInputFailure() {
		target().writeText("previous")

		val input = object : InputStream() {
			private var calls = 0

			override fun read(): Int =
				throw IOException("Unexpected single byte read")

			override fun read(
				buffer: ByteArray,
				offset: Int,
				length: Int
			): Int {
				if (calls++ > 0) {
					throw IOException("Synthetic interruption")
				}

				buffer[offset] = 42
				return 1
			}
		}

		expectIoFailure {
			PumpPayloadArchive.archive(input, target(), 1024)
		}

		assertEquals("previous", target().readText())
		assertNoTemporaryFiles()
	}

	@Test
	fun preservesPreviousFileOnReplacementFailure() {
		target().writeText("previous")

		expectIoFailure {
			PumpPayloadArchive.archive(
				ByteArrayInputStream(byteArrayOf(1, 2, 3)),
				target(),
				1024
			) { _, _ ->
				throw IOException("Synthetic replacement failure")
			}
		}

		assertEquals("previous", target().readText())
		assertNoTemporaryFiles()
	}

	@Test
	fun replacesPreviousFileOnSuccess() {
		target().writeText("previous")
		val bytes = byteArrayOf(1, 2, 3)

		PumpPayloadArchive.archive(
			ByteArrayInputStream(bytes), target(), 1024
		)

		assertArrayEquals(bytes, target().readBytes())
		assertNoTemporaryFiles()
	}

	@Test
	fun rejectsInputWithoutProgress() {
		val input = object : InputStream() {
			override fun read(): Int = 0

			override fun read(
				buffer: ByteArray,
				offset: Int,
				length: Int
			): Int = 0
		}

		expectIoFailure {
			PumpPayloadArchive.archive(input, target(), 1024)
		}

		assertFalse(target().exists())
		assertNoTemporaryFiles()
	}

	@Test
	fun doesNotCloseCallerInput() {
		var closed = false

		val input = object : ByteArrayInputStream(
			byteArrayOf(1, 2, 3)
		) {
			override fun close() {
				closed = true
			}
		}

		PumpPayloadArchive.archive(input, target(), 1024)

		assertFalse(closed)
		input.close()
		assertTrue(closed)
	}

	@Test
	fun archivesLargeGeneratedInputWithBoundedReadBuffer() {
		val size = 32L * 1024L * 1024L
		var generated = 0L
		var largestRequest = 0
		val expectedDigest = MessageDigest.getInstance("SHA-256")

		val input = object : InputStream() {
			override fun read(): Int =
				throw IOException("Unexpected single byte read")

			override fun read(
				buffer: ByteArray,
				offset: Int,
				length: Int
			): Int {
				largestRequest = maxOf(largestRequest, length)

				assertTrue(
					buffer.size <= PumpPayloadArchive.BUFFER_BYTES
				)

				if (generated == size) return -1

				val count = minOf(
					length.toLong(), size - generated
				).toInt()

				for (i in 0 until count) {
					buffer[offset + i] =
						((generated + i) % 251).toByte()
				}

				expectedDigest.update(buffer, offset, count)
				generated += count
				return count
			}
		}

		val result = PumpPayloadArchive.archive(
			input, target(), size
		)

		val expectedHash = expectedDigest.digest().joinToString("") {
			(it.toInt() and 0xff).toString(16).padStart(2, '0')
		}

		assertEquals(size, result.bytes)
		assertEquals(size, target().length())
		assertEquals(expectedHash, result.sha256)
		assertTrue(largestRequest <= PumpPayloadArchive.BUFFER_BYTES)

		val actualDigest = MessageDigest.getInstance("SHA-256")
		val buffer = ByteArray(8192)

		target().inputStream().use { stream ->
			while (true) {
				val count = stream.read(buffer)
				if (count < 0) break
				actualDigest.update(buffer, 0, count)
			}
		}

		val actualHash = actualDigest.digest().joinToString("") {
			(it.toInt() and 0xff).toString(16).padStart(2, '0')
		}

		assertEquals(expectedHash, actualHash)
		assertNoTemporaryFiles()
	}
}
