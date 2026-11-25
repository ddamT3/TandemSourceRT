package com.example.tandemapp.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** Provisional extraction only. Caller must provide an immutable legacy snapshot. */
internal object LegacyCacheExtractor {
	data class Limits(
		val maxInputBytes: Long = 512L * 1024 * 1024,
		val maxPayloadBytes: Long = 256L * 1024 * 1024,
		val maxSources: Int = 128,
		val maxTokenChars: Int = 1024 * 1024,
		val maxDatasetBytes: Long = 32L * 1024 * 1024,
		val maxOutputBytes: Long = 512L * 1024 * 1024,
		val reserveDiskBytes: Long = 16L * 1024 * 1024,
		val maxRetainedMetadataBytes: Long = 4L * 1024 * 1024
	) {
		init {
			require(maxInputBytes > 0 && maxPayloadBytes > 0)
			require(maxDatasetBytes > 0 && maxOutputBytes > 0 && reserveDiskBytes >= 0)
			require(maxRetainedMetadataBytes > 0)
			require(maxSources in 1..128 && maxTokenChars in 1..1024 * 1024)
		}
	}

	/** Output is not a published cache. Dataset has no embedded pumpEventsSources. */
	fun extract(source: File, destination: File, limits: Limits = Limits()): List<PumpPayloadDescriptor> {
		val size = source.length()
		require(source.isFile && size in 1..limits.maxInputBytes)
		require(!destination.exists())
		if (!destination.mkdir()) throw IOException("Cannot create staging directory")
		var complete = false
		try {
			val descriptors = mutableListOf<PumpPayloadDescriptor>()
			val budget = OutputBudget(destination, limits)
			var metadataRemaining = limits.maxRetainedMetadataBytes
			source.inputStream().use { input ->
				val bounded = object : java.io.FilterInputStream(input) {
					var remaining = size
					override fun read(b: ByteArray, off: Int, len: Int): Int {
						if (len == 0) return 0
						if (remaining == 0L) {
							if (super.read() != -1) throw IOException("Legacy snapshot grew")
							return -1
						}
						val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
						if (n > 0) remaining -= n
						return n
					}
				}
				val decoder = Charsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
				val reader = JsonReader(PumpPayloadStreamParser.BoundedJsonReader(
					InputStreamReader(bounded, decoder), limits.maxTokenChars))
				reader.isLenient = false
				writeJson(File(destination, "dataset.json"), limits.maxDatasetBytes, budget) { writer ->
					var datasetSeen = false
					var timestampSeen = false
					writer.beginObject()
					objectMembers(reader) { name ->
						writer.name(name)
						when (name) {
							"dataset" -> {
								datasetSeen = true
								writer.beginObject()
								objectMembers(reader) { member ->
									if (member == "pumpEventsSources") {
										reader.beginArray()
										while (reader.hasNext()) {
											if (descriptors.size >= limits.maxSources)
												throw IOException("Source count limit exceeded")
											val descriptor = extractPayload(reader, destination, descriptors.size, limits, budget)
											val assignmentBytes = descriptor.requestedAssignmentId?.let {
												PumpPayloadStreamParser.utf8ByteCount(it)
											} ?: 0L
											if (assignmentBytes > metadataRemaining)
												throw IOException("Aggregate metadata limit exceeded")
											metadataRemaining -= assignmentBytes
											descriptor.clockChanges?.let {
												metadataRemaining -= PumpPayloadStreamParser.serializedUtf8ByteCount(it, metadataRemaining)
											}
											descriptors += descriptor
										}
										reader.endArray()
									} else {
										writer.name(member)
										copyValue(reader, writer, 1)
									}
								}
								writer.endObject()
							}
							"latestTimestamp" -> {
								if (reader.peek() != JsonToken.STRING) throw IOException("Invalid timestamp")
								timestampSeen = true
								writer.value(reader.nextString())
							}
							else -> copyValue(reader, writer, 1)
						}
					}
					writer.endObject()
					if (!datasetSeen || !timestampSeen || reader.peek() != JsonToken.END_DOCUMENT ||
						bounded.remaining != 0L || source.length() != size)
						throw IOException("Invalid legacy envelope")
				}
			}
			complete = true
			return descriptors
		} finally {
			if (!complete && !destination.deleteRecursively())
				throw IOException("Staging cleanup failed")
		}
	}

