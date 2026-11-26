package com.example.tandemapp.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Planning only: never decodes, deletes or overwrites the legacy cache. */
internal object ChartCacheFallback {
	enum class Action { USE_PUBLISHED, MIGRATION_CANDIDATE, RECOVERY_REQUIRED, UNAVAILABLE }

	/**
	 * Caller must exclude legacy and generation writers for a consistent decision.
	 * A candidate is not permission to migrate: marker compatibility, source
	 * integrity, disk budget and publication prerequisites still need validation.
	 */
	fun decide(recovery: ChartCacheRecovery.Result, legacy: File,
		limits: LegacyCacheExtractor.Limits = LegacyCacheExtractor.Limits()): Action {
		return when (recovery) {
			is ChartCacheRecovery.Result.Published -> Action.USE_PUBLISHED
			ChartCacheRecovery.Result.Invalid -> Action.RECOVERY_REQUIRED
			ChartCacheRecovery.Result.Missing -> {
				val path = legacy.toPath()
				when {
					Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> Action.UNAVAILABLE
					!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
						Action.RECOVERY_REQUIRED
					legacy.length() !in 1..limits.maxInputBytes -> Action.RECOVERY_REQUIRED
					else -> Action.MIGRATION_CANDIDATE
				}
			}
		}
	}
}
