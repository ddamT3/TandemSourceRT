package com.example.tandemapp.data

import java.util.UUID
import kotlinx.serialization.Serializable

/** Versioned metadata for one immutable generation; not a publication marker. */
@Serializable
internal data class ChartCacheManifest(
	val schemaVersion: Int,
	val generationId: String,
	val dataset: DatasetArchive,
	val payloads: List<PumpPayloadDescriptor>
) {
	@Serializable
	data class DatasetArchive(
		val archiveBytes: Long,
		val sha256: String
	)

	init {
		require(schemaVersion == SCHEMA_VERSION)
		require(isGenerationId(generationId))
		require(dataset.archiveBytes > 0 && isSha256(dataset.sha256))
		require(payloads.size <= 128)
		payloads.forEachIndexed { index, descriptor ->
			require(descriptor.archiveReference == "payload_${index}.json")
			require(descriptor.archiveBytes > 0 && descriptor.eventCount >= 0)
			require(isSha256(descriptor.sha256))
		}
	}

	companion object {
		const val SCHEMA_VERSION = 1
		const val DATASET_FILE = "dataset.json"
		const val MANIFEST_FILE = "manifest.json"
		const val CURRENT_FILE = "current.json"

		private val generationPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
		private val sha256Pattern = Regex("[0-9a-f]{64}")

		fun newGenerationId(): String = UUID.randomUUID().toString()
		fun isGenerationId(value: String): Boolean = generationPattern.matches(value)
		fun isSha256(value: String): Boolean = sha256Pattern.matches(value)
	}
}

/** Pointer to a committed generation, bound to the exact serialized manifest. */
@Serializable
internal data class ChartCacheCurrent(
	val schemaVersion: Int,
	val generationId: String,
	val manifestSha256: String
) {
	init {
		require(schemaVersion == ChartCacheManifest.SCHEMA_VERSION)
		require(ChartCacheManifest.isGenerationId(generationId))
		require(ChartCacheManifest.isSha256(manifestSha256))
	}
}
