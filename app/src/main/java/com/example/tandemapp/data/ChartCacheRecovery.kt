package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** Read-only recovery of the explicitly published generation. No orphan promotion. */
internal object ChartCacheRecovery {
	sealed class Result {
		object Missing : Result()
		object Invalid : Result()
		data class Published(val directory: File, val manifest: ChartCacheManifest) : Result()
	}

	/** Caller must preserve immutable generations and prevent concurrent deletion. */
	fun inspect(store: File,
		limits: LegacyCacheExtractor.Limits = LegacyCacheExtractor.Limits()): Result {
		return try {
			val root = store.canonicalFile
			val pointer = File(root, ChartCacheManifest.CURRENT_FILE)
			if (Files.notExists(pointer.toPath(), LinkOption.NOFOLLOW_LINKS))
				return Result.Missing
			val current = Json.decodeFromString<ChartCacheCurrent>(readMetadata(
				pointer, ChartCacheManifestPersistence.MAX_CURRENT_BYTES).decodeToString(throwOnInvalidSequence = true))
			val generations = File(root, "generations")
			val directory = File(generations, current.generationId)
			if (generations.canonicalFile != generations ||
				directory.canonicalFile != directory || !directory.isDirectory)
				throw IOException("Invalid generation directory")
			val bytes = readMetadata(File(directory, ChartCacheManifest.MANIFEST_FILE),
				ChartCacheManifestPersistence.MAX_MANIFEST_BYTES)
			val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
				(it.toInt() and 255).toString(16).padStart(2, '0')
			}
			if (hash != current.manifestSha256) throw IOException("Manifest integrity mismatch")
			val manifest = Json.decodeFromString<ChartCacheManifest>(
				bytes.decodeToString(throwOnInvalidSequence = true))
			ChartCacheManifestFiles.validate(directory, manifest, current.generationId, limits)
			Result.Published(directory, manifest)
		} catch (_: IOException) {
			Result.Invalid
		} catch (_: IllegalArgumentException) {
			Result.Invalid
		}
	}

	private fun readMetadata(file: File, maximum: Long): ByteArray {
		if (file.canonicalFile != file || !file.isFile)
			throw IOException("Metadata unavailable")
		val size = file.length()
		if (size !in 1..maximum) throw IOException("Metadata size limit exceeded")
		val bytes = ByteArray(size.toInt())
		file.inputStream().use { input ->
			var offset = 0
			while (offset < bytes.size) {
				val count = input.read(bytes, offset, bytes.size - offset)
				if (count <= 0) throw IOException("Incomplete metadata")
				offset += count
			}
			if (input.read() != -1) throw IOException("Metadata size changed")
		}
		return bytes
	}
}
