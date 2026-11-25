package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Cooperative, non-blocking writer exclusion. The lock file must never be removed. */
internal object ChartCacheTransaction {
	internal fun <T> withWriter(store: File, operation: (File) -> T): T {
		val root = store.canonicalFile
		if (!root.isDirectory) throw IOException("Generation store unavailable")
		val lockFile = File(root, ".writer.lock")
		FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE,
			StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
			val lock = try {
				channel.tryLock()
			} catch (failure: OverlappingFileLockException) {
				throw IOException("Generation writer busy", failure)
			} ?: throw IOException("Generation writer busy")
			lock.use {
				return operation(root)
			}
		}
	}

	/**
	 * Caller must also prevent legacy cache writes for the duration of this operation.
	 * Store directories must already be durably linked. No generation is removed here.
	 */
	fun migrate(source: File, store: File,
		limits: LegacyCacheExtractor.Limits = LegacyCacheExtractor.Limits()): ChartCachePublication.Result =
		withWriter(store) { root ->
			val generations = File(root, "generations")
			if (!generations.isDirectory || generations.canonicalFile != generations)
				throw IOException("Generation directory unavailable")
			// Migration is initial publication only; recovery must handle an existing pointer.
			if (Files.exists(File(root, ChartCacheManifest.CURRENT_FILE).toPath(),
					LinkOption.NOFOLLOW_LINKS))
				throw IOException("Current generation already exists")
			val id = ChartCacheManifest.newGenerationId()
			val stage = File(generations, ".stage-$id")
			val descriptors = LegacyCacheMigration.prepare(source, stage, limits)
			val manifest = ChartCacheManifestFiles.build(stage, id, descriptors, limits)
			ChartCachePublication.publish(root, stage, manifest, limits)
		}
}
