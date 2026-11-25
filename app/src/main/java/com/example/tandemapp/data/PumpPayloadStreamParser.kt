package com.example.tandemapp.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Isolated incremental parser for archived original payloads.
 *
 * The original archive remains authoritative.
 * Callbacks must not retain an unbounded number of elements.
 * Callback results are provisional until parse returns successfully.
 * The caller owns the input stream.
 */
internal object PumpPayloadStreamParser {
	data class Limits(
		val maxDepth: Int = 64,
		val maxElementBytes: Int = 1024 * 1024,
		val maxNodes: Int = 16384,
		val maxTopLevelMembers: Int = 256,
		val maxTopLevelNameBytes: Int = 64 * 1024
	) {
		init {
			require(maxDepth in 1..128)
			require(maxElementBytes > 0)
			require(maxNodes > 0)
			require(maxTopLevelMembers > 0)
			require(maxTopLevelNameBytes > 0)
		}
	}

	data class Result(
		val eventCount: Long,
		val metadataCount: Long
	)

	/**
	 * Limits lexical token length before Gson can accumulate it.
	 * Counts UTF-16 code units, including JSON escape syntax.
	 * Gson remains responsible for JSON grammar validation.
	 */
	internal class BoundedJsonReader(
		private val delegate: Reader,
		private val maxTokenChars: Int
	) : Reader() {
		private var quoted = false
		private var escaped = false
		private var tokenChars = 0

		private fun countChar() {
			if (tokenChars >= maxTokenChars) {
				throw IOException("JSON lexical token limit exceeded")
			}
			tokenChars++
		}

		private fun inspect(char: Char) {
			if (quoted) {
				if (escaped) {
					countChar()
					escaped = false
				} else if (char == '\\') {
					countChar()
					escaped = true
				} else if (char == '"') {
					quoted = false
					tokenChars = 0
				} else {
					countChar()
				}
				return
			}

			when (char) {
				'"' -> {
					quoted = true
					escaped = false
					tokenChars = 0
				}
				'{', '}', '[', ']', ':', ',',
				' ', '\t', '\r', '\n' -> {
					tokenChars = 0
				}
				else -> countChar()
			}
		}

		override fun read(
			buffer: CharArray,
			offset: Int,
			length: Int
		): Int {
			val count = delegate.read(
				buffer,
				offset,
				minOf(length, 4096)
			)

			if (count > 0) {
				for (index in offset until offset + count) {
					inspect(buffer[index])
				}
			}

			return count
		}

		override fun close() {
			delegate.close()
		}
	}

	internal fun utf8ByteCount(value: String): Long {
		var bytes = 0L
		var index = 0

		while (index < value.length) {
			val char = value[index]

			when {
				char.code <= 0x7f -> {
					bytes += 1
				}

				char.code <= 0x7ff -> {
					bytes += 2
				}

				Character.isHighSurrogate(char) -> {
					if (
						index + 1 < value.length &&
						Character.isLowSurrogate(value[index + 1])
					) {
						bytes += 4
						index++
					} else {
						bytes += 1
					}
				}

				Character.isLowSurrogate(char) -> {
					bytes += 1
				}

				else -> {
					bytes += 3
				}
			}

			index++
		}

		return bytes
	}

	private class SerializedSizeCounter(
		private val limit: Long
	) {
		var bytes = 0L
			private set

		private fun charge(count: Long) {
			if (count > limit - bytes) {
				throw IOException("Serialized JSON element limit exceeded")
			}
			bytes += count
		}

		private fun quoted(value: String) {
			charge(2)
			var index = 0

			while (index < value.length) {
				val char = value[index]

				when {
					char == '"' || char == '\\' -> {
						charge(2)
					}

					char == '\b' ||
					char == '\t' ||
					char == '\n' ||
					char == '\r' ||
					char == '\u000c' -> {
						charge(2)
					}

					char.code < 0x20 -> {
						charge(6)
					}

					char.code <= 0x7f -> {
						charge(1)
					}

					char.code <= 0x7ff -> {
						charge(2)
					}

					Character.isHighSurrogate(char) -> {
						if (
							index + 1 < value.length &&
							Character.isLowSurrogate(value[index + 1])
						) {
							charge(4)
							index++
						} else {
							charge(1)
						}
					}

					Character.isLowSurrogate(char) -> {
						charge(1)
					}

					else -> {
						charge(3)
					}
				}

				index++
			}
		}

		fun element(value: JsonElement) {
			when (value) {
				is JsonObject -> {
					charge(2)
					var first = true

					for ((name, child) in value) {
						if (!first) {
							charge(1)
						}
						first = false

						quoted(name)
						charge(1)
						element(child)
					}
				}

				is JsonArray -> {
					charge(2)
					var first = true

					for (child in value) {
						if (!first) {
							charge(1)
						}
						first = false
						element(child)
					}
				}

				is JsonPrimitive -> {
					if (value.isString) {
						quoted(value.content)
					} else {
						charge(utf8ByteCount(value.content))
					}
				}
			}
		}
	}

