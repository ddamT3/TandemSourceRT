package com.example.tandemapp.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashDiagnosticsTest {

	@get:Rule
	val temporary = TemporaryFolder()

	private val now = 1800000000000L
	private val retention = 48L * 60L * 60L * 1000L

	private fun report(
		text: String,
		modified: Long = now
	): File {
		val file = File(temporary.root, "latest_crash.txt")
		file.writeText(text, Charsets.UTF_8)
		assertTrue(file.setLastModified(modified))
		return file
	}

	private fun sanitize(text: String): String? {
		return CrashDiagnostics.sanitizeReport(
			text.toByteArray(Charsets.UTF_8)
		)?.toString(Charsets.UTF_8)
	}

	@Test
	fun retentionMissingFile() {
		assertTrue(
			CrashDiagnostics.pruneExpired(temporary.root, now)
		)
	}

	@Test
	fun retentionFreshFile() {
		val file = report("FORMAT=1\n")
		assertTrue(
			CrashDiagnostics.pruneExpired(temporary.root, now)
		)
		assertTrue(file.exists())
	}

	@Test
	fun retentionBeforeBoundary() {
		val file = report(
			"FORMAT=1\n",
			now - retention + 1000L
		)
		assertTrue(
			CrashDiagnostics.pruneExpired(temporary.root, now)
		)
		assertTrue(file.exists())
	}

	@Test
	fun retentionAtBoundary() {
		val file = report("FORMAT=1\n", now - retention)
		assertTrue(
			CrashDiagnostics.pruneExpired(temporary.root, now)
		)
		assertFalse(file.exists())
	}

	@Test
	fun retentionFutureTimestamp() {
		val file = report("FORMAT=1\n", now + 60000L)
		assertFalse(
			CrashDiagnostics.pruneExpired(temporary.root, now)
		)
		assertTrue(file.exists())
	}

	@Test
	fun retentionRejectsDirectoryInsteadOfFile() {
		val directory = File(temporary.root, "latest_crash.txt")
		assertTrue(directory.mkdir())
		assertFalse(
			CrashDiagnostics.pruneExpired(temporary.root, now)
		)
		assertTrue(directory.isDirectory)
	}

	@Test
	fun readValidReport() {
		val text = "FORMAT=1\nRECORD_COMPLETE=YES\n"
		report(text)

		assertArrayEquals(
			text.toByteArray(Charsets.UTF_8),
			CrashDiagnostics.readReportFromDirectory(
				temporary.root, now
			)
		)
	}

	@Test
	fun readIncompleteReport() {
		val text = "FORMAT=1\nEXCEPTION=java.lang.OutOfMemoryError\n"
		report(text)

		assertArrayEquals(
			text.toByteArray(Charsets.UTF_8),
			CrashDiagnostics.readReportFromDirectory(
				temporary.root, now
			)
		)
	}

	@Test
	fun readEmptyReport() {
		report("")
		assertNull(
			CrashDiagnostics.readReportFromDirectory(
				temporary.root, now
			)
		)
	}

	@Test
	fun readOversizedReport() {
		report("A".repeat(65537))
		assertNull(
			CrashDiagnostics.readReportFromDirectory(
				temporary.root, now
			)
		)
	}

	@Test
	fun readExpiredReport() {
		val file = report(
			"FORMAT=1\n",
			now - retention - 1000L
		)

		assertNull(
			CrashDiagnostics.readReportFromDirectory(
				temporary.root, now
			)
		)
		assertFalse(file.exists())
	}

	@Test
	fun sanitizeValidReport() {
		val text = listOf(
			"FORMAT=1",
			"WALL_TIME_MS=1800000000000",
			"LAST_PHASE=cache_decode",
			"JAVA_USED_BYTES=1000",
			"JAVA_COMMITTED_BYTES=2000",
			"JAVA_MAX_BYTES=3000",
			"EXCEPTION=java.lang.OutOfMemoryError",
			"CAUSE_DEPTH=0",
			"CAUSE_TYPE=java.lang.OutOfMemoryError",
			"FRAME=java.lang.String.length:12",
			"FRAMES_TOTAL=1",
			"FRAMES_WRITTEN=1",
			"RECORD_COMPLETE=YES"
		).joinToString("\n", postfix = "\n")

		assertEquals(text, sanitize(text))
	}

	@Test
	fun sanitizeUnknownFields() {
		val result = sanitize(
			"FORMAT=1\n" +
				"MESSAGE=SYNTHETIC_SECRET\n" +
				"TOKEN=SYNTHETIC_TOKEN\n" +
				"PAYLOAD=SYNTHETIC_PAYLOAD\n" +
				"RECORD_COMPLETE=YES\n"
		)

		assertEquals(
			"FORMAT=1\nRECORD_COMPLETE=NO\n",
			result
		)
	}

	@Test
	fun sanitizeStackFrames() {
		val result = sanitize(
			"FORMAT=1\n" +
				"FRAME=java.lang.String.length:12\n" +
				"FRAME=java.lang.String.<init>:-1\n" +
				"FRAME=invalid frame with spaces:10\n" +
				"RECORD_COMPLETE=YES\n"
		)

		assertEquals(
			"FORMAT=1\n" +
				"FRAME=java.lang.String.length:12\n" +
				"FRAME=java.lang.String.<init>:-1\n" +
				"RECORD_COMPLETE=NO\n",
			result
		)
	}

	@Test
	fun sanitizeInvalidValues() {
		val result = sanitize(
			"FORMAT=1\n" +
				"LAST_PHASE=UNKNOWN_PHASE\n" +
				"JAVA_USED_BYTES=-1\n" +
				"JAVA_MAX_BYTES=9999999999999999999999\n" +
				"EXCEPTION=invalid exception message\n" +
				"RECORD_COMPLETE=YES\n"
		)

		assertEquals(
			"FORMAT=1\nRECORD_COMPLETE=NO\n",
			result
		)
	}

	@Test
	fun sanitizeTruncatedReport() {
		val result = sanitize(
			"FORMAT=1\n" +
				"EXCEPTION=java.lang.OutOfMemoryError\n" +
				"FRAME=unfinished"
		)

		assertEquals(
			"FORMAT=1\n" +
				"EXCEPTION=java.lang.OutOfMemoryError\n" +
				"RECORD_COMPLETE=NO\n",
			result
		)
	}

	@Test
	fun sanitizeOversizedReport() {
		assertNull(sanitize("A".repeat(65537)))
	}

	@Test
	fun sanitizeNoAcceptedFields() {
		assertNull(
			sanitize("UNKNOWN=SYNTHETIC_VALUE\n")
		)
	}
}
