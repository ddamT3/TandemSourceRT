package com.example.tandemapp.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort fatal exception diagnostics.
 *
 * Never records exception messages, application payloads,
 * credentials or medical data.
 */
internal object CrashDiagnostics {

	private const val MAX_FRAMES = 64
	private const val MAX_CAUSES = 4
	private const val MAX_FILE_BYTES = 64 * 1024
	private const val RETENTION_MS = 48L * 60L * 60L * 1000L
	private const val RESERVE_BYTES = 64 * 1024

	private val handling = AtomicBoolean(false)

	@Volatile
	private var installed = false

	@Volatile
	private var reserve: ByteArray? = null

	@Volatile
	private var lastPhase = "startup"

	private val allowedPhases = setOf(
		"startup",
		"activity_start",
		"cache_read",
		"cache_decode",
		"cache_loaded",
		"dashboard_prepare",
		"dashboard_ready",
		"authentication",
		"assignment_lookup",
		"pump_logs_download",
		"json_parse",
		"event_extraction",
		"merge",
		"cache_encode",
		"cache_save",
		"ui_update",
		"dashboard_cgm_timeline",
		"assignments",
		"pump_logs_download_decode",
		"assignment_merge",
		"idle"
	)

	@Synchronized
	fun initialize(context: Context) {
		if (installed) return

		val previous =
			Thread.getDefaultUncaughtExceptionHandler() ?: return

		try {
			val directory = File(
				context.applicationContext.filesDir,
				"crash_diagnostics"
			)

			if (!directory.isDirectory && !directory.mkdirs()) {
				return
			}

			pruneExpired(directory)

			reserve = ByteArray(RESERVE_BYTES)

			Thread.setDefaultUncaughtExceptionHandler { thread, error ->
				try {
					if (handling.compareAndSet(false, true)) {
						reserve = null
						record(directory, error)
					}
				} catch (_: Throwable) {
					// Best effort only. Never replace the original failure.
				} finally {
					previous.uncaughtException(thread, error)
				}
			}

			installed = true
		} catch (_: Exception) {
			reserve = null
		}
	}

	fun markPhase(phase: String) {
		if (phase in allowedPhases) {
			lastPhase = phase
		}
	}

	internal fun pruneExpired(directory: File, nowMs: Long = System.currentTimeMillis()): Boolean {
		return try {
			val file = File(directory, "latest_crash.txt")
			if (!file.exists()) return true
			if (!file.isFile) return false
			val modified = file.lastModified()
			if (modified <= 0L || modified > nowMs) return false
			if (nowMs - modified >= RETENTION_MS) {
				file.delete() || !file.exists()
			} else {
				true
			}
		} catch (_: Exception) {
			false
		}
	}

	fun readReport(context: Context): ByteArray? {
		return try {
			readReportFromDirectory(
				File(context.applicationContext.filesDir, "crash_diagnostics")
			)
		} catch (_: Exception) {
			null
		}
	}

	internal fun readReportFromDirectory(
		directory: File,
		nowMs: Long = System.currentTimeMillis()
	): ByteArray? {
		return try {
			if (!pruneExpired(directory, nowMs)) return null

			val file = File(directory, "latest_crash.txt")
			if (!file.isFile) return null

			val size = file.length()
			if (size <= 0L || size > MAX_FILE_BYTES) return null

			val buffer = ByteArray(size.toInt())

			file.inputStream().use { input ->
				var offset = 0

				while (offset < buffer.size) {
					val count = input.read(
						buffer,
						offset,
						buffer.size - offset
					)

					if (count <= 0) break
					offset += count
					}

				if (offset == 0) return null
				if (input.read() != -1) return null

				if (offset == buffer.size) {
					buffer
				} else {
					buffer.copyOf(offset)
				}
			}
		} catch (_: Exception) {
			null
		}
	}


	fun readExportableReport(context: Context): ByteArray? {
		val raw = readReport(context) ?: return null
		return sanitizeReport(raw)
	}

