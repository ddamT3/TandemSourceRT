package com.example.tandemapp.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Prototype: caller must hold exclusive ownership of store and validated staging. */
internal object ChartCachePublication {
	enum class Result { COMMITTED, COMMIT_DURABILITY_UNCERTAIN }

	/**
	 * Store and generations directory must already exist and be durably linked.
	 * Staging must be a direct child of generations named .stage-<generationId>.
	 * Never deletes generations, including on an ambiguous move failure.
	 */
	fun publish(store: File, stage: File, manifest: ChartCacheManifest,
		limits: LegacyCacheExtractor.Limits = LegacyCacheExtractor.Limits(),
		syncDirectory: (File) -> Unit = ::forceDirectory): Result {
		val root = store.canonicalFile
		val generations = File(root, "generations")
		if (!root.isDirectory || !generations.isDirectory ||
			generations.canonicalFile != generations)
			throw IOException("Generation store unavailable")
		val expectedStage = File(generations, ".stage-${manifest.generationId}")
		if (stage.canonicalFile != expectedStage || !expectedStage.isDirectory ||
			Files.isSymbolicLink(expectedStage.toPath()))
			throw IOException("Invalid staging directory")
		val target = File(generations, manifest.generationId)
		if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
			throw IOException("Generation already exists")
		val pointer = File(root, ChartCacheManifest.CURRENT_FILE)
		if (Files.exists(pointer.toPath(), LinkOption.NOFOLLOW_LINKS) &&
			(!pointer.isFile || pointer.canonicalFile != pointer))
			throw IOException("Invalid current pointer")

		val current = ChartCacheManifestPersistence.writeManifest(expectedStage, manifest, limits)
		// Extraction synced archive files; persist their directory entries before rename.
		syncDirectory(expectedStage)
		Files.move(expectedStage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
		syncDirectory(generations)
		val fingerprint = ChartCacheManifestFiles.fingerprint(
			ChartCacheManifestFiles.resolve(target, ChartCacheManifest.MANIFEST_FILE),
			ChartCacheManifestPersistence.MAX_MANIFEST_BYTES)
		if (fingerprint.sha256 != current.manifestSha256)
			throw IOException("Moved manifest integrity mismatch")

		val bytes = ChartCacheManifestPersistence.currentBytes(current)
		if (root.usableSpace - limits.reserveDiskBytes < bytes.size.toLong())
			throw IOException("Insufficient pointer disk space")
		val temporary = File.createTempFile(".current-", ".tmp", root)
		try {
			FileOutputStream(temporary).use {
				it.write(bytes)
				it.flush()
				it.fd.sync()
			}
			// Check directory sync support before crossing the commit boundary.
			syncDirectory(root)
			Files.move(temporary.toPath(), pointer.toPath(),
				StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
			return try {
				syncDirectory(root)
				Result.COMMITTED
			} catch (failure: Exception) {
				// Pointer may already be visible. Never roll back or delete the generation.
				Result.COMMIT_DURABILITY_UNCERTAIN
			}
		} finally {
			temporary.delete()
		}
	}

	private fun forceDirectory(directory: File) {
		FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
	}
}
