package com.example.tandemapp.data

import com.example.tandemapp.model.BasalPoint
import com.example.tandemapp.model.BolusEvent
import com.example.tandemapp.model.CarbEvent
import com.example.tandemapp.model.CgmPoint
import com.example.tandemapp.model.DayDataset
import com.example.tandemapp.model.DeviceStateEvent
import com.example.tandemapp.model.IobPoint
import com.example.tandemapp.model.SupplementalPumpEvent
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class PumpEventsPayloadDto(val events: List<PumpEventDto> = emptyList())

@Serializable
data class PumpEventDto(
	val pumpDateTime: String? = null,
	val estimatedDateTime: String? = null,
	val deviceAssignmentId: String? = null,
	val eventCode: Int? = null,
	val sequenceGroup: Int = 0,
	val sequenceNumber: Int = 0,
	val eventProperties: JsonObject = JsonObject(emptyMap())
)

/** Converts the Tandem Source pump-event BFF contract into the app domain model. */
object PumpEventsAdapter {
	private val json = Json { ignoreUnknownKeys = true }
	private val tandemEpoch = Instant.parse("2008-01-01T00:00:00Z")
	private val specializedCodes = setOf(3, 11, 12, 20, 21, 33, 55, 64, 65, 66, 81, 229, 230, 279, 313, 399, 447)
	private val excludedCodes = setOf(219, 307)

	fun decode(payload: String): DayDataset = decode(json.decodeFromString<PumpEventsPayloadDto>(payload))

	fun decode(payload: PumpEventsPayloadDto): DayDataset {
		val events = payload.events.sortedWith(compareBy({ time(it).orEmpty() }, { it.sequenceGroup }, { it.sequenceNumber }))
		val (bolus, carbs) = extractBolus(events)
		return DayDataset(
			cgm = extractCgm(events), bolus = bolus, carbs = carbs,
			iob = extractIob(events), basal = extractBasal(events),
			deviceStates = extractDeviceStates(events), supplementalEvents = extractSupplemental(events)
		)
	}

	private fun time(event: PumpEventDto): String? {
		val value = event.pumpDateTime ?: event.estimatedDateTime ?: return null
		return if (value.endsWith("Z") || value.substringAfterIndex(10).contains('+') || value.substringAfterIndex(10).contains('-')) value else "${value}Z"
	}

	private fun String.substringAfterIndex(index: Int) = if (length > index) substring(index) else ""
	private fun JsonObject.number(name: String, default: Double = 0.0) = (this[name] as? JsonPrimitive)?.doubleOrNull ?: default
	private fun JsonObject.integer(name: String, default: Int = 0) = (this[name] as? JsonPrimitive)?.intOrNull ?: default
	private fun JsonObject.boolean(name: String): Boolean {
		val value = this[name] as? JsonPrimitive ?: return false
		return value.booleanOrNull ?: value.doubleOrNull?.let { it != 0.0 } ?: value.content.isNotEmpty()
	}
	private fun JsonObject.present(name: String) = this[name] != null && this[name] !is JsonNull

	private fun extractCgm(events: List<PumpEventDto>) = events.mapNotNull { event ->
		if (event.eventCode != 399) return@mapNotNull null
		val p = event.eventProperties
		val seconds = p.integer("egvTimeStamp")
		val pointTime = if (seconds > 0) tandemEpoch.plus(seconds.toLong(), ChronoUnit.SECONDS).toString() else time(event)
		val value = (p["currentGlucoseDisplayValue"] as? JsonPrimitive)?.intOrNull
		val dataType = p["cgmDataType"]
		val recovered = if (dataType is JsonArray) dataType.any { (it as? JsonPrimitive)?.intOrNull == 1 } else (dataType as? JsonPrimitive)?.intOrNull == 1
		if (pointTime == null || value == null) null else CgmPoint(pointTime, value, recovered)
	}.sortedBy { it.time }

	private fun extractIob(events: List<PumpEventDto>) = events.mapNotNull { event ->
		val pointTime = time(event)
		if (event.eventCode != 81 || pointTime == null || !event.eventProperties.present("iob")) null
		else IobPoint(pointTime, event.eventProperties.number("iob"))
	}

