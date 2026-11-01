package com.example.tandemapp.data

import android.content.Context
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
	suspend fun mergeForDisplay(incoming: DayDataset): ChartCacheResult = withContext(Dispatchers.IO) {
		stateMutex.withLock {
			initialize()
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
			val merged = retainCgmOriginals(cached.dataset, incoming, base)
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

	private fun initialize() {
		if (!initialized) {
			current = readEnvelope()
			initialized = true
		}
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
		if (!cacheFile.isFile) return null
		cacheFile.inputStream().buffered().use { json.decodeFromStream<ChartDataCache>(it) }
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
				json.encodeToStream(cache, buffered)
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
