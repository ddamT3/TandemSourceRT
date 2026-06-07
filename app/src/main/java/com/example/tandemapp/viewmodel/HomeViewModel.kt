package com.example.tandemapp.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tandemapp.data.JsonTandemRepository
import com.example.tandemapp.data.EmbeddedTandemRepository
import com.example.tandemapp.model.DayDataset
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class HomeState(
	val selected: LocalDate = LocalDate.parse("2026-03-16"),
	val anchorDate: LocalDate = LocalDate.parse("2026-03-16"),
	val available: List<LocalDate> = emptyList(),
	val dataset: DayDataset? = null,
	val userModeSegments: List<UserModeSegment> = emptyList(),
	val selectedWindowHours: Int = 24,
	val visibleWindowStart: OffsetDateTime? = null,
	val visibleWindowEnd: OffsetDateTime? = null
)

data class UserModeSegment(
	val start: OffsetDateTime,
	val end: OffsetDateTime,
	val mode: String
)

enum class SnapshotField {
	DAY,
	HOUR,
	IOB,
	BOLUS,
	CHO,
	CGM,
	BASAL,
	EVENT
}

enum class NavigationDirection {
	PREVIOUS,
	NEXT
}

class HomeViewModel(
	private val repo: JsonTandemRepository
) : ViewModel() {


	var state = mutableStateOf(HomeState())
		private set

	init {
		val today = LocalDate.now()

		val center = today
			.atTime(12, 0)
			.atOffset(ZoneOffset.UTC)

		val halfWindowSeconds = state.value.selectedWindowHours * 3600L / 2

		state.value = state.value.copy(
			selected = today,
			anchorDate = today,
			available = emptyList(),
			dataset = null,
			userModeSegments = emptyList(),
			visibleWindowStart = center.minusSeconds(halfWindowSeconds),
			visibleWindowEnd = center.plusSeconds(halfWindowSeconds)
		)
	}

	fun setLiveData(data: DayDataset, anchorDate: LocalDate = LocalDate.now()) {
		val available = data.cgm
			.mapNotNull { runCatching { LocalDate.parse(it.time.substring(0, 10)) }.getOrNull() }
			.distinct()
			.sorted()

		val center = anchorDate
			.atTime(12, 0)
			.atOffset(ZoneOffset.UTC)

		val halfWindowSeconds = state.value.selectedWindowHours * 3600L / 2

		state.value = state.value.copy(
			selected = anchorDate,
			anchorDate = anchorDate,
			available = available,
			dataset = data,
			userModeSegments = buildUserModeTimeline(data),
			visibleWindowStart = center.minusSeconds(halfWindowSeconds),
			visibleWindowEnd = center.plusSeconds(halfWindowSeconds)
		)
	}

	fun selectAnchorDate(date: LocalDate) {
		val center = date
			.atTime(12, 0)
			.atOffset(ZoneOffset.UTC)

		val halfWindowSeconds = state.value.selectedWindowHours * 3600L / 2

		state.value = state.value.copy(
			selected = date,
			anchorDate = date,
			visibleWindowStart = center.minusSeconds(halfWindowSeconds),
			visibleWindowEnd = center.plusSeconds(halfWindowSeconds)
		)
	}

	fun selectWindow(hours: Int) {
		val start = state.value.visibleWindowStart
		val end = state.value.visibleWindowEnd

		val center = if (start != null && end != null) {
			start.plusSeconds(Duration.between(start, end).seconds / 2)
		} else {
			state.value.anchorDate
				.atTime(12, 0)
				.atOffset(ZoneOffset.UTC)
		}

		val halfWindowSeconds = hours * 3600L / 2L


		val newStart = center.minusSeconds(halfWindowSeconds)
		val newEnd = center.plusSeconds(halfWindowSeconds)


		state.value = state.value.copy(
			selectedWindowHours = hours,
			visibleWindowStart = newStart,
			visibleWindowEnd = newEnd
		)
	}

	fun panWindowBySeconds(deltaSeconds: Long) {
		val start = state.value.visibleWindowStart ?: return
		val end = state.value.visibleWindowEnd ?: return

		state.value = state.value.copy(
			visibleWindowStart = start.plusSeconds(deltaSeconds),
			visibleWindowEnd = end.plusSeconds(deltaSeconds)
		)
	}

	fun jumpToLatest() {
		val dataset = state.value.dataset ?: return
		val lastCgm = dataset.cgm.lastOrNull() ?: return
		val lastTime = parseChartTime(lastCgm.time) ?: return
		centerOn(lastTime)
	}

	fun navigate(field: SnapshotField, direction: NavigationDirection) {
		val centerTime = currentCenterTime() ?: return

		when (field) {
			SnapshotField.DAY -> {
				val nextCenter = when (direction) {
					NavigationDirection.NEXT ->
						centerTime.plusDays(1).withHour(12).withMinute(0).withSecond(0).withNano(0)

					NavigationDirection.PREVIOUS ->
						centerTime.minusDays(1).withHour(12).withMinute(0).withSecond(0).withNano(0)
				}
				centerOn(nextCenter)

				state.value = state.value.copy(
					selected = nextCenter.toLocalDate(),
					anchorDate = nextCenter.toLocalDate()
				)
			}

			SnapshotField.HOUR -> {
				val nextCenter = when (direction) {
					NavigationDirection.NEXT ->
						centerTime.plusHours(1).withMinute(0).withSecond(0).withNano(0)

					NavigationDirection.PREVIOUS ->
						centerTime.minusHours(1).withMinute(0).withSecond(0).withNano(0)
				}
				centerOn(nextCenter)
			}

			else -> {
				val dataset = state.value.dataset ?: return

				val target = when (field) {
					SnapshotField.IOB -> navigateDistinct(
						pairs = dataset.iob.mapNotNull { point ->
							parseChartTime(point.time)?.let { it to point.iob }
						},
						currentTime = centerTime,
						direction = direction
					)

					SnapshotField.BOLUS -> navigateDistinct(
						pairs = dataset.bolus.mapNotNull { event ->
							parseChartTime(event.time)?.let { it to event.insulin_delivered_u }
						},
						currentTime = centerTime,
						direction = direction
					)

					SnapshotField.CHO -> navigateDistinct(
						pairs = buildChoPairs(dataset),
						currentTime = centerTime,
						direction = direction
					)

					SnapshotField.CGM -> navigateDistinct(
						pairs = dataset.cgm.mapNotNull { point ->
							parseChartTime(point.time)?.let { it to point.value }
						},
						currentTime = centerTime,
						direction = direction
					)

					SnapshotField.BASAL -> navigateDistinct(
						pairs = dataset.basal.mapNotNull { point ->
							parseChartTime(point.time)?.let { it to point.CommandedBasalRate }
						},
						currentTime = centerTime,
						direction = direction
					)

					SnapshotField.EVENT -> navigateDistinct(
						pairs = dataset.deviceStates.mapNotNull { event ->
							parseChartTime(event.time)?.let { time ->
								mapEventValue(event.eventLabel, event.eventType, event.CurrentUserMode, event.PumpControlState)?.let { value ->
									time to value
								}
							}
						},
						currentTime = centerTime,
						direction = direction
					)

					SnapshotField.DAY,
					SnapshotField.HOUR -> null
				}

				target?.let { centerOn(it) }
			}
		}
	}

	fun loadTestData(testRepo: EmbeddedTandemRepository) {
		viewModelScope.launch {
			val history = testRepo.loadBundledBlobHistory("pumpevents.bin") ?: return@launch

			val timeline = buildTimelinePairs(history).map { it.first }
			val firstTime = timeline.firstOrNull()
			val lastTime = timeline.lastOrNull()
			val center = if (firstTime != null && lastTime != null) {
				firstTime.plusSeconds(Duration.between(firstTime, lastTime).seconds / 2L)
			} else {
				LocalDate.now().atTime(12, 0).atOffset(ZoneOffset.UTC)
			}

			val available = timeline
				.map { it.toLocalDate() }
				.distinct()
				.sorted()

			val anchorDate = center.toLocalDate()
			val halfWindowSeconds = state.value.selectedWindowHours * 3600L / 2L

			state.value = state.value.copy(
				selected = anchorDate,
				anchorDate = anchorDate,
				available = available,
				dataset = history,
				userModeSegments = buildUserModeTimeline(history),
				visibleWindowStart = center.minusSeconds(halfWindowSeconds),
				visibleWindowEnd = center.plusSeconds(halfWindowSeconds)
			)
		}
	}


	private fun buildUserModeTimeline(dataset: DayDataset): List<UserModeSegment> {
		val events = dataset.deviceStates
			.mapNotNull { event ->
				val time = parseChartTime(event.time) ?: return@mapNotNull null
				val mode = normalizeUserMode(event.CurrentUserMode) ?: return@mapNotNull null
				time to mode
			}
			.sortedBy { it.first.toInstant() }
		if (events.isEmpty()) return emptyList()

		val timelineStart = dataset.cgm
			.mapNotNull { parseChartTime(it.time) }
			.firstOrNull()
			?: events.first().first

		val timelineEnd = dataset.cgm
			.mapNotNull { parseChartTime(it.time) }
			.lastOrNull()
			?: events.last().first

		val segments = mutableListOf<UserModeSegment>()
		var currentStart = timelineStart
		var currentMode = events.first().second

		if (events.first().first.isAfter(timelineStart)) {
			currentMode = events.first().second
			currentStart = timelineStart
		}

		for (i in 1 until events.size) {
			val (time, mode) = events[i]
			if (mode == currentMode) continue
			segments.add(UserModeSegment(start = currentStart, end = time, mode = currentMode))
			currentStart = time
			currentMode = mode
		}

		if (timelineEnd.isAfter(currentStart)) {
			segments.add(UserModeSegment(start = currentStart, end = timelineEnd, mode = currentMode))
		}

		return segments
	}

	private fun buildChoPairs(dataset: DayDataset): List<Pair<OffsetDateTime, Double>> {
		val bolusCho = dataset.bolus.mapNotNull { event ->
			val parsed = parseChartTime(event.time) ?: return@mapNotNull null
			if (event.bolus_type == "carb") parsed to event.carbs_g else null
		}
		val carbEvents = dataset.carbs.mapNotNull { event ->
			parseChartTime(event.time)?.let { it to event.carbs_g }
		}
		return (bolusCho + carbEvents).sortedBy { it.first.toInstant() }
	}

	private fun buildTimelinePairs(dataset: DayDataset): List<Pair<OffsetDateTime, Unit>> {
		return buildList {
			addAll(dataset.cgm.mapNotNull { parseChartTime(it.time)?.let { time -> time to Unit } })
			addAll(dataset.iob.mapNotNull { parseChartTime(it.time)?.let { time -> time to Unit } })
			addAll(dataset.basal.mapNotNull { parseChartTime(it.time)?.let { time -> time to Unit } })
			addAll(dataset.bolus.mapNotNull { parseChartTime(it.time)?.let { time -> time to Unit } })
			addAll(dataset.carbs.mapNotNull { parseChartTime(it.time)?.let { time -> time to Unit } })
			addAll(dataset.deviceStates.mapNotNull { parseChartTime(it.time)?.let { time -> time to Unit } })
		}
			.distinctBy { it.first.toInstant() }
			.sortedBy { it.first.toInstant() }
	}

	private fun currentCenterTime(): OffsetDateTime? {
		val start = state.value.visibleWindowStart ?: return null
		val end = state.value.visibleWindowEnd ?: return null
		return start.plusSeconds(Duration.between(start, end).seconds / 2)
	}

	private fun centerOn(targetTime: OffsetDateTime) {
		val start = state.value.visibleWindowStart ?: return
		val end = state.value.visibleWindowEnd ?: return
		val halfWindowSeconds = Duration.between(start, end).seconds / 2

		state.value = state.value.copy(
			visibleWindowStart = targetTime.minusSeconds(halfWindowSeconds),
			visibleWindowEnd = targetTime.plusSeconds(halfWindowSeconds)
		)
	}

	private fun <T> navigateDistinct(
		pairs: List<Pair<OffsetDateTime, T>>,
		currentTime: OffsetDateTime,
		direction: NavigationDirection
	): OffsetDateTime? {
		val sorted = pairs.sortedBy { it.first.toInstant() }
		if (sorted.isEmpty()) return null

		val currentIndex = sorted.indexOfLast { !it.first.isAfter(currentTime) }
		if (currentIndex == -1) {
			return when (direction) {
				NavigationDirection.NEXT -> sorted.first().first
				NavigationDirection.PREVIOUS -> null
			}
		}

		val currentValue = sorted[currentIndex].second
		return when (direction) {
			NavigationDirection.NEXT -> {
				for (i in currentIndex + 1 until sorted.size) {
					if (sorted[i].second != currentValue) return sorted[i].first
				}
				null
			}
			NavigationDirection.PREVIOUS -> {
				for (i in currentIndex - 1 downTo 0) {
					if (sorted[i].second != currentValue) return sorted[i].first
				}
				null
			}
		}
	}

	private fun parseChartTime(raw: String): OffsetDateTime? = try {
		OffsetDateTime.parse(raw)
	} catch (_: Exception) {
		try {
			LocalDateTime.parse(raw).atOffset(ZoneOffset.UTC)
		} catch (_: Exception) {
			null
		}
	}

	private fun normalizeUserMode(raw: String?): String? {
		val value = raw.orEmpty().trim()
		return when {
			value.equals("exercise", true) || value.equals("exercising", true) -> "Exercise"
			value.equals("sleep", true) || value.equals("sleeping", true) || value.equals("night", true) || value.equals("notte", true) -> "Sleep"
			value.equals("eatingsoon", true) || value.equals("eating_soon", true) || value.equals("eating soon", true) -> "EatingSoon"
			value.equals("normal", true) || value.equals("norm", true) -> "Normal"
			else -> null
		}
	}

	private fun mapEventValue(eventLabel: String?, eventType: String?, currentUserMode: String?, pumpControlState: String?): String? {
		return when {
			eventType.equals("pump_suspended", true) -> "Stop"
			eventType.equals("pump_resumed", true) -> "Restart"
			eventType.equals("cartridge_site_change", true) -> "Change set"
			eventType.equals("sensor_session_ended", true) -> "End Sensor"
			currentUserMode.equals("normal", true) -> "Norm"
			currentUserMode.equals("exercising", true) -> "EX"
			currentUserMode.equals("sleep", true) ||
				currentUserMode.equals("sleeping", true) ||
				currentUserMode.equals("night", true) -> "Sleep"
			pumpControlState.equals("No Control", true) -> "!"
			!currentUserMode.isNullOrBlank() -> currentUserMode
			!pumpControlState.isNullOrBlank() -> pumpControlState
			else -> null
		}
	}
}
