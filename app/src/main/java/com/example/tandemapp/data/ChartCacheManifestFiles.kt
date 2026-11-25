package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Builds metadata from exclusively owned, validated staging. Does not publish or write files. */
internal object ChartCacheManifestFiles {
	fun build(directory: File, generationId: String, descriptors: List<PumpPayloadDescriptor>,
		limits: LegacyCacheExtractor.Limits = LegacyCacheExtractor.Limits()): ChartCacheManifest {
		require(ChartCacheManifest.isGenerationId(generationId))
		require(descriptors.size <= limits.maxSources)
		val dataset = fingerprint(resolve(directory, ChartCacheManifest.DATASET_FILE), limits.maxDatasetBytes)
		var remaining = limits.maxOutputBytes - dataset.archiveBytes
		if (remaining < 0) throw IOException("Aggregate archive limit exceeded")
		for ((index, descriptor) in descriptors.withIndex()) {
			if (descriptor.archiveReference != "payload_${index}.json")
				throw IOException("Invalid archive reference")
			val file = resolve(directory, descriptor.archiveReference)
			if (descriptor.archiveBytes !in 1..limits.maxPayloadBytes ||
				file.length() != descriptor.archiveBytes)
				throw IOException("Invalid archive size")
			if (descriptor.archiveBytes > remaining)
				throw IOException("Aggregate archive limit exceeded")
			remaining -= descriptor.archiveBytes
		}
		return ChartCacheManifest(ChartCacheManifest.SCHEMA_VERSION, generationId, dataset, descriptors.toList())
	}

	internal fun resolve(directory: File, name: String): File {
		require(name.matches(Regex("[a-zA-Z0-9_-]{1,100}\\.json")))
		val file = File(directory.canonicalFile, name)
		if (file.canonicalFile != file || !file.isFile)
			throw IOException("Generation file unavailable")
		return file
	}

	internal fun fingerprint(file: File, maxBytes: Long): ChartCacheManifest.DatasetArchive {
		require(maxBytes > 0)
		val size = file.length()
		if (!file.isFile || size !in 1..maxBytes) throw IOException("Invalid fingerprint file size")
		val digest = MessageDigest.getInstance("SHA-256")
		val buffer = ByteArray(64 * 1024)
		var remaining = size
		file.inputStream().use { input ->
			while (remaining > 0) {
				val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
				if (count <= 0) throw IOException("Incomplete fingerprint read")
				digest.update(buffer, 0, count)
				remaining -= count.toLong()
			}
			if (input.read() != -1 || file.length() != size)
				throw IOException("Fingerprint file size changed")
		}
		val hash = digest.digest().joinToString("") {
			(it.toInt() and 255).toString(16).padStart(2, '0')
		}
		return ChartCacheManifest.DatasetArchive(size, hash)
	}
}
