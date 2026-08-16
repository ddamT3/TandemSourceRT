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
				return@withContext PumpSettingsResult.Failure(result.message)
			}
		}

		try {
			val reports = getReportsPumper(auth)
			val currentPump = selectCurrentPump(reports.pumps)
				?: throw IllegalStateException("Nessuna impostazione pompa disponibile")

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
			Log.e("PumpSettingsRepo", "Caricamento settings fallito per $requestedDate", e)
			if (cached != null) {
				PumpSettingsResult.Success(mapPump(cached.pump, requestedDate, cached.readAt, true))
			} else {
				PumpSettingsResult.Failure(e.message ?: "Caricamento impostazioni pompa non riuscito")
			}
		}
	}

	private fun getReportsPumper(auth: TandemAuthContext): ReportsPumperDto {
		val body = getJson(
			"https://source.eu.tandemdiabetes.com/api/reports/bff/pumper/${auth.pumperId}",
			auth.accessToken
		)
		return json.decodeFromString(ReportsPumperDto.serializer(), body)
	}

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
				throw IllegalStateException("Errore Pump Settings HTTP $status")
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
			modelName = pump.modelName ?: "Pompa Tandem",
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
}
