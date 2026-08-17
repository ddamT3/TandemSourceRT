package com.example.tandemapp.data

import android.content.Context
import android.util.Log
import com.example.tandemapp.model.PumpProfile
import com.example.tandemapp.model.PumpProfileDto
import com.example.tandemapp.model.PumpProfileSegment
import com.example.tandemapp.model.PumpSettingsCacheDto
import com.example.tandemapp.model.PumpSettingsData
import com.example.tandemapp.model.ReportsPumperDto
import com.example.tandemapp.model.ReportsPumpDto
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class TandemAuthContext(val accessToken: String, val pumperId: String)

sealed interface TandemAuthContextResult {
	data class Success(val context: TandemAuthContext) : TandemAuthContextResult
	data class Failure(val message: String) : TandemAuthContextResult
}

sealed interface PumpSettingsResult {
	data class Success(val data: PumpSettingsData) : PumpSettingsResult
	data class Failure(val message: String) : PumpSettingsResult
}

class PumpSettingsRepository(
	context: Context,
	private val authProvider: suspend (String, String) -> TandemAuthContextResult
) {
	private val json = Json { ignoreUnknownKeys = true }
	private val prettyJson = Json { prettyPrint = true }
	private val cacheFile = context.applicationContext.filesDir.resolve("latest_pump_settings.json")

	suspend fun loadCurrent(
		username: String,
		password: String
	): PumpSettingsResult = withContext(Dispatchers.IO) {
		val requestedDate = LocalDate.now()
		val cached = readCache()

		val auth = when (val result = authProvider(username, password)) {
			is TandemAuthContextResult.Success -> result.context
			is TandemAuthContextResult.Failure -> {
				if (cached != null) return@withContext PumpSettingsResult.Success(
					mapPump(cached.pump, requestedDate, cached.readAt, true)
				)
				return@withContext PumpSettingsResult.Failure("Tandem authentication failed")
			}
		}

		try {
			val reports = getReportsPumper(auth)
			val currentPump = selectCurrentPump(reports.pumps)
				?: throw IllegalStateException("No pump settings available")

			val readAt = Instant.now().toString()
			val pumpToShow = if (cached != null && settingsTimestamp(cached.pump) > settingsTimestamp(currentPump)) {
				cached.pump
			} else {
				currentPump
			}
			val effectiveReadAt = if (pumpToShow === cached?.pump) cached.readAt else readAt

			if (cached == null || settingsTimestamp(currentPump) > settingsTimestamp(cached.pump)) {
				writeCache(PumpSettingsCacheDto(readAt = readAt, pump = currentPump))
			}

			PumpSettingsResult.Success(
				mapPump(pumpToShow, requestedDate, effectiveReadAt, false)
			)
		} catch (e: Exception) {
			Log.e("PumpSettingsRepo", "Failed to load pump settings for $requestedDate", e)
			if (cached != null) {
				PumpSettingsResult.Success(mapPump(cached.pump, requestedDate, cached.readAt, true))
			} else {
				PumpSettingsResult.Failure(e.message ?: "Unable to load pump settings")
			}
		}
	}

	private fun getReportsPumper(auth: TandemAuthContext): ReportsPumperDto {
		return json.decodeFromString(ReportsPumperDto.serializer(), getReportsPumperJson(auth))
	}

	fun exportRaw(auth: TandemAuthContext, exportDir: File): File {
		val root = json.parseToJsonElement(getReportsPumperJson(auth)).jsonObject
		val pump = root["pumps"]?.jsonArray.orEmpty()
			.map { it.jsonObject }
			.filter { it["settings"] != null && it["settings"] != JsonNull }
			.maxByOrNull { rawPumpTimestamp(it) }
			?: throw IllegalStateException("No pump with settings available")
		if (!exportDir.exists() && !exportDir.mkdirs()) {
			throw IllegalStateException("Unable to create export directory")
		}
		val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
			.withZone(ZoneOffset.UTC).format(Instant.now())
		val assignmentId = pump["assignmentId"]?.jsonPrimitive?.contentOrNull ?: "unknown"
		val serialNumber = pump["serialNumber"]?.jsonPrimitive?.contentOrNull ?: "unknown"
		val payload = buildJsonObject {
				put("source", "reports-bff-pumper")
				put("pumperId", auth.pumperId)
				put("assignmentId", assignmentId)
				put("serialNumber", serialNumber)
				put("modelNumber", pump["modelNumber"] ?: JsonNull)
				put("modelName", pump["modelName"] ?: JsonNull)
				put("assignedAt", pump["assignedAt"] ?: JsonNull)
				put("lastUpload", pump["lastUpload"] ?: pump["lastUploadDate"] ?: JsonNull)
				put("settings", pump["settings"] ?: JsonNull)
		}
		return File(
			exportDir,
			"pump_settings_${safeFilenamePart(serialNumber)}_" +
				"${safeFilenamePart(assignmentId)}_${timestamp}.json"
		).apply { writeText(prettyJson.encodeToString(payload), Charsets.UTF_8) }
	}

	private fun rawPumpTimestamp(pump: kotlinx.serialization.json.JsonObject): Long {
		val lastUpload = pump["lastUpload"]?.jsonPrimitive?.contentOrNull
			?: pump["lastUploadDate"]?.jsonPrimitive?.contentOrNull
		val settingsTimestamp = pump["settings"]?.jsonObject
			?.get("uploadedTimeStamp")?.jsonPrimitive?.contentOrNull
		return parseTimestamp(lastUpload) ?: parseTimestamp(settingsTimestamp) ?: Long.MIN_VALUE
	}

	private fun getReportsPumperJson(auth: TandemAuthContext): String =
		getJson(
			"https://source.eu.tandemdiabetes.com/api/reports/bff/pumper/${auth.pumperId}",
			auth.accessToken
		)

	private fun getJson(url: String, accessToken: String): String {
		val connection = URL(url).openConnection() as HttpURLConnection
		return try {
			connection.requestMethod = "GET"
			connection.connectTimeout = 30_000
			connection.readTimeout = 60_000
			connection.setRequestProperty("Accept", "application/json")
			connection.setRequestProperty("Authorization", "Bearer $accessToken")
			connection.setRequestProperty("User-Agent", "TandemSourceRT/Android")

			val status = connection.responseCode
			val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
				?.bufferedReader()?.use { it.readText() }.orEmpty()
			if (status != HttpURLConnection.HTTP_OK) {
				throw IllegalStateException("Pump Settings HTTP error $status")
			}
			body
		} finally {
			connection.disconnect()
		}
	}

	private fun selectCurrentPump(pumps: List<ReportsPumpDto>): ReportsPumpDto? =
		pumps.filter { it.settings != null }.maxByOrNull { pumpSelectionTimestamp(it) }

	private fun mapPump(
		pump: ReportsPumpDto,
		requestedDate: LocalDate,
		readAt: String,
		isFromCache: Boolean
	): PumpSettingsData {
		val envelope = requireNotNull(pump.settings)
		val details = envelope.details
		val activeId = details.profiles.activeIdp
		val profiles = details.profiles.profile
			.map { mapProfile(it, it.idp == activeId) }
			.sortedByDescending { it.isActive }

		return PumpSettingsData(
			requestedDate = requestedDate.toString(),
			readAt = readAt,
			isFromCache = isFromCache,
			serialNumber = pump.serialNumber,
			modelName = pump.modelName ?: "Tandem pump",
			softwareVersion = pump.softwareVersion,
			algorithm = pump.algorithm,
			lastUploadDate = pump.lastUploadDate ?: pump.lastUpload,
			settingsTimestamp = envelope.uploadedTimeStamp,
			activeProfileName = profiles.firstOrNull { it.isActive }?.name,
			profiles = profiles,
			basalLimitUnitsPerHour = details.basalLimitSettings?.basalLimit?.div(1000.0),
			maxBolusUnits = details.globalMaxBolusSettings?.maxBolus?.div(1000.0),
			pumpSettings = details.pumpSettings,
			cgmSettings = details.cgmSettings,
			controlIqSettings = details.controlIqSettings,
			reminders = details.reminders,
			glucoseUnit = if (details.localizationSettings?.glucoseUom == "MillimolesPerLiter") "mmol/L" else "mg/dl"
		)
	}

	private fun mapProfile(profile: PumpProfileDto, active: Boolean): PumpProfile {
		val segments = profile.timeDependentSegments.sortedBy { it.startTime }.map {
			PumpProfileSegment(
				startMinutes = it.startTime,
				basalUnitsPerHour = it.basalRate / 1000.0,
				correctionFactor = it.isf,
				carbRatioGrams = it.carbRatio / 1000.0,
				targetMgDl = it.targetBg
			)
		}
		val dailyBasal = segments.indices.sumOf { index ->
			val end = segments.getOrNull(index + 1)?.startMinutes ?: 24 * 60
			segments[index].basalUnitsPerHour * (end - segments[index].startMinutes) / 60.0
		}
		return PumpProfile(
			id = profile.idp,
			name = profile.name,
			isActive = active,
			carbEntryEnabled = profile.carbEntry == "UnitsAsCarbs",
			maxBolusUnits = profile.maxBolus / 1000.0,
			insulinDurationMinutes = profile.insulinDuration,
			dailyBasalUnits = dailyBasal,
			segments = segments
		)
	}

	private fun readCache(): PumpSettingsCacheDto? = runCatching {
		if (!cacheFile.isFile) return null
		json.decodeFromString(PumpSettingsCacheDto.serializer(), cacheFile.readText())
	}.getOrNull()

	private fun writeCache(cache: PumpSettingsCacheDto) {
		val temporary = cacheFile.resolveSibling("${cacheFile.name}.tmp")
		temporary.writeText(json.encodeToString(cache))
		if (!temporary.renameTo(cacheFile)) {
			cacheFile.writeText(temporary.readText())
			temporary.delete()
		}
	}

	private fun pumpSelectionTimestamp(pump: ReportsPumpDto): Long =
		parseTimestamp(pump.lastUploadDate ?: pump.lastUpload)
			?: parseTimestamp(pump.settings?.uploadedTimeStamp)
			?: Long.MIN_VALUE

	private fun settingsTimestamp(pump: ReportsPumpDto): Long =
		parseTimestamp(pump.settings?.uploadedTimeStamp) ?: Long.MIN_VALUE

	private fun parseTimestamp(raw: String?): Long? {
		if (raw.isNullOrBlank()) return null
		return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
			?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
			?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
	}

	private fun safeFilenamePart(value: String): String =
		value.replace(Regex("[^A-Za-z0-9._-]+"), "_")
}
