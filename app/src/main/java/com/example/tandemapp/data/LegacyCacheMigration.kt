package com.example.tandemapp.data

import com.example.tandemapp.model.DayDataset
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/** Private, unpublished staging. Caller must exclusively own the immutable source and staging. */
internal object LegacyCacheMigration {
	@Serializable
	private data class Envelope(val latestTimestamp: String, val dataset: DayDataset)

	@OptIn(ExperimentalSerializationApi::class)
	fun prepare(source: File, destination: File,
		limits: LegacyCacheExtractor.Limits = LegacyCacheExtractor.Limits()): List<PumpPayloadDescriptor> {
		// Extractor owns cleanup until it returns successfully.
		val descriptors = LegacyCacheExtractor.extract(source, destination, limits)
		var complete = false
		try {
			val file = File(destination, "dataset.json")
			if (!file.isFile || file.length() !in 1..limits.maxDatasetBytes)
				throw IOException("Invalid staged dataset size")
			// Node, token and CGM budgets were checked during extraction.
			// No external writer may modify the private stage between these operations.
			val envelope = file.inputStream().buffered().use {
				Json { ignoreUnknownKeys = true }.decodeFromStream<Envelope>(it)
			}
			if (envelope.dataset.pumpEventsSources.isNotEmpty())
				throw IOException("Unexpected embedded payloads")
			LegacyCgmValidator.validate(destination, descriptors, envelope.dataset.cgm, limits.maxCgmPoints)
			complete = true
			return descriptors
		} finally {
			if (!complete && !destination.deleteRecursively())
				throw IOException("Migration staging cleanup failed")
		}
	}
}
