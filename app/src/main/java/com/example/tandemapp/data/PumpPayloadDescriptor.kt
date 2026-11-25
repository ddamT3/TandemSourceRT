package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Isolated prototype; archive files must be immutable while referenced. */
@Serializable
internal data class PumpPayloadDescriptor(
	val requestedAssignmentId: String?,
	val archiveReference: String,
	val clockChanges: JsonElement?,
	val eventCount: Long,
	val archiveBytes: Long,
	val sha256: String
)

internal object PumpPayloadAccess {
	private fun resolve(directory: File, reference: String): File {
		require(reference.matches(Regex("[a-zA-Z0-9_-]{1,100}\\.json")))
		val root = directory.canonicalFile
		val file = File(root, reference)
		if (file.canonicalFile != file || !file.isFile) {
			throw IOException("Archive unavailable")
		}
		return file
	}

	fun describe(
		directory: File,
		reference: String,
		assignment: String?,
		maxBytes: Long
	): PumpPayloadDescriptor = scan(directory, reference, assignment, maxBytes, null).first

	fun readEvent(
		directory: File,
		descriptor: PumpPayloadDescriptor,
		eventIndex: Long
	): JsonObject {
		require(eventIndex >= 0 && eventIndex < descriptor.eventCount)
		val (actual, event) = scan(
			directory, descriptor.archiveReference,
			descriptor.requestedAssignmentId, descriptor.archiveBytes, eventIndex
		)
		if (actual != descriptor) throw IOException("Archive integrity mismatch")
		return event ?: throw IOException("Event unavailable")
	}

	private fun scan(
		directory: File,
		reference: String,
		assignment: String?,
		maxBytes: Long,
		eventIndex: Long?
	): Pair<PumpPayloadDescriptor, JsonObject?> {
		require(maxBytes > 0)
		val file = resolve(directory, reference)
		val size = file.length()
		if (size <= 0 || size > maxBytes) throw IOException("Archive size invalid")
		val digest = MessageDigest.getInstance("SHA-256")
		var clockChanges: JsonElement? = null
		var event: JsonObject? = null
		val result = file.inputStream().use { input ->
			val bounded = object : java.io.FilterInputStream(input) {
				var remaining = size
				override fun read(): Int {
					if (remaining == 0L) {
						if (super.read() != -1) throw IOException("Archive grew")
						return -1
					}
					val value = super.read()
					if (value >= 0) remaining--
					return value
				}
				override fun read(b: ByteArray, off: Int, len: Int): Int {
					if (len == 0) return 0
					if (remaining == 0L) return read()
					val count = super.read(b, off, minOf(len.toLong(), remaining).toInt())
					if (count > 0) remaining -= count.toLong()
					return count
				}
			}
			val parsed = PumpPayloadStreamParser.parse(
				DigestInputStream(bounded, digest),
				onEvent = { index, value -> if (index == eventIndex) event = value },
				onMetadata = { name, value -> if (name == "clockChanges") clockChanges = value }
			)
			if (bounded.remaining != 0L || file.length() != size) {
				throw IOException("Archive size changed")
			}
			parsed
		}
		val hash = digest.digest().joinToString("") {
			(it.toInt() and 0xff).toString(16).padStart(2, '0')
		}
		return PumpPayloadDescriptor(
			assignment, reference, clockChanges, result.eventCount, size, hash
		) to event
	}
}
