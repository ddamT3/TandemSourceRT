package com.example.tandemapp.data

import android.content.Context
import com.example.tandemapp.model.DayDataset
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ChartDataCache(
	val latestTimestamp: String,
	val dataset: DayDataset
)

data class ChartCacheResult(val dataset: DayDataset, val usedCachedData: Boolean)

/** Stores only the latest current-period chart dataset. Historical requests stay in memory. */
class ChartDataCacheRepository(context: Context) {
	private val json = Json { ignoreUnknownKeys = true }
	private val cacheFile = context.applicationContext.filesDir.resolve("latest_chart_data.json")

	fun read(): DayDataset? = readEnvelope()?.dataset

	fun update(incoming: DayDataset): ChartCacheResult {
		val incomingTimestamp = latestTimestamp(incoming)
		val cached = readEnvelope()
		if (incomingTimestamp == null) {
			return cached?.let { ChartCacheResult(it.dataset, true) }
				?: ChartCacheResult(incoming, false)
		}
		if (cached != null && incomingTimestamp <= cached.latestTimestamp) {
			return ChartCacheResult(cached.dataset, true)
		}
		val updated = ChartDataCache(incomingTimestamp, incoming)
		write(updated)
		return ChartCacheResult(incoming, false)
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
