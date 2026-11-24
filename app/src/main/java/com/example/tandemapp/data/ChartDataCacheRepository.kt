package com.example.tandemapp.data

import android.content.Context
import com.example.tandemapp.config.AppConfig
import com.example.tandemapp.model.DayDataset
import java.io.File
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.Json

@Serializable
private data class ChartDataCache(
	val latestTimestamp: String,
	val dataset: DayDataset
)

data class ChartCacheResult(val dataset: DayDataset, val usedCachedData: Boolean)

/** Keeps the current snapshot and observed original CGM history. Historical requests stay in memory. */
@OptIn(ExperimentalSerializationApi::class)
class ChartDataCacheRepository internal constructor(
	private val cacheFile: File,
  private val today: () -> java.time.LocalDate = { java.time.LocalDate.now() },
	private val replaceFile: (File, File) -> Unit = { temporary, destination ->
		Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
	}
) {
	constructor(context: Context) : this(context.applicationContext.filesDir.resolve("latest_chart_data.json"))
	private val json = Json { ignoreUnknownKeys = true }

	private val stateMutex = Mutex()
	private val writeMutex = Mutex()
	private var initialized = false
	private var current: ChartDataCache? = null
	private var revision = 0L
	private var savedRevision = 0L

	suspend fun read(): DayDataset? = withContext(Dispatchers.IO) {
		stateMutex.withLock { initialize(); current?.dataset }
	}

	/** Merge and publish in memory first; disk persistence is a separate operation. */
	suspend fun mergeForDisplay(incomingData: DayDataset): ChartCacheResult = withContext(Dispatchers.IO) {
		stateMutex.withLock {
			initialize()
                val incoming = compactSources(retainWindow(incomingData))
                val incomingTimestamp = latestTimestamp(incoming)
			val cached = current
			if (cached == null) {
				if (incomingTimestamp != null || incoming.pumpEventsSources.isNotEmpty()) {
					current = ChartDataCache(incomingTimestamp.orEmpty(), incoming)
					revision++
				}
				return@withLock ChartCacheResult(incoming, false)
			}
			// Keep the existing selection rule for every non-CGM series.
			val base = if (incomingTimestamp != null && incomingTimestamp > cached.latestTimestamp)
				incoming else cached.dataset
			val merged = compactSources(retainWindow(retainCgmOriginals(cached.dataset, incoming, base)))
			if (merged == cached.dataset) return@withLock ChartCacheResult(cached.dataset, true)
			current = ChartDataCache(maxOf(cached.latestTimestamp, incomingTimestamp.orEmpty()), merged)
			revision++
			ChartCacheResult(merged, false)
		}
	}

	/** Serialize saves, taking the latest snapshot only after the previous writer finishes. */
	suspend fun save(): Unit = withContext(Dispatchers.IO) {
		writeMutex.withLock {
			val snapshot = stateMutex.withLock {
				if (revision == savedRevision) null else current?.let { it to revision }
			} ?: return@withLock
			write(snapshot.first)
			stateMutex.withLock { savedRevision = snapshot.second }
		}
	}

	// Convenience for callers that explicitly require persistence before returning.
	suspend fun update(incoming: DayDataset): ChartCacheResult {
		val result = mergeForDisplay(incoming)
		save()
		return result
	}

    /**
     * P0C.2-B.3a: limit decoded chart series to HISTORY_DAYS.
     * Raw pump payloads are intentionally untouched until B.3b.
     */
    /**
     * P0C.2-B.3b.3
     *
     * Preserve all raw payloads referenced by CGM and every
     * payload containing clockChanges.
     *
     * Remove only unreferenced payloads without clockChanges.
     *
     * Rebase payloadIndex without modifying eventIndex or
     * the original JSON content of retained payloads.
     */
    private fun compactSources(dataset: DayDataset): DayDataset {
        val sources = dataset.pumpEventsSources
        if (sources.isEmpty()) return dataset

        val referenced = dataset.cgm.mapNotNull {
            it.source?.payloadIndex
        }.toSet()

        // Never silently discard provenance when indices are invalid.
        if (referenced.any { it !in sources.indices }) {
            return dataset
        }

        val retainedIndices = sources.indices.filter { index ->
            index in referenced || sources[index].clockChanges != null
        }

        if (retainedIndices.size == sources.size) {
            return dataset
        }

        val newIndex = retainedIndices.withIndex().associate {
            it.value to it.index
        }

        val rebasedCgm = dataset.cgm.map { point ->
            val metadata = point.source ?: return@map point

            point.copy(
                source = metadata.copy(
                    payloadIndex = newIndex.getValue(metadata.payloadIndex)
                )
            )
        }

        return dataset.copy(
            cgm = rebasedCgm,
            pumpEventsSources = retainedIndices.map { sources[it] }
        )
    }
    private fun retainWindow(dataset: DayDataset): DayDataset {
        val firstDay = today()
            .minusDays(AppConfig.HISTORY_DAYS - 1)

        fun keep(time: String?): Boolean {
            if (time == null || time.length < 10) return true

            val day = runCatching {
                java.time.LocalDate.parse(time.substring(0, 10))
            }.getOrNull() ?: return true

            return !day.isBefore(firstDay)
        }

        return dataset.copy(
            cgm = dataset.cgm.filter { keep(it.time) },
            bolus = dataset.bolus.filter { keep(it.time) },
            carbs = dataset.carbs.filter { keep(it.time) },
            iob = dataset.iob.filter { keep(it.time) },
            basal = dataset.basal.filter { keep(it.time) },
            deviceStates = dataset.deviceStates.filter { keep(it.time) },
            supplementalEvents = dataset.supplementalEvents.filter {
                keep(it.time ?: it.estimatedDateTime)
            }
        )
    }
    private fun initialize() {
        if (!initialized) {
            invalidateObsoleteCache()
            current = readEnvelope()
            initialized = true
        }
    }

    /**
     * P0C.2-B.2: discard incompatible cache before JSON decoding.
     * The cache is reconstructible from the server.
     */
    private fun invalidateObsoleteCache() {
        val marker = cacheFile.resolveSibling("${cacheFile.name}.version")
        val expected = "p0c2b-v3-days-${AppConfig.HISTORY_DAYS}"

        if (marker.isFile && marker.readText() == expected) {
            return
        }

        if (cacheFile.exists() && !cacheFile.delete()) {
            throw IllegalStateException(
                "Unable to delete obsolete chart cache"
            )
        }

        val temporaryMarker = marker.resolveSibling("${marker.name}.tmp")
        temporaryMarker.writeText(expected)

        try {
            java.nio.file.Files.move(
                temporaryMarker.toPath(),
                marker.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (temporaryMarker.exists()) {
                temporaryMarker.delete()
            }
        }

        runCatching { android.util.Log.i(
            "P0C2B_CACHE",
            "Cache invalidated for HISTORY_DAYS=${AppConfig.HISTORY_DAYS}"
        ) }
    }

	private fun retainCgmOriginals(cached: DayDataset, incoming: DayDataset, base: DayDataset): DayDataset {
		val sources = cached.pumpEventsSources.toMutableList()
		val sourceIndices = incoming.pumpEventsSources.map { source ->
			val existing = sources.indexOf(source)
			if (existing >= 0) existing else sources.size.also { sources += source }
		}
		val rebased = incoming.cgm.map { point ->
			point.source?.let { source ->
				point.copy(source = source.copy(payloadIndex = sourceIndices.getOrElse(source.payloadIndex) {
					cached.pumpEventsSources.size + source.payloadIndex
				}))
			} ?: point
		}
		// Preserve multiplicity within each snapshot; do not append an identical poll repeatedly.
		val remaining = cached.cgm.groupingBy { it }.eachCount().toMutableMap()
		val additions = rebased.filter { point ->
			val count = remaining[point] ?: 0
			if (count == 0) true else { remaining[point] = count - 1; false }
		}
		return base.copy(cgm = (cached.cgm + additions).sortedBy { it.time }, pumpEventsSources = sources)
	}

	private fun readEnvelope(): ChartDataCache? = runCatching {
        runCatching { StartupMemoryProfiler.sample("P0B2A_CACHE_before_read") }

        if (!cacheFile.isFile) {
            runCatching { StartupMemoryProfiler.sample("P0B2A_CACHE_missing") }
            return null
        }

        val decoded = cacheFile.inputStream().buffered().use {
            StartupMemoryProfiler.measure("cache_decode") { json.decodeFromStream<ChartDataCache>(it) }
        }

        runCatching { StartupMemoryProfiler.sample("P0B2A_CACHE_after_decode") }

        val dataset = decoded.dataset

        runCatching { android.util.Log.i(
            "P0B2B_DATASET",
            "cacheFileMiB=${cacheFile.length() / (1024L * 1024L)} " +
                "cgm=${dataset.cgm.size} " +
                "bolus=${dataset.bolus.size} " +
                "carbs=${dataset.carbs.size} " +
                "iob=${dataset.iob.size} " +
                "basal=${dataset.basal.size} " +
                "deviceStates=${dataset.deviceStates.size} " +
                "supplementalEvents=${dataset.supplementalEvents.size} " +
                "pumpEventsSources=${dataset.pumpEventsSources.size}"
        ) }

        runCatching { StartupMemoryProfiler.sample("P0B2B_before_gc") }
        System.gc()
        Thread.sleep(500L)
        runCatching { StartupMemoryProfiler.sample("P0B2B_after_gc") }
        decoded
    }.getOrNull()

	private fun latestTimestamp(data: DayDataset): String? = buildList {
		addAll(data.cgm.map { it.time })
		addAll(data.bolus.map { it.time })
		addAll(data.carbs.map { it.time })
		addAll(data.iob.map { it.time })
		addAll(data.basal.map { it.time })
		addAll(data.deviceStates.map { it.time })
		addAll(data.supplementalEvents.mapNotNull { it.time })
	}.maxOrNull()

	private fun write(cache: ChartDataCache) {
		val temporary = File.createTempFile("${cacheFile.name}.", ".tmp", cacheFile.absoluteFile.parentFile)
		try {
			temporary.outputStream().use { output ->
				val buffered = output.buffered()
				StartupMemoryProfiler.measure("cache_encode") { json.encodeToStream(cache, buffered) }
				buffered.flush()
				output.fd.sync()
			}
			// Never fall back to overwriting the previous file in place.
			replaceFile(temporary, cacheFile)
		} finally {
			temporary.delete()
		}
	}
}
