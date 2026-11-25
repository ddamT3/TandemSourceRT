package com.example.tandemapp.data

import com.example.tandemapp.model.CgmPoint
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Validates original provenance, without applying display-time transformations. */
internal object LegacyCgmValidator {
	private val json = Json { ignoreUnknownKeys = true }

	fun validate(directory: File, descriptors: List<PumpPayloadDescriptor>,
		points: List<CgmPoint>, maxPoints: Int = 100000) {
		require(maxPoints > 0 && points.size <= maxPoints)
		val groups = HashMap<Int, MutableMap<Long, MutableList<CgmPoint>>>()
		for (point in points) {
			val source = point.source ?: throw IOException("Missing CGM provenance")
			val descriptor = descriptors.getOrNull(source.payloadIndex)
				?: throw IOException("Invalid CGM payload index")
			val index = source.eventIndex.toLong()
			if (index < 0 || index >= descriptor.eventCount)
				throw IOException("Invalid CGM event index")
			groups.getOrPut(source.payloadIndex) { HashMap() }
				.getOrPut(index) { mutableListOf() }.add(point)
		}
		for ((payloadIndex, descriptor) in descriptors.withIndex()) {
			val reference = descriptor.archiveReference
			require(reference.matches(Regex("[a-zA-Z0-9_-]{1,100}\\.json")))
			val file = File(directory.canonicalFile, reference)
			if (file.canonicalFile != file || !file.isFile ||
				file.length() != descriptor.archiveBytes)
				throw IOException("Invalid CGM archive")
			val pending = groups.remove(payloadIndex) ?: emptyMap()
			var matched = 0
			val digest = MessageDigest.getInstance("SHA-256")
			val result = file.inputStream().use { input ->
				PumpPayloadStreamParser.parse(DigestInputStream(input, digest),
					onEvent = { index, raw ->
						val references = pending[index]
						if (references != null) {
							val event = json.decodeFromJsonElement<PumpEventDto>(raw)
							for (point in references) {
								val expected = PumpEventsAdapter.originalCgm(index.toInt(), event)
									?: throw IOException("Invalid original CGM event")
								val expectedSource = requireNotNull(expected.source).copy(payloadIndex = payloadIndex)
								if (point != expected.copy(source = expectedSource))
									throw IOException("CGM provenance mismatch")
								matched++
							}
						}
					})
			}
			val hash = digest.digest().joinToString("") {
				(it.toInt() and 255).toString(16).padStart(2, '0')
			}
			if (hash != descriptor.sha256 || result.eventCount != descriptor.eventCount ||
				file.length() != descriptor.archiveBytes ||
				matched != pending.values.sumOf { it.size })
				throw IOException("CGM archive integrity mismatch")
		}
	}
}
