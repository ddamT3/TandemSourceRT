package com.example.tandemapp.data

import com.example.tandemapp.model.CgmPoint
import com.example.tandemapp.model.DayDataset
import com.example.tandemapp.model.DeviceStateEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A display-only reference to an untouched original sample. */
data class CgmDisplayPoint(val point: CgmPoint, val time: Instant, val segment: Int)

class CgmTimeline(
	val points: List<CgmDisplayPoint> = emptyList(),
	val timeEvents: List<DeviceStateEvent> = emptyList()
) {
	/** Add derived TIME markers without mutating or deduplicating any other event. */
	fun dashboardEvents(existing: List<DeviceStateEvent>): List<DeviceStateEvent> {
		// TIME is derived exclusively from this resolution, never trusted from a previous snapshot.
		return existing.filterNot { it.eventType == TIME_EVENT_TYPE } + timeEvents.distinct()
	}

	fun valueAt(target: Instant): Double? {
		points.firstOrNull { it.time == target }?.let { return it.point.value.toDouble() }
		val right = points.indexOfFirst { it.time > target }
		if (right <= 0) return null
		val leftPoint = points[right - 1]
		val rightPoint = points[right]
		if (!canConnect(leftPoint, rightPoint)) return null
		val ratio = Duration.between(leftPoint.time, target).toMillis().toDouble() /
			Duration.between(leftPoint.time, rightPoint.time).toMillis()
		return leftPoint.point.value + (rightPoint.point.value - leftPoint.point.value) * ratio
	}

	companion object {
		const val TIME_EVENT_TYPE = "pump_time_changed"
		/** Shared by the renderer and cursor; never bridge a clock boundary. */
		fun canConnect(left: CgmDisplayPoint, right: CgmDisplayPoint): Boolean =
			left.segment == right.segment && right.time > left.time &&
				Duration.between(left.time, right.time) <= Duration.ofMinutes(15)
	}
}

/** Resolves only acquisition orders supported by the available BFF metadata. */
object CgmTimelineResolver {
	private data class Scope(val assignment: String, val group: Int)
	private data class Row(
		val index: Int, val point: CgmPoint, val time: Instant,
		val assignment: String?, val scope: Scope?, val sequence: Int?,
		val pump: Instant?, val estimated: Instant?, val acquired: Instant?
	)
	private data class Mask(
		val scope: Scope, val beforeSequence: Int, val start: Instant,
		val end: Instant, val includeEndpoints: Boolean
	)

