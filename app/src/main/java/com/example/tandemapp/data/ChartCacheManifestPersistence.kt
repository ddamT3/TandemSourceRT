package com.example.tandemapp.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

/** Writes metadata only in private, exclusively owned staging. */
internal object ChartCacheManifestPersistence {
	const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
	const val MAX_CURRENT_BYTES = 1024L
	private val json = Json { encodeDefaults = true }

	@OptIn(ExperimentalSerializationApi::class)
	fun writeManifest(directory: File, manifest: ChartCacheManifest,
		limits: LegacyCacheExtractor.Limits = LegacyCacheExtractor.Limits()): ChartCacheCurrent {
		ChartCacheManifestFiles.validate(directory, manifest, manifest.generationId, limits)
		val target = File(directory.canonicalFile, ChartCacheManifest.MANIFEST_FILE)
		if (!target.createNewFile()) throw IOException("Manifest already exists")
		var complete = false
		try {
			FileOutputStream(target).use { file ->
				val bounded = object : java.io.OutputStream() {
					private var remaining = MAX_MANIFEST_BYTES
					override fun write(value: Int) {
						checkBudget(1)
						file.write(value)
					}
					override fun write(bytes: ByteArray, offset: Int, length: Int) {
						checkBudget(length)
						file.write(bytes, offset, length)
					}
					private fun checkBudget(length: Int) {
						if (length.toLong() > remaining)
							throw IOException("Manifest size limit exceeded")
						if (directory.usableSpace - limits.reserveDiskBytes < length.toLong())
							throw IOException("Insufficient manifest disk space")
						remaining -= length.toLong()
					}
				}
				val output = bounded.buffered(64 * 1024)
				json.encodeToStream(manifest, output)
				output.flush()
				file.fd.sync()
			}
			val fingerprint = ChartCacheManifestFiles.fingerprint(target, MAX_MANIFEST_BYTES)
			val current = ChartCacheCurrent(ChartCacheManifest.SCHEMA_VERSION,
				manifest.generationId, fingerprint.sha256)
			complete = true
			return current
		} finally {
			if (!complete && !target.delete())
				throw IOException("Incomplete manifest cleanup failed")
		}
	}

	fun currentBytes(current: ChartCacheCurrent): ByteArray {
		val bytes = json.encodeToString(current).toByteArray(Charsets.UTF_8)
		if (bytes.size.toLong() > MAX_CURRENT_BYTES)
			throw IOException("Current pointer size limit exceeded")
		return bytes
	}
}