	internal fun sanitizeReport(raw: ByteArray): ByteArray? {
		if (raw.isEmpty() || raw.size > MAX_FILE_BYTES) return null

		val numericKeys = setOf(
			"WALL_TIME_MS",
			"JAVA_USED_BYTES",
			"JAVA_COMMITTED_BYTES",
			"JAVA_MAX_BYTES",
			"CAUSE_DEPTH",
			"FRAMES_TOTAL",
			"FRAMES_WRITTEN"
		)

		val classPattern = Regex(
			"""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*"""
		)

		val framePattern = Regex(
			"""[A-Za-z_$][A-Za-z0-9_.$]*\.(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>):-?[0-9]{1,10}"""
		)

		val text = raw.toString(Charsets.UTF_8)
		val completeEnd = text.lastIndexOf('\n')
		if (completeEnd < 0) return null

		val result = StringBuilder()
		var complete = false
		var rejected = false

		for (line in text.substring(0, completeEnd).lineSequence()) {
			if (line.length > 512) {
				rejected = true
				continue
			}

			val separator = line.indexOf('=')
			if (separator <= 0) {
				rejected = true
				continue
			}

			val key = line.substring(0, separator)
			val value = line.substring(separator + 1)

			if (key == "RECORD_COMPLETE") {
				if (value == "YES") complete = true
				else rejected = true
				continue
			}

			val accepted = when {
				key == "FORMAT" -> value == "1"
				key == "LAST_PHASE" -> value in allowedPhases
				key in numericKeys ->
					value.length <= 19 &&
						value.toLongOrNull()?.let { it >= 0L } == true
				key == "EXCEPTION" || key == "CAUSE_TYPE" ->
					value.length <= 256 && classPattern.matches(value)
				key == "FRAME" ->
					value.length <= 512 && framePattern.matches(value)
				else -> false
			}

			if (accepted) {
				result.append(line).append('\n')
			} else {
				rejected = true
			}
		}

		if (result.isEmpty()) return null

		if (complete && !rejected && completeEnd == text.lastIndex) {
			result.append("RECORD_COMPLETE=YES\n")
		} else {
			result.append("RECORD_COMPLETE=NO\n")
		}

		val output = result.toString().toByteArray(Charsets.UTF_8)

		return output.takeIf { it.size <= MAX_FILE_BYTES }
	}


	internal fun record(directory: File, error: Throwable) {
		val runtime = Runtime.getRuntime()
		val committed = runtime.totalMemory()
		val used = (committed - runtime.freeMemory())
			.coerceAtLeast(0L)

		val file = File(directory, "latest_crash.txt")

		FileOutputStream(file, false).use { output ->
			var written = 0
			var truncated = false

			// Reserve space for the final status and counters.
			val footerReserve = 256

			fun line(
				value: String,
				footer: Boolean = false
			): Boolean {
				val limit = if (footer) {
					MAX_FILE_BYTES
				} else {
					MAX_FILE_BYTES - footerReserve
				}

				if (value.length > 512 ||
					value.indexOf('\n') >= 0 ||
					value.indexOf('\r') >= 0) {
					truncated = true
					return false
				}

				val bytes = value.toByteArray(Charsets.UTF_8)

				if (bytes.size + 1 > limit - written) {
					truncated = true
					return false
				}

				output.write(bytes)
				output.write(10)
				written += bytes.size + 1

				return true
			}

			line("FORMAT=1")
			line("WALL_TIME_MS=${System.currentTimeMillis()}")
			line("LAST_PHASE=$lastPhase")
			line("JAVA_USED_BYTES=$used")
			line("JAVA_COMMITTED_BYTES=$committed")
			line("JAVA_MAX_BYTES=${runtime.maxMemory()}")
			line("EXCEPTION=${error.javaClass.name}")

			// Preserve the diagnostic header before stack extraction.
			output.fd.sync()

			var cause: Throwable? = error
			var depth = 0

			while (cause != null && depth < MAX_CAUSES) {
				val current = cause

				if (!line("CAUSE_DEPTH=$depth")) break
				if (!line("CAUSE_TYPE=${current.javaClass.name}")) break

				val frames = current.stackTrace
				val count = minOf(frames.size, MAX_FRAMES)
				var framesWritten = 0

				for (index in 0 until count) {
					val frame = frames[index]

					if (frame.className.length > 256 ||
						frame.methodName.length > 128 ||
						frame.className.indexOf('\n') >= 0 ||
						frame.className.indexOf('\r') >= 0 ||
						frame.methodName.indexOf('\n') >= 0 ||
						frame.methodName.indexOf('\r') >= 0) {
						truncated = true
						continue
					}

					val accepted = line(
						"FRAME=${frame.className}." +
							"${frame.methodName}:" +
							"${frame.lineNumber}"
					)

					if (!accepted) break

					framesWritten++
				}

				line("FRAMES_TOTAL=${frames.size}", footer = true)
				line("FRAMES_WRITTEN=$framesWritten", footer = true)

				if (framesWritten < frames.size) {
					truncated = true
				}

				val next = current.cause
				depth++

				if (next === current) {
					truncated = true
					break
				}

				cause = next

				if (written >= MAX_FILE_BYTES - footerReserve) {
					truncated = true
					break
				}
			}

			if (cause != null && depth >= MAX_CAUSES) {
				truncated = true
			}

			val status = if (truncated) "NO" else "YES"

			line("RECORD_COMPLETE=$status", footer = true)

			output.fd.sync()
		}
	}
}