	fun resolve(dataset: DayDataset): CgmTimeline {
		val rows = dataset.cgm.mapIndexedNotNull { index, point ->
			val time = parseTime(point.time) ?: return@mapIndexedNotNull null
			val source = point.source
			val assignment = source?.deviceAssignmentId
				?: source?.let { dataset.pumpEventsSources.getOrNull(it.payloadIndex)?.requestedAssignmentId }
			val group = source?.sequenceGroup?.takeIf { it >= 0 }
			Row(index, point, time, assignment,
				if (assignment != null && group != null) Scope(assignment, group) else null,
				source?.sequenceNumber?.takeIf { it >= 0 },
				parseTime(source?.pumpDateTime), parseTime(source?.estimatedDateTime),
				(source?.egvTimeStamp as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let {
					runCatching { Instant.parse("2008-01-01T00:00:00Z").plusSeconds(it) }.getOrNull()
				})
		}
		val segments = mutableMapOf<Int, Int>()
		val validated = mutableSetOf<Int>()
		val masks = mutableListOf<Mask>()
		val timeEvents = mutableListOf<DeviceStateEvent>()
		var nextSegment = 0

		// A reception sequence is not an acquisition sequence for a recovered batch.
		// Require an explicit acquisition timestamp matching pump time; a receipt-time fallback is insufficient.
		val candidates = rows.filter {
			!it.point.is_recovered && it.scope != null && it.sequence != null &&
				it.pump == it.time && it.acquired == it.time
		}
		for ((scope, groupRows) in candidates.groupBy { requireNotNull(it.scope) }) {
			val bySequence = groupRows.groupBy { requireNotNull(it.sequence) }
			// Conflicting observations of one sequence cannot establish precedence.
			if (bySequence.values.any { copies ->
				copies.map { listOf(it.time, it.point.value, it.pump, it.estimated) }.distinct().size != 1
			}) continue
			val ordered = bySequence.toSortedMap().values.map { it.first() }
			// A backwards or repeated estimated instant invalidates this sequence order.
			if (ordered.mapNotNull { it.estimated }.zipWithNext().any { (a, b) -> b <= a }) continue
			if (ordered.size < 2) continue
			var segment = nextSegment++
			var high = ordered.first().time
			var previous: Row? = null
			for (row in ordered) {
				val prior = previous
				if (prior != null) {
					val wallDelta = Duration.between(prior.time, row.time)
					val elapsed = if (prior.estimated != null && row.estimated != null)
						Duration.between(prior.estimated, row.estimated) else null
					val offsetChanged = elapsed != null && wallDelta != elapsed
					if (wallDelta.isNegative || wallDelta.isZero || offsetChanged) {
						segment = nextSegment++
						// A continuity break without a demonstrated clock change is not a TIME event.
						if (wallDelta.isNegative || offsetChanged) {
							timeEvents += timeEvent(prior, row, elapsed?.let { wallDelta.minus(it) })
						}
						if (row.time <= high) {
							// Mask the whole old overlap, including timestamps without an exact replacement.
							masks += Mask(scope, requireNotNull(row.sequence), row.time, high, true)
						} else if (elapsed != null && wallDelta > elapsed) {
							masks += Mask(scope, requireNotNull(row.sequence), prior.time, row.time, false)
						}
					} else if (elapsed == null) {
						// A sequence alone cannot distinguish a short forward jump from missing samples.
						segment = nextSegment++
					}
				}
				for (copy in bySequence.getValue(requireNotNull(row.sequence))) {
					segments[copy.index] = segment
					validated += copy.index
				}
				if (row.time > high) high = row.time
				previous = row
			}
		}

		val visible = rows.filterNot { row ->
			masks.any { mask ->
				val inside = if (mask.includeEndpoints) row.time >= mask.start && row.time <= mask.end
					else row.time > mask.start && row.time < mask.end
				inside && if (row.index in validated) {
					row.scope == mask.scope && requireNotNull(row.sequence) < mask.beforeSequence
				} else {
					// An unorderable recovered/missing-sequence sample cannot override a known overlap/gap.
					row.assignment == null || row.assignment == mask.scope.assignment
				}
			}
		}
		val blockedInstants = mutableListOf<Instant>()
		val resolved = visible.groupBy { it.time }.toSortedMap().values.mapNotNull { copies ->
			val trusted = copies.filter { it.index in validated }
			val selected = if (trusted.isNotEmpty()) {
				if (trusted.map { it.scope }.distinct().size != 1) {
					if (trusted.map { it.point.value }.distinct().size != 1) {
						blockedInstants += copies.first().time
						return@mapNotNull null
					}
					// Equal values from incomparable pumps are displayable, but not connectable.
					return@mapNotNull CgmDisplayPoint(trusted.first().point, trusted.first().time, nextSegment++)
				}
				trusted.maxBy { requireNotNull(it.sequence) }
			} else {
				// Do not invent a winner between conflicting, unorderable originals.
				if (copies.map { it.point.value }.distinct().size != 1) {
					blockedInstants += copies.first().time
					return@mapNotNull null
				}
				copies.first()
			}
			CgmDisplayPoint(selected.point, selected.time, segments[selected.index] ?: nextSegment++)
		}
		// Never reconnect across an ambiguous sample omitted from the single display timeline.
		var continuousSegment = 0
		return CgmTimeline(resolved.mapIndexed { index, point ->
			val prior = resolved.getOrNull(index - 1)
			if (prior == null || prior.segment != point.segment ||
				blockedInstants.any { it > prior.time && it < point.time }) continuousSegment++
			point.copy(segment = continuousSegment)
		}, timeEvents.distinct().sortedBy { it.time })
	}

	private fun timeEvent(previous: Row, current: Row, difference: Duration?): DeviceStateEvent =
		DeviceStateEvent(
			time = current.time.toString(), eventType = CgmTimeline.TIME_EVENT_TYPE, eventLabel = "TIME",
			eventSubtype = buildString {
				append("Previous observed pump time: ")
				append(previous.point.source?.pumpDateTime ?: previous.time.toString())
				append("\nNew observed pump time: ")
				append(current.point.source?.pumpDateTime ?: current.time.toString())
				append("\nClock difference: ")
				if (difference == null) append("Not available") else {
					val absolute = difference.abs()
					append(String.format(Locale.US, "%s%02d:%02d:%02d",
						if (difference.isNegative) "-" else "+", absolute.toHours(),
						absolute.toMinutes() % 60, absolute.seconds % 60))
					if (absolute.nano != 0) append(String.format(Locale.US, ".%09d", absolute.nano).trimEnd('0'))
				}
				append("\n\nObserved samples bracket the change; the exact adjustment time is unavailable.")
			}
		)

	private fun parseTime(raw: String?): Instant? {
		if (raw == null) return null
		return runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
			?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }.getOrNull()
	}
}