	private fun extractBasal(events: List<PumpEventDto>): List<BasalPoint> {
		val delivered = events.filter { it.eventCode == 279 }.mapNotNull { event ->
			val pointTime = time(event)
			if (pointTime == null || !event.eventProperties.present("commandedRate")) null
			else BasalPoint(pointTime, event.eventProperties.number("commandedRate") / 1000.0)
		}.toMutableList()
		if (delivered.isNotEmpty()) {
			events.filter { it.eventCode == 90 }.mapNotNullTo(delivered) { event ->
				val pointTime = time(event)
				if (pointTime == null || !event.eventProperties.present("commandedBasalRate")) null
				else BasalPoint(pointTime, event.eventProperties.number("commandedBasalRate"))
			}
			return delivered.associateBy { it.time }.values.sortedBy { it.time }
		}
		return events.filter { it.eventCode == 3 }.mapNotNull { event ->
			val pointTime = time(event)
			if (pointTime == null || !event.eventProperties.present("commandedBasalRate")) null
			else BasalPoint(pointTime, event.eventProperties.number("commandedBasalRate"))
		}
	}

	private data class BolusGroup(val events: MutableMap<Int, PumpEventDto> = linkedMapOf(), val updates: MutableList<PumpEventDto> = mutableListOf())

	private fun extractBolus(events: List<PumpEventDto>): Pair<List<BolusEvent>, List<CarbEvent>> {
		val groups = linkedMapOf<Int, BolusGroup>()
		events.filter { it.eventCode in setOf(20, 21, 55, 64, 65, 66, 280) }.forEach { event ->
			val id = (event.eventProperties["bolusId"] as? JsonPrimitive)?.intOrNull ?: return@forEach
			val group = groups.getOrPut(id) { BolusGroup() }
			if (event.eventCode == 280) group.updates += event else group.events[event.eventCode!!] = event
		}
		val bolus = mutableListOf<BolusEvent>()
		val carbs = mutableListOf<CarbEvent>()
		groups.values.forEach { group ->
			val p1 = group.events[64]?.eventProperties ?: JsonObject(emptyMap())
			val p2 = group.events[65]?.eventProperties ?: JsonObject(emptyMap())
			val completion = group.events[20]?.eventProperties ?: JsonObject(emptyMap())
			val extendedCompletion = group.events[21]?.eventProperties ?: JsonObject(emptyMap())
			val latest = group.updates.map { it.eventProperties }.filter { it.integer("bolusDeliveryStatus", -1) == 0 }.lastOrNull() ?: JsonObject(emptyMap())
			val start = group.events[55] ?: group.events[64] ?: group.events[20] ?: group.events[21]
			val eventTime = start?.let(::time) ?: return@forEach
			if (completion.isEmpty() && extendedCompletion.isEmpty()) return@forEach
			val standardDelivered = completion.number("insulinDelivered")
			var extendedDelivered = extendedCompletion.number("insulinDelivered")
			var delivered = standardDelivered + extendedDelivered
			if (latest.present("deliveredTotal")) delivered = latest.number("deliveredTotal") / 1000.0
			val duration = p2.integer("duration")
			val standardPercent = p2.integer("standardPercent", 100)
			val extended = extendedCompletion.isNotEmpty() || duration > 0 || standardPercent < 100
			val immediate = if (extended && extendedCompletion.isEmpty()) {
				val now = delivered * standardPercent / 100.0
				extendedDelivered = maxOf(0.0, delivered - now); now
			} else standardDelivered
			val carbAmount = p1.number("carbAmount")
			val type = p1.integer("bolusType")
			var requested = completion.number("insulinRequested")
			if (group.updates.isNotEmpty()) requested = (group.updates.first().eventProperties.number("requestedNow") + group.updates.first().eventProperties.number("requestedLater")) / 1000.0
			val status = (completion["completionStatus"] as? JsonPrimitive)?.intOrNull
			val interrupted = (status != null && status != 3) || (requested > 0 && delivered + 0.005 < requested)
			val source = group.updates.firstOrNull()?.eventProperties?.integer("bolusSource") ?: 0
			val origin = if (type == 4) "R" else if (type == 2 || source == 7) "A" else "M"
			val code = when { extended && interrupted -> if (origin == "M") "EI" else "${origin}EI"; interrupted -> if (origin == "M") "I" else "${origin}I"; extended -> if (origin == "M") "E" else "${origin}E"; else -> origin }
			bolus += BolusEvent(eventTime, delivered, mapOf(1 to "carb", 2 to "automatic_correction", 3 to "quick", 4 to "remote")[type] ?: type.toString(),
				if (p1.present("bg")) p1.number("bg") else null, carbAmount, extended,
				if (extended) immediate else null, if (extended) extendedDelivered else null,
				if (extended) duration else null, if (extended) duration else null, code, interrupted)
			if (carbAmount > 0) carbs += CarbEvent(eventTime, carbAmount)
		}
		return bolus to carbs
	}

