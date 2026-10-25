package com.example.tandemapp.data

import android.content.Context
import com.example.tandemapp.model.DayDataset
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ChartDataCache(
	val latestTimestamp: String,
	val dataset: DayDataset
)

data class ChartCacheResult(val dataset: DayDataset, val usedCachedData: Boolean)

/** Keeps the current snapshot and observed original CGM history. Historical requests stay in memory. */
class ChartDataCacheRepository internal constructor(private val cacheFile: File) {
	constructor(context: Context) : this(context.applicationContext.filesDir.resolve("latest_chart_data.json"))
	private val json = Json { ignoreUnknownKeys = true }

	fun read(): DayDataset? = readEnvelope()?.dataset

	fun update(incoming: DayDataset): ChartCacheResult {
		val incomingTimestamp = latestTimestamp(incoming)
		val cached = readEnvelope()
		if (cached == null) {
			if (incomingTimestamp != null || incoming.pumpEventsSources.isNotEmpty()) {
				write(ChartDataCache(incomingTimestamp.orEmpty(), incoming))
			}
			return ChartCacheResult(incoming, false)
		}
		// Keep the existing selection rule for every non-CGM series.
		val base = if (incomingTimestamp != null && incomingTimestamp > cached.latestTimestamp)
			incoming else cached.dataset
		val merged = retainCgmOriginals(cached.dataset, incoming, base)
		if (merged == cached.dataset) return ChartCacheResult(cached.dataset, true)
		write(ChartDataCache(maxOf(cached.latestTimestamp, incomingTimestamp.orEmpty()), merged))
		return ChartCacheResult(merged, false)
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
		json.decodeFromString<ChartDataCache>(cacheFile.readText())
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
		val temporary = cacheFile.resolveSibling("${cacheFile.name}.tmp")
		temporary.writeText(json.encodeToString(cache))
		if (!temporary.renameTo(cacheFile)) {
			cacheFile.writeText(temporary.readText())
			temporary.delete()
		}
	}
}
