package com.example.tandemapp.data

import com.example.tandemapp.model.DayDataset
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PumpEventsRepository {
	private val json = Json { ignoreUnknownKeys = true }

	fun load(auth: TandemAuthContext, selectedDate: LocalDate?): DayDataset {
		val assignments = getAssignmentIds(auth)
		if (assignments.isEmpty()) throw IllegalStateException("Nessun assignmentId disponibile")
		return merge(assignments.map { PumpEventsAdapter.decode(downloadEvents(auth, it, selectedDate)) })
	}

	private fun getAssignmentIds(auth: TandemAuthContext): List<String> {
		val body = getJson(
			"https://source.eu.tandemdiabetes.com/api/pumpers/pumpers/${auth.pumperId}",
			auth.accessToken,
			60_000
		)
		val root = json.parseToJsonElement(body).jsonObject
		return root["devices"]?.jsonArray.orEmpty().mapNotNull { item ->
			item.jsonObject["assignmentId"]?.jsonPrimitive?.contentOrNull
		}.distinct()
	}

	private fun downloadEvents(auth: TandemAuthContext, assignmentId: String, selectedDate: LocalDate?): String {
		val today = LocalDate.now(ZoneOffset.UTC)
		val center = selectedDate ?: today
		val start = center.minusDays(5)
		val end = minOf(if (center >= today) center else center.plusDays(5), today)
		val eventIds = "229,5,28,4,26,99,279,3,16,59,21,55,20,280,64,65,66,61,33,371,171,369,460,172,370,461,372,480,399,256,213,406,477,394,212,404,214,405,486,447,313,60,14,6,90,230,140,12,11,53,13,63,203,191,81"
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
}