	internal fun serializedUtf8ByteCount(
		element: JsonElement,
		limit: Long = Long.MAX_VALUE
	): Long {
		require(limit >= 0)

		val counter = SerializedSizeCounter(limit)
		counter.element(element)
		return counter.bytes
	}

	private class Budget(private val limits: Limits) {
		private var nodes = 0
		private var bytes = 0L

		fun node(depth: Int) {
			if (depth > limits.maxDepth) {
				throw IOException("JSON depth limit exceeded")
			}
			if (nodes >= limits.maxNodes) {
				throw IOException("JSON node limit exceeded")
			}
			nodes++
			charge(1)
		}

		fun charge(count: Long) {
			if (count > limits.maxElementBytes.toLong() - bytes) {
				throw IOException("JSON element size limit exceeded")
			}
			bytes += count
		}

		fun text(value: String) {
			charge(utf8ByteCount(value))
		}

		fun validate(element: JsonElement) {
			serializedUtf8ByteCount(
				element,
				limits.maxElementBytes.toLong()
			)
		}
	}

	fun parse(
		input: InputStream,
		limits: Limits = Limits(),
		onEvent: (Long, JsonObject) -> Unit,
		onMetadata: (String, JsonElement) -> Unit = { _, _ -> }
	): Result {
		val decoder = Charsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)

		val reader = JsonReader(
			BoundedJsonReader(
				InputStreamReader(input, decoder),
				limits.maxElementBytes
			)
		)
		reader.isLenient = false

		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw IOException("Expected payload object")
		}

		var eventCount = 0L
		var metadataCount = 0L
		var eventsSeen = false
		val topLevelNames = HashSet<String>()
		var topLevelNameBytes = 0L

		reader.beginObject()

		while (reader.hasNext()) {
			if (topLevelNames.size >= limits.maxTopLevelMembers) {
				throw IOException("Top-level member limit exceeded")
			}

			val name = reader.nextName()

			if (name in topLevelNames) {
				throw IOException("Duplicate top-level member")
			}

			val nameBytes = utf8ByteCount(name)

			if (
				nameBytes >
				limits.maxTopLevelNameBytes.toLong() - topLevelNameBytes
			) {
				throw IOException("Top-level name budget exceeded")
			}

			topLevelNames.add(name)
			topLevelNameBytes += nameBytes

			if (name == "events") {

				eventsSeen = true

				if (reader.peek() != JsonToken.BEGIN_ARRAY) {
					throw IOException("Expected events array")
				}

				reader.beginArray()

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.BEGIN_OBJECT) {
						throw IOException("Expected event object")
					}

					val budget = Budget(limits)
					val event = readValue(reader, budget, 1) as JsonObject
					budget.validate(event)

					if (eventCount == Long.MAX_VALUE) {
						throw IOException("Event index overflow")
					}

					onEvent(eventCount, event)
					eventCount++
				}

				reader.endArray()
			} else {
				val budget = Budget(limits)
				budget.text(name)

				val value = readValue(reader, budget, 1)
				budget.validate(value)

				if (metadataCount == Long.MAX_VALUE) {
					throw IOException("Metadata count overflow")
				}

				onMetadata(name, value)
				metadataCount++
			}
		}

		reader.endObject()

		if (!eventsSeen) {
			throw IOException("Missing events array")
		}

		if (reader.peek() != JsonToken.END_DOCUMENT) {
			throw IOException("Unexpected trailing JSON")
		}

		return Result(eventCount, metadataCount)
	}

	private fun readValue(
		reader: JsonReader,
		budget: Budget,
		depth: Int
	): JsonElement {
		budget.node(depth)

		return when (reader.peek()) {
			JsonToken.BEGIN_OBJECT -> {
				val values = linkedMapOf<String, JsonElement>()

				reader.beginObject()

				while (reader.hasNext()) {
					val name = reader.nextName()
					budget.text(name)

					if (values.containsKey(name)) {
						throw IOException("Duplicate JSON object member")
					}

					values[name] = readValue(reader, budget, depth + 1)
				}

				reader.endObject()
				JsonObject(values)
			}

			JsonToken.BEGIN_ARRAY -> {
				val values = mutableListOf<JsonElement>()

				reader.beginArray()

				while (reader.hasNext()) {
					values += readValue(reader, budget, depth + 1)
				}

				reader.endArray()
				JsonArray(values)
			}

			JsonToken.STRING -> {
				val value = reader.nextString()
				budget.text(value)
				JsonPrimitive(value)
			}

			JsonToken.NUMBER -> {
				val value = reader.nextString()
				budget.text(value)
				Json.parseToJsonElement(value)
			}

			JsonToken.BOOLEAN -> {
				budget.charge(5)
				JsonPrimitive(reader.nextBoolean())
			}

			JsonToken.NULL -> {
				budget.charge(4)
				reader.nextNull()
				JsonNull
			}

			else -> throw IOException("Unexpected JSON token")
			}
	}
}