	private fun extractDeviceStates(events: List<PumpEventDto>): List<DeviceStateEvent> {
		val rows = mutableListOf<DeviceStateEvent>(); var manualSuspend = false
		events.forEach { event ->
			if (event.eventCode == 11) { if (event.eventProperties.integer("suspendReason", -1) != 0) { manualSuspend = false; return@forEach }; manualSuspend = true }
			else if (event.eventCode == 12) { if (!manualSuspend) return@forEach; manualSuspend = false }
			deviceRow(event)?.let(rows::add)
		}
		return rows
	}

	private fun label(value: Int, values: Map<Int, String>, raw: JsonElement?) = values[value] ?: raw?.let { (it as? JsonPrimitive)?.content } ?: "null"
	private fun deviceRow(event: PumpEventDto): DeviceStateEvent? {
		val eventTime = time(event) ?: return null; val p = event.eventProperties; val code = event.eventCode ?: return null
		val pcm = mapOf(0 to "No Control", 1 to "Open Loop", 2 to "Pinning", 3 to "Closed Loop")
		val modes = mapOf(0 to "Normal", 1 to "Sleep", 2 to "Exercise", 3 to "EatingSoon")
		return when (code) {
			229 -> DeviceStateEvent(eventTime, CurrentUserMode=label(p.integer("currentUserMode"),modes,p["currentUserMode"]), PreviousUserMode=label(p.integer("previousUserMode"),modes,p["previousUserMode"]), RequestedAction=label(p.integer("requestedAction"),mapOf(1 to "Start Sleep",2 to "Stop Sleep",3 to "Start Exercise",4 to "Stop Exercise"),p["requestedAction"]), ExerciseChoice=label(p.integer("exerciseChoice"),mapOf(0 to "Continuous"),p["exerciseChoice"]), SleepStartedByGUI=p.boolean("sleepStartedByGui"), ExerciseStoppedByTimer=p.boolean("exerciseStoppedByTimer"), EatingSoonStoppedByTimer=p.boolean("eatingSoonStoppedByTimer"), ExerciseTimeMin=p.integer("exerciseTime"), sourceEventId=code)
			230 -> DeviceStateEvent(eventTime, PumpControlState=label(p.integer("currentPcm"),pcm,p["currentPcm"]), sourceEventId=code)
			313 -> DeviceStateEvent(eventTime, PumpControlState=label(p.integer("pumpControlState"),pcm,p["pumpControlState"]), CurrentUserMode=label(p.integer("usermode"),modes,p["usermode"]), SensorType=label(p.integer("sensorType"),mapOf(1 to "Dexcom G6",3 to "Dexcom G7",4 to "Libre 2",5 to "Libre 3"),p["sensorType"]), sourceEventId=code)
			11 -> DeviceStateEvent(eventTime,eventType="pump_suspended",eventLabel="Stop",sourceEventId=code)
			12 -> DeviceStateEvent(eventTime,eventType="pump_resumed",eventLabel="Restart",sourceEventId=code)
			33 -> DeviceStateEvent(eventTime,eventType="cartridge_site_change",eventLabel="Change set",sourceEventId=code)
			69 -> if (p.integer("status") == 3) { val name=(0..15).mapNotNull { (p["name$it"] as? JsonPrimitive)?.intOrNull?.takeIf { n -> n in 1..127 }?.toChar() }.joinToString(""); DeviceStateEvent(eventTime,eventType="profile_changed",eventLabel="Change profile",eventSubtype=name.ifEmpty { null },sourceEventId=code) } else null
			447 -> DeviceStateEvent(eventTime,eventType="sensor_session_ended",eventLabel="End Sensor",sourceEventId=code)
			else -> null
		}
	}

	private fun extractSupplemental(events: List<PumpEventDto>) = events.mapNotNull { event ->
		val code = event.eventCode ?: return@mapNotNull null
		if (code in specializedCodes || code in excludedCodes) null else SupplementalPumpEvent(time(event), event.estimatedDateTime, event.deviceAssignmentId, code, event.sequenceGroup, event.sequenceNumber, event.eventProperties)
	}
}