	private fun extractPayload(reader: JsonReader, directory: File, index: Int,
		limits: Limits, budget: OutputBudget): PumpPayloadDescriptor {
		val reference = "payload_${index}.json"
		var assignment: String? = null
		var payloadSeen = false
		objectMembers(reader) { name ->
			when (name) {
				"requestedAssignmentId" -> {
					assignment = when (reader.peek()) {
						JsonToken.NULL -> { reader.nextNull(); null }
						JsonToken.STRING -> reader.nextString()
						else -> throw IOException("Invalid assignment")
					}
					if ((assignment?.length ?: 0) > 4096) throw IOException("Assignment too long")
				}
				"rawPayload" -> {
					payloadSeen = true
					if (reader.peek() != JsonToken.BEGIN_OBJECT) throw IOException("Invalid payload")
					writeJson(File(directory, reference), limits.maxPayloadBytes, budget) { copyValue(reader, it, 1) }
				}
				else -> throw IOException("Unsupported source wrapper member")
			}
		}
		if (!payloadSeen) throw IOException("Missing raw payload")
		return PumpPayloadAccess.describe(directory, reference, assignment, limits.maxPayloadBytes)
	}

	private class OutputBudget(private val directory: File, private val limits: Limits) {
		private var remaining = limits.maxOutputBytes

		fun consume(bytes: Int) {
			if (bytes.toLong() > remaining) throw IOException("Aggregate output limit exceeded")
			val available = directory.usableSpace
			if (available < limits.reserveDiskBytes ||
				bytes.toLong() > available - limits.reserveDiskBytes)
				throw IOException("Insufficient staging disk space")
			remaining -= bytes
		}
	}

	private fun writeJson(file: File, maxBytes: Long, budget: OutputBudget, block: (JsonWriter) -> Unit) {
		file.outputStream().use { output ->
			val bounded = object : java.io.OutputStream() {
				private var remaining = maxBytes

				private fun consume(bytes: Int) {
					if (bytes.toLong() > remaining) throw IOException("File output limit exceeded")
					budget.consume(bytes)
					remaining -= bytes
				}

				override fun write(b: Int) {
					consume(1)
					output.write(b)
				}

				override fun write(b: ByteArray, off: Int, len: Int) {
					consume(len)
					output.write(b, off, len)
				}
			}
			val writer = JsonWriter(bounded.writer(Charsets.UTF_8).buffered())
			writer.serializeNulls = true
			block(writer)
			writer.flush()
			output.fd.sync()
		}
	}

	private fun objectMembers(reader: JsonReader, block: (String) -> Unit) {
		val names = HashSet<String>()
		var chars = 0L
		reader.beginObject()
		while (reader.hasNext()) {
			val name = reader.nextName()
			chars += name.length
			if (chars > 65536 || names.size >= 256 || !names.add(name))
				throw IOException("Invalid object member budget")
			block(name)
		}
		reader.endObject()
	}

	private fun copyValue(reader: JsonReader, writer: JsonWriter, depth: Int) {
		if (depth > 64) throw IOException("Legacy depth limit exceeded")
		when (reader.peek()) {
			JsonToken.BEGIN_OBJECT -> {
				writer.beginObject()
				objectMembers(reader) { name ->
					writer.name(name)
					copyValue(reader, writer, depth + 1)
				}
				writer.endObject()
			}
			JsonToken.BEGIN_ARRAY -> {
				reader.beginArray()
				writer.beginArray()
				while (reader.hasNext()) copyValue(reader, writer, depth + 1)
				reader.endArray()
				writer.endArray()
			}
			JsonToken.STRING -> writer.value(reader.nextString())
			JsonToken.NUMBER -> writer.jsonValue(reader.nextString())
			JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
			JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
			else -> throw IOException("Unexpected legacy token")
		}
	}
}
