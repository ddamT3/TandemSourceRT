package com.example.tandemapp.data

import android.content.res.AssetManager
import android.util.Log
import com.example.tandemapp.model.BasalPoint
import com.example.tandemapp.model.BolusEvent
import com.example.tandemapp.model.CarbEvent
import com.example.tandemapp.model.CgmPoint
import com.example.tandemapp.model.DayDataset
import com.example.tandemapp.model.DeviceStateEvent
import com.example.tandemapp.model.IobPoint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JsonTandemRepository(
	private val assets: AssetManager
) {
	private val tag = "JsonTandemRepository"

	private val json = Json {
		ignoreUnknownKeys = true
	}

	private val historyBasePath = "datasets/history"

	private val cgm: List<CgmPoint> by lazy {
		loadFile("$historyBasePath/cgm.json", ListSerializer(CgmPoint.serializer()), "cgm")
	}

	private val bolus: List<BolusEvent> by lazy {
		loadFile("$historyBasePath/bolus.json", ListSerializer(BolusEvent.serializer()), "bolus")
	}

	private val carbs: List<CarbEvent> by lazy {
		loadFile("$historyBasePath/cho.json", ListSerializer(CarbEvent.serializer()), "cho")
	}

	private val iob: List<IobPoint> by lazy {
		loadFile("$historyBasePath/iob.json", ListSerializer(IobPoint.serializer()), "iob")
	}

	private val basal: List<BasalPoint> by lazy {
		loadFile("$historyBasePath/basal.json", ListSerializer(BasalPoint.serializer()), "basal")
	}

	private val deviceStates: List<DeviceStateEvent> by lazy {
		loadFile("$historyBasePath/deviceState.json", ListSerializer(DeviceStateEvent.serializer()), "deviceState")
	}

	fun getAvailableDates(): List<LocalDate> {
		val dates = cgm
			.mapNotNull { parseTime(it.time)?.toLocalDate() }
			.distinct()
			.sorted()

		Log.d(tag, "available dates from history = $dates")
		return dates
	}

	fun loadAllHistory(): DayDataset {
		Log.d(
			tag,
			"loadAllHistory -> cgm=${cgm.size}, bolus=${bolus.size}, carbs=${carbs.size}, " +
				"iob=${iob.size}, basal=${basal.size}, deviceStates=${deviceStates.size}"
		)

		return DayDataset(
			cgm = cgm,
			bolus = bolus,
			carbs = carbs,
			iob = iob,
			basal = basal,
			deviceStates = deviceStates
		)
	}

	fun loadDay(date: LocalDate): DayDataset {
		val start = date.atStartOfDay().atOffset(ZoneOffset.UTC)
		val end = start.plusDays(1)
		return loadRange(start, end)
	}

	fun loadRange(
		start: OffsetDateTime,
		end: OffsetDateTime
	): DayDataset {
		val filteredCgm = filterRange(cgm, { it.time }, start, end)
		val filteredBolus = filterRange(bolus, { it.time }, start, end)
		val filteredCarbs = filterRange(carbs, { it.time }, start, end)
		val filteredIob = filterRange(iob, { it.time }, start, end)
		val filteredBasal = filterRange(basal, { it.time }, start, end)
		val filteredDeviceStates = filterRange(deviceStates, { it.time }, start, end)

		Log.d(
			tag,
			"loadRange start=$start end=$end -> " +
				"cgm=${filteredCgm.size}, bolus=${filteredBolus.size}, carbs=${filteredCarbs.size}, " +
				"iob=${filteredIob.size}, basal=${filteredBasal.size}, deviceStates=${filteredDeviceStates.size}"
		)

		return DayDataset(
			cgm = filteredCgm,
			bolus = filteredBolus,
			carbs = filteredCarbs,
			iob = filteredIob,
			basal = filteredBasal,
			deviceStates = filteredDeviceStates
		)
	}

	private fun <T> loadFile(
		path: String,
		serializer: KSerializer<List<T>>,
		label: String
	): List<T> {
		return try {
			Log.d(tag, "trying to open asset path=$path")
			val text = assets.open(path).bufferedReader().use { it.readText() }
			val result = json.decodeFromString(serializer, text)
			Log.d(tag, "loaded $label rows=${result.size} from $path")
			result
		} catch (e: Exception) {
			Log.e(tag, "failed loading $label from $path", e)
			emptyList()
		}
	}

	private fun parseTime(raw: String): OffsetDateTime? {
		return try {
			OffsetDateTime.parse(raw)
		} catch (_: Exception) {
			try {
				LocalDateTime.parse(raw).atOffset(ZoneOffset.UTC)
			} catch (_: Exception) {
				null
			}
		}
	}

	private fun <T> filterRange(
		list: List<T>,
		getTime: (T) -> String,
		start: OffsetDateTime,
		end: OffsetDateTime
	): List<T> {
		return list
			.filter { item ->
				val time = parseTime(getTime(item)) ?: return@filter false
				!time.isBefore(start) && time.isBefore(end)
			}
			.sortedBy { item ->
				parseTime(getTime(item))?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
			}
	}
}