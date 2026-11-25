package com.example.tandemapp.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import com.google.gson.stream.MalformedJsonException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PumpPayloadStreamParserTest {
	@get:Rule
	val temporary = TemporaryFolder()

	private fun input(value: String) =
		ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))

	private fun expectFailure(block: () -> Unit) {
		try {
			block()
			fail("Expected parser failure")
		} catch (_: IOException) {
			// Expected.
		} catch (_: IllegalStateException) {
			// Invalid JSON reader state.
		}
	}

	@Test
	fun preservesOriginalEventOrderAndIndices() {
		val payload = """{"events":[{"id":3},{"id":1},{"id":3}]}"""
		val indices = mutableListOf<Long>()
		val events = mutableListOf<JsonObject>()

		val result = PumpPayloadStreamParser.parse(
			input(payload),
			onEvent = { index, event ->
				indices += index
				events += event
			}
		)

		assertEquals(listOf(0L, 1L, 2L), indices)
		assertEquals(
			Json.parseToJsonElement(payload).jsonObject["events"]!!.jsonArray,
			events
		)
		assertEquals(3L, result.eventCount)
	}

	@Test
	fun preservesUnknownPropertiesAndMetadata() {
		val payload = """
			{
				"unknown":{"nested":[true,null,123.45]},
				"events":[{
					"pumpDateTime":"2026-01-01T00:00:00",
					"estimatedDateTime":"2026-01-01T00:00:01Z",
					"deviceAssignmentId":"synthetic",
					"sequenceGroup":2,
					"sequenceNumber":7,
					"eventProperties":{
						"egvTimeStamp":123,
						"unknown":[1,2,3]
					}
				}],
				"clockChanges":[{"opaque":true}]
			}
		""".trimIndent()

		val expected = Json.parseToJsonElement(payload).jsonObject
		val events = mutableListOf<JsonObject>()
		val metadata = linkedMapOf<String, JsonElement>()

		val result = PumpPayloadStreamParser.parse(
			input(payload),
			onEvent = { _, event -> events += event },
			onMetadata = { name, value -> metadata[name] = value }
		)

		assertEquals(expected["events"]!!.jsonArray, events)
		assertEquals(expected["unknown"], metadata["unknown"])
		assertEquals(expected["clockChanges"], metadata["clockChanges"])
		assertEquals(2L, result.metadataCount)
	}

	@Test
	fun acceptsEmptyEventsArray() {
		val result = PumpPayloadStreamParser.parse(
			input("""{"events":[]}"""),
			onEvent = { _, _ -> fail("Unexpected event") }
		)

		assertEquals(0L, result.eventCount)
	}

	@Test
	fun rejectsMissingEventsArray() {
		expectFailure {
			PumpPayloadStreamParser.parse(
				input("""{"unknown":true}"""),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun rejectsDuplicateEventsArray() {
		expectFailure {
			PumpPayloadStreamParser.parse(
				input("""{"events":[],"events":[]}"""),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun rejectsNonObjectEvent() {
		expectFailure {
			PumpPayloadStreamParser.parse(
				input("""{"events":[null]}"""),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun rejectsTruncatedPayloadAfterProvisionalEvent() {
		var count = 0

		expectFailure {
			PumpPayloadStreamParser.parse(
				input("""{"events":[{"id":1},"""),
				onEvent = { _, _ -> count++ }
			)
		}

		assertEquals(1, count)
	}

	@Test
	fun rejectsTrailingDocument() {
		expectFailure {
			PumpPayloadStreamParser.parse(
				input("""{"events":[]} {"events":[]}"""),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun rejectsDuplicateEventMember() {
		expectFailure {
			PumpPayloadStreamParser.parse(
				input("""{"events":[{"id":1,"id":2}]}"""),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun rejectsDepthLimit() {
		expectFailure {
			PumpPayloadStreamParser.parse(
				input("""{"events":[{"a":{"b":{"c":1}}}]}"""),
				limits = PumpPayloadStreamParser.Limits(maxDepth = 2),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun rejectsNodeLimit() {
		expectFailure {
			PumpPayloadStreamParser.parse(
				input("""{"events":[{"a":[1,2,3,4,5]}]}"""),
				limits = PumpPayloadStreamParser.Limits(maxNodes = 4),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun rejectsOversizedElement() {
		val payload = """{"events":[{"value":"${"x".repeat(2048)}"}]}"""

		expectFailure {
			PumpPayloadStreamParser.parse(
				input(payload),
				limits = PumpPayloadStreamParser.Limits(maxElementBytes = 128),
				onEvent = { _, _ -> fail("Oversized event delivered") }
			)
		}
	}

	@Test
	fun rejectsMalformedUtf8() {
		val bytes = """{"events":[{"value":""""
			.toByteArray(Charsets.UTF_8) +
			byteArrayOf(0xc3.toByte(), 0x28) +
			""""}]}""".toByteArray(Charsets.UTF_8)

		expectFailure {
			PumpPayloadStreamParser.parse(
				ByteArrayInputStream(bytes),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun doesNotCloseCallerInput() {
		var closed = false

		val stream = object : ByteArrayInputStream(
			"""{"events":[]}""".toByteArray(Charsets.UTF_8)
		) {
			override fun close() {
				closed = true
			}
		}

		PumpPayloadStreamParser.parse(
			stream,
			onEvent = { _, _ -> }
		)

		assertFalse(closed)
		stream.close()
		assertTrue(closed)
	}

	@Test
	fun parsesLargeSyntheticArchiveWithoutCollectingEvents() {
		val count = 100000
		val file = File(temporary.root, "synthetic.json")

		file.bufferedWriter(Charsets.UTF_8).use { writer ->
			writer.write("{\"events\":[")

			repeat(count) { index ->
				if (index > 0) writer.write(",")
				writer.write("{\"id\":$index,\"unknown\":[1,true,null]}")
			}

			writer.write("],\"clockChanges\":[]}")
		}

		var observed = 0L
		var metadataCount = 0L

		val result = file.inputStream().use { stream ->
			PumpPayloadStreamParser.parse(
				stream,
				onEvent = { index, event ->
					assertEquals(observed, index)
					assertEquals(index.toString(), event["id"].toString())
					observed++
				},
				onMetadata = { name, _ ->
					assertEquals("clockChanges", name)
					metadataCount++
				}
			)
		}

		assertEquals(count.toLong(), observed)
		assertEquals(count.toLong(), result.eventCount)
		assertEquals(1L, metadataCount)
		assertEquals(1L, result.metadataCount)
	}

	private class GeneratedTokenInput(
		prefix: String,
		private val tokenLength: Long,
		private val tokenByte: Int,
		suffix: String
	) : InputStream() {
		private val start = prefix.toByteArray(Charsets.UTF_8)
		private val end = suffix.toByteArray(Charsets.UTF_8)
		private var position = 0L

		var bytesRead = 0L
			private set

		override fun read(): Int {
			val value = when {
				position < start.size.toLong() ->
					start[position.toInt()].toInt() and 0xff

				position < start.size.toLong() + tokenLength ->
					tokenByte

				position < start.size.toLong() + tokenLength + end.size ->
					end[(position - start.size - tokenLength).toInt()]
						.toInt() and 0xff

				else -> return -1
			}

			position++
			bytesRead++
			return value
		}

		override fun read(
			buffer: ByteArray,
			offset: Int,
			length: Int
		): Int {
			if (length == 0) return 0

			var count = 0

			while (count < length) {
				val value = read()
				if (value < 0) break

				buffer[offset + count] = value.toByte()
				count++
			}

			return if (count == 0) -1 else count
		}
	}

	private fun assertEarlyTokenRejection(
		label: String,
		prefix: String,
		tokenByte: Int,
		suffix: String,
		allowStrictNumberRejection: Boolean = false
	) {
		val stream = GeneratedTokenInput(
			prefix,
			64L * 1024 * 1024,
			tokenByte,
			suffix
		)

		var deliveredEvents = 0
		var deliveredMetadata = 0
		var rejection = "NONE"

		try {
			PumpPayloadStreamParser.parse(
				stream,
				limits = PumpPayloadStreamParser.Limits(
					maxElementBytes = 4096
				),
				onEvent = { _, _ ->
					deliveredEvents++
				},
				onMetadata = { _, _ ->
					deliveredMetadata++
				}
			)
			fail("Expected early token rejection")
		} catch (error: IOException) {
			rejection = when {
				error.message == "JSON lexical token limit exceeded" ->
					"LEXICAL_LIMIT"

				allowStrictNumberRejection &&
					error is MalformedJsonException &&
					error.message?.startsWith(
						"Use JsonReader.setLenient(true) to accept malformed JSON"
					) == true ->
					"GSON_STRICT"

				else -> throw error
			}
		}

		assertTrue(rejection != "NONE")

		assertTrue(
			"Parser consumed too much input: ${stream.bytesRead}",
			stream.bytesRead <= 64L * 1024
		)

		assertEquals(0, deliveredEvents)
		assertEquals(0, deliveredMetadata)

		println(
			"TOKEN_TEST=$label " +
				"REJECTION=$rejection " +
				"BYTES_READ=${stream.bytesRead} " +
				"EVENTS_DELIVERED=$deliveredEvents " +
				"METADATA_DELIVERED=$deliveredMetadata"
		)
	}

	@Test
	fun rejectsHugeStringBeforeReadingEntireToken() {
		assertEarlyTokenRejection(
			"STRING",
			"{\"events\":[{\"value\":\"",
			'x'.code,
			"\"}]}"
		)
	}

	@Test
	fun rejectsHugeMemberNameBeforeReadingEntireToken() {
		assertEarlyTokenRejection(
			"MEMBER_NAME",
			"{\"events\":[{\"",
			'x'.code,
			"\":1}]}"
		)
	}

	@Test
	fun rejectsHugeNumberBeforeReadingEntireToken() {
		assertEarlyTokenRejection(
			"NUMBER",
			"{\"events\":[{\"value\":",
			'7'.code,
			"}]}",
			allowStrictNumberRejection = true
		)
	}

	@Test
	fun rejectsHugeTopLevelMetadataToken() {
		assertEarlyTokenRejection(
			"METADATA",
			"{\"unknown\":\"",
			'x'.code,
			"\" ,\"events\":[]}"
		)
	}

	@Test
	fun preservesEscapedQuotesAndBackslashes() {
		val payload = """{"events":[{"value":"a\"b\\c\u0041"}]}"""
		val expected = Json.parseToJsonElement(payload)
			.jsonObject["events"]!!.jsonArray.single()

		val result = PumpPayloadStreamParser.parse(
			input(payload),
			onEvent = { _, event ->
				assertEquals(expected, event)
			}
		)

		assertEquals(1L, result.eventCount)
	}

	@Test
	fun preservesEscapeStateAcrossReaderChunks() {
		val payload =
			"{\"events\":[{\"value\":\"" +
			"x".repeat(10000) +
			"\\\"tail\\\\end\"}]}"

		val expected = Json.parseToJsonElement(payload)
			.jsonObject["events"]!!.jsonArray.single()

		val bytes = payload.toByteArray(Charsets.UTF_8)

		val stream = object : ByteArrayInputStream(bytes) {
			override fun read(
				buffer: ByteArray,
				offset: Int,
				length: Int
			): Int = super.read(
				buffer,
				offset,
				minOf(length, 3)
			)
		}

		val result = PumpPayloadStreamParser.parse(
			stream,
			onEvent = { _, event ->
				assertEquals(expected, event)
			}
		)

		assertEquals(1L, result.eventCount)
	}

	private fun expectTopLevelFailure(
		expectedMessage: String,
		block: () -> Unit
	) {
		try {
			block()
			fail("Expected top-level validation failure")
		} catch (error: IOException) {
			assertEquals(expectedMessage, error.message)
		}
	}

	@Test
	fun rejectsDuplicateUnknownTopLevelMember() {
		var metadataCount = 0

		expectTopLevelFailure("Duplicate top-level member") {
			PumpPayloadStreamParser.parse(
				input("""{"unknown":1,"events":[],"unknown":2}"""),
				onEvent = { _, _ -> },
				onMetadata = { _, _ -> metadataCount++ }
			)
		}

		assertEquals(1, metadataCount)
	}

	@Test
	fun rejectsDuplicateClockChanges() {
		var metadataCount = 0

		expectTopLevelFailure("Duplicate top-level member") {
			PumpPayloadStreamParser.parse(
				input("""{"clockChanges":[],"events":[],"clockChanges":[]}"""),
				onEvent = { _, _ -> },
				onMetadata = { _, _ -> metadataCount++ }
			)
		}

		assertEquals(1, metadataCount)
	}

	@Test
	fun rejectsDuplicateEscapedTopLevelName() {
		val payload =
			"{\"events\":[],\"name\":1,\"n\\u0061me\":2}"

		expectTopLevelFailure("Duplicate top-level member") {
			PumpPayloadStreamParser.parse(
				input(payload),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun acceptsExactTopLevelMemberLimit() {
		val result = PumpPayloadStreamParser.parse(
			input("""{"events":[],"a":1,"b":2}"""),
			limits = PumpPayloadStreamParser.Limits(
				maxTopLevelMembers = 3
			),
			onEvent = { _, _ -> }
		)

		assertEquals(2L, result.metadataCount)
	}

	@Test
	fun rejectsTopLevelMemberLimitExceeded() {
		var metadataCount = 0

		expectTopLevelFailure("Top-level member limit exceeded") {
			PumpPayloadStreamParser.parse(
				input("""{"events":[],"a":1,"b":2,"c":3}"""),
				limits = PumpPayloadStreamParser.Limits(
					maxTopLevelMembers = 3
				),
				onEvent = { _, _ -> },
				onMetadata = { _, _ -> metadataCount++ }
			)
		}

		assertEquals(2, metadataCount)
	}

	@Test
	fun acceptsExactTopLevelNameBudget() {
		val result = PumpPayloadStreamParser.parse(
			input("""{"events":[],"abc":1,"def":2}"""),
			limits = PumpPayloadStreamParser.Limits(
				maxTopLevelNameBytes = 12
			),
			onEvent = { _, _ -> }
		)

		assertEquals(2L, result.metadataCount)
	}

	@Test
	fun rejectsCumulativeTopLevelNameBudgetExceeded() {
		var metadataCount = 0

		expectTopLevelFailure("Top-level name budget exceeded") {
			PumpPayloadStreamParser.parse(
				input("""{"events":[],"abc":1,"def":2}"""),
				limits = PumpPayloadStreamParser.Limits(
					maxTopLevelNameBytes = 11
				),
				onEvent = { _, _ -> },
				onMetadata = { _, _ -> metadataCount++ }
			)
		}

		assertEquals(1, metadataCount)
	}

	@Test
	fun countsTopLevelNamesInUtf8Bytes() {
		val payload = "{\"events\":[],\"\u00e8\":1}"

		val result = PumpPayloadStreamParser.parse(
			input(payload),
			limits = PumpPayloadStreamParser.Limits(
				maxTopLevelNameBytes = 8
			),
			onEvent = { _, _ -> }
		)

		assertEquals(1L, result.metadataCount)

		expectTopLevelFailure("Top-level name budget exceeded") {
			PumpPayloadStreamParser.parse(
				input(payload),
				limits = PumpPayloadStreamParser.Limits(
					maxTopLevelNameBytes = 7
				),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun preservesMetadataOrderAroundEvents() {
		val order = mutableListOf<String>()

		val result = PumpPayloadStreamParser.parse(
			input(
				"""{"before":1,"events":[{},{}],"clockChanges":[],"after":true}"""
			),
			onEvent = { index, _ ->
				order += "event:$index"
			},
			onMetadata = { name, _ ->
				order += name
			}
		)

		assertEquals(
			listOf(
				"before",
				"event:0",
				"event:1",
				"clockChanges",
				"after"
			),
			order
		)

		assertEquals(2L, result.eventCount)
		assertEquals(3L, result.metadataCount)
	}

	@Test
	fun rejectsOversizedMetadataArray() {
		var metadataCount = 0

		expectTopLevelFailure("JSON node limit exceeded") {
			PumpPayloadStreamParser.parse(
				input("""{"events":[],"clockChanges":[1,2,3,4,5]}"""),
				limits = PumpPayloadStreamParser.Limits(
					maxNodes = 4
				),
				onEvent = { _, _ -> },
				onMetadata = { _, _ -> metadataCount++ }
			)
		}

		assertEquals(0, metadataCount)
	}

	@Test
	fun rejectsInvalidTopLevelLimits() {
		try {
			PumpPayloadStreamParser.Limits(maxTopLevelMembers = 0)
			fail("Expected invalid member limit")
		} catch (_: IllegalArgumentException) {
			// Expected.
		}

		try {
			PumpPayloadStreamParser.Limits(maxTopLevelNameBytes = 0)
			fail("Expected invalid name budget")
		} catch (_: IllegalArgumentException) {
			// Expected.
		}
	}

	private fun assertUtf8CountParity(value: String) {
		assertEquals(
			value.toByteArray(Charsets.UTF_8).size.toLong(),
			PumpPayloadStreamParser.utf8ByteCount(value)
		)
	}

	@Test
	fun utf8CountMatchesEmptyAndAsciiStrings() {
		assertUtf8CountParity("")
		assertUtf8CountParity("Tandem synthetic metadata")
		assertUtf8CountParity(
			String(CharArray(128) { it.toChar() })
		)
	}

	@Test
	fun utf8CountMatchesTwoByteCharacters() {
		val value = String(
			CharArray(0x800 - 0x80) {
				(it + 0x80).toChar()
			}
		)

		assertUtf8CountParity(value)
	}

	@Test
	fun utf8CountMatchesThreeByteCharacters() {
		assertUtf8CountParity(
			String(CharArray(0xd800 - 0x800) {
				(it + 0x800).toChar()
			})
		)

		assertUtf8CountParity(
			String(CharArray(0x10000 - 0xe000) {
				(it + 0xe000).toChar()
			})
		)
	}

	@Test
	fun utf8CountMatchesSupplementaryCharacters() {
		val value = buildString {
			append(Character.toChars(0x10000))
			append(Character.toChars(0x1f600))
			append(Character.toChars(0x10ffff))
		}

		assertEquals(
			12L,
			PumpPayloadStreamParser.utf8ByteCount(value)
		)

		assertUtf8CountParity(value)
	}

	@Test
	fun utf8CountMatchesIsolatedSurrogates() {
		val values = listOf(
			"\uD800",
			"\uDBFF",
			"\uDC00",
			"\uDFFF",
			"\uD800A",
			"A\uDC00",
			"\uD800\uD800",
			"\uDC00\uDC00",
			"\uDC00\uD800",
			"\uD800\uDC00",
			"\uD800\uD800\uDC00",
			"\uD800\uDC00\uDC00"
		)

		for (value in values) {
			assertUtf8CountParity(value)
		}
	}

	@Test
	fun utf8CountMatchesRandomUtf16Strings() {
		val random = java.util.Random(20261124L)

		repeat(2000) {
			val length = random.nextInt(1024)

			val value = String(
				CharArray(length) {
					random.nextInt(65536).toChar()
				}
			)

			assertUtf8CountParity(value)
		}
	}

	@Test
	fun utf8CountMatchesLargeSyntheticString() {
		val value = buildString {
			repeat(100000) {
				append('A')
				append('\u00e8')
				append('\u20ac')
				append("\uD83D\uDE00")
			}
		}

		assertEquals(
			1000000L,
			PumpPayloadStreamParser.utf8ByteCount(value)
		)

		assertUtf8CountParity(value)
	}

	@Test
	fun utf8CountPreservesSupplementaryNameBudget() {
		val payload = "{\"events\":[],\"\uD83D\uDE00\":1}"

		val result = PumpPayloadStreamParser.parse(
			input(payload),
			limits = PumpPayloadStreamParser.Limits(
				maxTopLevelNameBytes = 10
			),
			onEvent = { _, _ -> }
		)

		assertEquals(1L, result.metadataCount)

		expectTopLevelFailure("Top-level name budget exceeded") {
			PumpPayloadStreamParser.parse(
				input(payload),
				limits = PumpPayloadStreamParser.Limits(
					maxTopLevelNameBytes = 9
				),
				onEvent = { _, _ -> }
			)
		}
	}

	@Test
	fun utf8CountPreservesSerializedElementBoundary() {
		val payload = """{"events":[{"v":"a"}]}"""

		val result = PumpPayloadStreamParser.parse(
			input(payload),
			limits = PumpPayloadStreamParser.Limits(
				maxElementBytes = 9
			),
			onEvent = { _, _ -> }
		)

		assertEquals(1L, result.eventCount)

		expectTopLevelFailure(
			"Serialized JSON element limit exceeded"
		) {
			PumpPayloadStreamParser.parse(
				input(payload),
				limits = PumpPayloadStreamParser.Limits(
					maxElementBytes = 8
				),
				onEvent = { _, _ -> }
			)
		}
	}

	private fun assertSerializedSizeParity(
		element: kotlinx.serialization.json.JsonElement
	) {
		val expected = element.toString()
			.toByteArray(Charsets.UTF_8).size.toLong()

		assertEquals(
			expected,
			PumpPayloadStreamParser.serializedUtf8ByteCount(element)
		)

		assertEquals(
			expected,
			PumpPayloadStreamParser.serializedUtf8ByteCount(
				element,
				expected
			)
		)
	}

	@Test
	fun serializedSizeMatchesPrimitiveValues() {
		val values = listOf(
			"null",
			"true",
			"false",
			"0",
			"-1",
			"12345678901234567890",
			"1.2345",
			"1.23e-12",
			"1E+20",
			"\"\"",
			"\"abc\""
		)

		for (value in values) {
			assertSerializedSizeParity(
				Json.parseToJsonElement(value)
			)
		}
	}

	@Test
	fun serializedSizeMatchesEmptyContainers() {
		assertSerializedSizeParity(
			Json.parseToJsonElement("{}")
		)

		assertSerializedSizeParity(
			Json.parseToJsonElement("[]")
		)
	}

	@Test
	fun serializedSizeMatchesNestedContainers() {
		val payload =
			"""{"a":[1,2,{"b":null}],"c":{"d":true,"e":[]}}"""

		assertSerializedSizeParity(
			Json.parseToJsonElement(payload)
		)
	}

	@Test
	fun serializedSizeMatchesAllAsciiCharacters() {
		val value = String(
			CharArray(128) { it.toChar() }
		)

		val primitive =
			kotlinx.serialization.json.JsonPrimitive(value)

		assertSerializedSizeParity(primitive)

		assertSerializedSizeParity(
			kotlinx.serialization.json.JsonObject(
				mapOf(value to primitive)
			)
		)
	}

	@Test
	fun serializedSizeMatchesUnicodeCharacters() {
		val value = buildString {
			append('\u00e8')
			append('\u20ac')
			append('\u2028')
			append('\u2029')
			append('\u007f')
			append('\u0080')
			append('\u07ff')
			append('\u0800')
			append('\uffff')
			append(Character.toChars(0x1f600))
			append(Character.toChars(0x10ffff))
		}

		val primitive =
			kotlinx.serialization.json.JsonPrimitive(value)

		assertSerializedSizeParity(primitive)

		assertSerializedSizeParity(
			kotlinx.serialization.json.JsonObject(
				mapOf(value to primitive)
			)
		)
	}

	@Test
	fun serializedSizeMatchesIsolatedSurrogates() {
		val values = listOf(
			"\uD800",
			"\uDC00",
			"\uD800A",
			"A\uDC00",
			"\uD800\uD800",
			"\uDC00\uD800",
			"\uD800\uDC00",
			"\uD800\uDC00\uDC00"
		)

		for (value in values) {
			assertSerializedSizeParity(
				kotlinx.serialization.json.JsonPrimitive(value)
			)
		}
	}

	@Test
	fun serializedSizeMatchesRandomStringsAndNames() {
		val random = java.util.Random(20261124L)

		repeat(2000) {
			val length = random.nextInt(512)

			val value = String(
				CharArray(length) {
					random.nextInt(65536).toChar()
				}
			)

			val primitive =
				kotlinx.serialization.json.JsonPrimitive(value)

			assertSerializedSizeParity(primitive)

			assertSerializedSizeParity(
				kotlinx.serialization.json.JsonObject(
					mapOf(value to primitive)
				)
			)
		}
	}

	@Test
	fun serializedSizeMatchesLargeEscapedString() {
		val value = "\u0000".repeat(100000)

		val element =
			kotlinx.serialization.json.JsonPrimitive(value)

		assertEquals(
			600002L,
			PumpPayloadStreamParser.serializedUtf8ByteCount(element)
		)

		assertSerializedSizeParity(element)
	}

	@Test
	fun serializedSizeRejectsOneByteOverLimit() {
		val element = Json.parseToJsonElement(
			"""{"a":[1,true,null,"abc"]}"""
		)

		val expected = element.toString()
			.toByteArray(Charsets.UTF_8).size.toLong()

		expectTopLevelFailure(
			"Serialized JSON element limit exceeded"
		) {
			PumpPayloadStreamParser.serializedUtf8ByteCount(
				element,
				expected - 1
			)
		}
	}

	@Test
	fun serializedSizeRejectsEscapeExpansionBeforeCallback() {
		var deliveredEvents = 0

		val payload = "{\"events\":[{\"v\":\"" +
			"\\u0000".repeat(100) +
			"\"}]}"

		expectTopLevelFailure(
			"Serialized JSON element limit exceeded"
		) {
			PumpPayloadStreamParser.parse(
				input(payload),
				limits = PumpPayloadStreamParser.Limits(
					maxElementBytes = 605
				),
				onEvent = { _, _ -> deliveredEvents++ }
			)
		}

		assertEquals(0, deliveredEvents)
	}

	@Test
	fun serializedSizeMatchesDeepNestedArrays() {
		val payload =
			"[".repeat(64) + "null" + "]".repeat(64)

		assertSerializedSizeParity(
			Json.parseToJsonElement(payload)
		)
	}
}
