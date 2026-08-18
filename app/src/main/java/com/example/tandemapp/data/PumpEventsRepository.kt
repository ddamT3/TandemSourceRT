package com.example.tandemapp.data

import com.example.tandemapp.model.DayDataset
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PumpEventsExportResult(
	val files: List<File>
)

class PumpEventsRepository {
	private val json = Json { ignoreUnknownKeys = true }
	private data class Assignment(
		val id: String,
		val serialNumber: String,
		val lastUpload: String?
	)

	fun load(auth: TandemAuthContext, selectedDate: LocalDate?): DayDataset {
		val assignments = getAssignments(auth)
		if (assignments.isEmpty()) throw IllegalStateException("Nessun assignmentId disponibile")
		return merge(assignments.map { PumpEventsAdapter.decode(downloadEvents(auth, it.id, selectedDate)) })
	}

	fun exportRaw(auth: TandemAuthContext, selectedDate: LocalDate?, exportDir: File): PumpEventsExportResult {
		val assignments = listOfNotNull(getLatestReportsAssignment(auth))
		if (assignments.isEmpty()) throw IllegalStateException("No current pump assignment available")
		if (!exportDir.exists() && !exportDir.mkdirs()) {
			throw IllegalStateException("Unable to create export directory")
		}
		val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
			.withZone(ZoneOffset.UTC).format(java.time.Instant.now())
		val datePart = selectedDate?.toString() ?: "latest"
		val files = assignments.mapNotNull { assignment ->
			val body = downloadEvents(auth, assignment.id, selectedDate)
			val root = json.parseToJsonElement(body).jsonObject
			val events = root["events"]?.jsonArray
				?: throw IllegalStateException("Pump-event response does not contain an events array")
			if (events.isEmpty()) {
				return@mapNotNull null
			}
			File(
				exportDir,
				"full_pump_logs_${datePart}_${safeFilenamePart(assignment.serialNumber)}_" +
					"${safeFilenamePart(assignment.id)}_${timestamp}.json"
			).apply { writeText(body, Charsets.UTF_8) }
		}
		return PumpEventsExportResult(files)
	}

	private fun getAssignments(auth: TandemAuthContext): List<Assignment> {
		val body = getJson(
			"https://source.eu.tandemdiabetes.com/api/pumpers/pumpers/${auth.pumperId}",
			auth.accessToken,
			60_000
		)
		val root = json.parseToJsonElement(body).jsonObject
		return root["devices"]?.jsonArray.orEmpty().mapNotNull { item ->
			val device = item.jsonObject
			val id = device["assignmentId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
			Assignment(
				id = id,
				serialNumber = device["serialNumber"]?.jsonPrimitive?.contentOrNull ?: "unknown",
				lastUpload = device["lastUpload"]?.jsonPrimitive?.contentOrNull
			)
		}.distinctBy { it.id }
	}

	private fun getLatestReportsAssignment(auth: TandemAuthContext): Assignment? {
		val body = getJson(
			"https://source.eu.tandemdiabetes.com/api/reports/bff/pumper/${auth.pumperId}",
			auth.accessToken,
			60_000
		)
		val pumps = json.parseToJsonElement(body).jsonObject["pumps"]?.jsonArray.orEmpty()
			.mapNotNull { item ->
				val pump = item.jsonObject
				val id = pump["assignmentId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				Assignment(
					id = id,
					serialNumber = pump["serialNumber"]?.jsonPrimitive?.contentOrNull ?: "unknown",
					lastUpload = pump["lastUpload"]?.jsonPrimitive?.contentOrNull
						?: pump["lastUploadDate"]?.jsonPrimitive?.contentOrNull
						?: pump["settings"]?.let { settings ->
							runCatching {
								settings.jsonObject["uploadedTimeStamp"]?.jsonPrimitive?.contentOrNull
							}.getOrNull()
						}
				)
			}
		return pumps.maxByOrNull { parseInstant(it.lastUpload) ?: Long.MIN_VALUE }
	}

	private fun parseInstant(raw: String?): Long? {
		if (raw.isNullOrBlank()) return null
		return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
			?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
			?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
	}

	private fun downloadEvents(auth: TandemAuthContext, assignmentId: String, selectedDate: LocalDate?): String {
		val today = LocalDate.now()
		val center = selectedDate ?: today
		val end = minOf(center.plusDays(7), today)
		val start = end.minusDays(14)
		val eventIds = "229,5,28,4,26,99,279,3,9,16,59,21,55,20,280,64,65,66,61,33,371,171,369,460,172,370,461,372,480,399,256,213,406,477,394,212,404,214,405,486,447,313,60,14,6,90,230,140,12,11,53,13,63,203,191,81"
		val query = linkedMapOf(
			"pumperId" to auth.pumperId,
			"startDate" to "${start}T00:00:00Z",
			"endDate" to "${end}T23:59:59Z",
			"eventIds" to eventIds
		).entries.joinToString("&") { (key, value) ->
			"${encode(key)}=${encode(value)}"
		}
		return getJson(
			"https://source.eu.tandemdiabetes.com/api/reports/bff/pump-logs/$assignmentId?$query",
			auth.accessToken,
			120_000
		)
	}

	private fun getJson(url: String, accessToken: String, timeout: Int): String {
		val connection = URL(url).openConnection() as HttpURLConnection
		return try {
			connection.requestMethod = "GET"
			connection.connectTimeout = 30_000
			connection.readTimeout = timeout
			connection.setRequestProperty("Accept", "application/json")
			connection.setRequestProperty("Authorization", "Bearer $accessToken")
			connection.setRequestProperty("User-Agent", "TandemSourceRT/Android")
			val status = connection.responseCode
			val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
				?.bufferedReader()?.use { it.readText() }.orEmpty()
			if (status != HttpURLConnection.HTTP_OK) {
				throw IllegalStateException("Errore pump-event HTTP $status: ${body.take(300)}")
			}
			body
		} finally {
			connection.disconnect()
		}
	}

	private fun merge(datasets: List<DayDataset>) = DayDataset(
		cgm = datasets.flatMap { it.cgm }.distinct().sortedBy { it.time },
		bolus = datasets.flatMap { it.bolus }.distinct().sortedBy { it.time },
		carbs = datasets.flatMap { it.carbs }.distinct().sortedBy { it.time },
		iob = datasets.flatMap { it.iob }.distinct().sortedBy { it.time },
		basal = datasets.flatMap { it.basal }.distinct().sortedBy { it.time },
		deviceStates = datasets.flatMap { it.deviceStates }.distinct().sortedBy { it.time },
		supplementalEvents = datasets.flatMap { it.supplementalEvents }.distinct()
			.sortedWith(compareBy({ it.time.orEmpty() }, { it.sequenceGroup }, { it.sequenceNumber }))
	)

	private fun encode(value: String): String =
		URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

	private fun safeFilenamePart(value: String): String =
		value.replace(Regex("[^A-Za-z0-9._-]+"), "_")
}
