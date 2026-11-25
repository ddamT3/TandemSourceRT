package com.example.tandemapp.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashDiagnosticsWriterTest {

	@get:Rule
	val temporary = TemporaryFolder()

	private fun frame(
		index: Int = 1,
		className: String = "synthetic.TestClass",
		methodName: String = "testMethod"
	): StackTraceElement {
		return StackTraceElement(
			className,
			methodName,
			"SYNTHETIC_PRIVATE_FILE.kt",
			index
		)
	}

	private fun exception(
		frames: Int = 1,
		cause: Throwable? = null
	): Throwable {
		return IllegalStateException(
			"SYNTHETIC_PRIVATE_MESSAGE",
			cause
		).apply {
			stackTrace = Array(frames) { frame(it + 1) }
		}
	}

	private fun write(error: Throwable): String {
		CrashDiagnostics.record(temporary.root, error)

		val file = File(temporary.root, "latest_crash.txt")

		assertTrue(file.isFile)
		assertTrue(file.length() > 0L)
		assertTrue(file.length() <= 65536L)

		return file.readText(Charsets.UTF_8)
	}

	@Test
	fun writerHeader() {
		val report = write(exception())

		assertTrue(report.startsWith("FORMAT=1\n"))
		assertTrue(report.contains("WALL_TIME_MS="))
		assertTrue(report.contains("LAST_PHASE="))
		assertTrue(report.contains("JAVA_USED_BYTES="))
		assertTrue(report.contains("JAVA_COMMITTED_BYTES="))
		assertTrue(report.contains("JAVA_MAX_BYTES="))
		assertTrue(report.endsWith("RECORD_COMPLETE=YES\n"))
	}

	@Test
	fun writerSyntheticOom() {
		val error = OutOfMemoryError("SYNTHETIC_OOM")
		error.stackTrace = arrayOf(frame())

		val report = write(error)

		assertTrue(
			report.contains(
				"EXCEPTION=java.lang.OutOfMemoryError\n"
			)
		)
		assertFalse(report.contains("SYNTHETIC_OOM"))
	}

	@Test
	fun writerExcludesExceptionMessages() {
		val report = write(
			exception(cause = exception())
		)

		assertFalse(
			report.contains("SYNTHETIC_PRIVATE_MESSAGE")
		)
	}

	@Test
	fun writerExcludesSourceFileNames() {
		val report = write(exception())

		assertFalse(
			report.contains("SYNTHETIC_PRIVATE_FILE")
		)
	}

	@Test
	fun writerStackFrames() {
		val report = write(exception(frames = 3))

		assertTrue(
			report.contains(
				"FRAME=synthetic.TestClass.testMethod:1\n"
			)
		)

		assertEquals(
			3,
			report.lineSequence().count {
				it.startsWith("FRAME=")
			}
		)

		assertTrue(report.contains("FRAMES_TOTAL=3\n"))
		assertTrue(report.contains("FRAMES_WRITTEN=3\n"))
	}

	@Test
	fun writerNestedCauses() {
		val report = write(
			exception(
				cause = exception(
					cause = exception()
				)
			)
		)

		assertTrue(report.contains("CAUSE_DEPTH=0\n"))
		assertTrue(report.contains("CAUSE_DEPTH=1\n"))
		assertTrue(report.contains("CAUSE_DEPTH=2\n"))
		assertFalse(report.contains("CAUSE_DEPTH=3\n"))
		assertTrue(report.endsWith("RECORD_COMPLETE=YES\n"))
	}

	@Test
	fun writerFrameLimit() {
		val report = write(exception(frames = 100))

		assertEquals(
			64,
			report.lineSequence().count {
				it.startsWith("FRAME=")
			}
		)

		assertTrue(report.contains("FRAMES_TOTAL=100\n"))
		assertTrue(report.contains("FRAMES_WRITTEN=64\n"))
		assertTrue(report.endsWith("RECORD_COMPLETE=NO\n"))
	}

	@Test
	fun writerCauseLimit() {
		var error = exception()

		repeat(5) {
			error = exception(cause = error)
		}

		val report = write(error)

		assertEquals(
			4,
			report.lineSequence().count {
				it.startsWith("CAUSE_DEPTH=")
			}
		)

		assertFalse(report.contains("CAUSE_DEPTH=4\n"))
		assertTrue(report.endsWith("RECORD_COMPLETE=NO\n"))
	}

	@Test
	fun writerByteLimit() {
		var error: Throwable? = null

		repeat(4) {
			error = exception(cause = error).apply {
				stackTrace = Array(64) { index ->
					frame(
						index,
						"synthetic." + "A".repeat(240),
						"B".repeat(128)
					)
				}
			}
		}

		val report = write(requireNotNull(error))

		assertTrue(
			report.toByteArray(Charsets.UTF_8).size <= 65536
		)
		assertTrue(report.endsWith("RECORD_COMPLETE=NO\n"))
	}

	@Test
	fun writerActualFrameCounters() {
		var error: Throwable? = null

		repeat(4) {
			error = exception(cause = error).apply {
				stackTrace = Array(64) { index ->
					frame(
						index,
						"synthetic." + "A".repeat(240),
						"B".repeat(128)
					)
				}
			}
		}

		val report = write(requireNotNull(error))

		var actual = 0
		var declared = 0
		var counters = 0

		for (line in report.lineSequence()) {
			if (line.startsWith("FRAME=")) {
				actual++
			}

			if (line.startsWith("FRAMES_WRITTEN=")) {
				declared += line.substringAfter('=').toInt()
				counters++
			}
		}

		assertTrue(counters > 0)
		assertEquals(actual, declared)
		assertTrue(actual < 256)
	}

	@Test
	fun writerRejectsMalformedFrames() {
		val error = exception()

		error.stackTrace = arrayOf(
			frame(className = "A".repeat(257)),
			frame(methodName = "B".repeat(129)),
			frame(className = "synthetic.Bad\nTOKEN=TEST"),
			frame(index = 4)
		)

		val report = write(error)

		assertFalse(report.contains("TOKEN=TEST"))
		assertFalse(report.contains("A".repeat(257)))
		assertFalse(report.contains("B".repeat(129)))

		assertEquals(
			1,
			report.lineSequence().count {
				it.startsWith("FRAME=")
			}
		)

		assertTrue(report.contains("FRAMES_TOTAL=4\n"))
		assertTrue(report.contains("FRAMES_WRITTEN=1\n"))
		assertTrue(report.endsWith("RECORD_COMPLETE=NO\n"))
	}

	@Test
	fun writerSanitizerCompatibility() {
		val report = write(
			exception(
				frames = 3,
				cause = exception(frames = 2)
			)
		)

		val sanitized = CrashDiagnostics.sanitizeReport(
			report.toByteArray(Charsets.UTF_8)
		)

		assertNotNull(sanitized)

		assertEquals(
			report,
			sanitized!!.toString(Charsets.UTF_8)
		)
	}
}
