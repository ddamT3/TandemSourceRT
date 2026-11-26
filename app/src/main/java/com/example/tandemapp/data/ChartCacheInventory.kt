package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Read-only inventory. Caller must exclude writers and deletion during inspection. */
internal object ChartCacheInventory {
	enum class Kind { PUBLISHED, STAGING, UNREFERENCED, UNRESOLVED, UNKNOWN }
	data class Entry(val name: String, val kind: Kind)
	data class Result(val entries: List<Entry>, val truncated: Boolean)

	fun inspect(store: File, recovery: ChartCacheRecovery.Result,
		maxEntries: Int = 256): Result {
		require(maxEntries in 1..4096)
		val root = store.canonicalFile
		val generations = File(root, "generations")
		if (!root.isDirectory || generations.canonicalFile != generations ||
			!Files.isDirectory(generations.toPath(), LinkOption.NOFOLLOW_LINKS))
			throw IOException("Generation directory unavailable")
		val published = (recovery as? ChartCacheRecovery.Result.Published)?.directory
		if (published != null && published.parentFile != generations)
			throw IOException("Recovery belongs to another store")
		val entries = mutableListOf<Entry>()
		var truncated = false
		Files.newDirectoryStream(generations.toPath()).use { stream ->
			for (path in stream) {
				if (entries.size == maxEntries) {
					truncated = true
					break
				}
				val name = path.fileName.toString()
				val directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
				val kind = when {
					!directory -> Kind.UNKNOWN
					name.startsWith(".stage-") &&
						ChartCacheManifest.isGenerationId(name.removePrefix(".stage-")) -> Kind.STAGING
					!ChartCacheManifest.isGenerationId(name) -> Kind.UNKNOWN
					path.toFile() == published -> Kind.PUBLISHED
					recovery is ChartCacheRecovery.Result.Published -> Kind.UNREFERENCED
					else -> Kind.UNRESOLVED
				}
				entries.add(Entry(name, kind))
			}
		}
		return Result(entries.sortedBy { it.name }, truncated)
	}
}
