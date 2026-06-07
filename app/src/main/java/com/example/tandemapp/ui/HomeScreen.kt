package com.example.tandemapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.example.tandemapp.model.BasalPoint
import com.example.tandemapp.model.BolusEvent
import androidx.compose.ui.platform.LocalConfiguration
import com.example.tandemapp.model.CgmPoint
import com.example.tandemapp.model.DayDataset
import com.example.tandemapp.model.IobPoint
import com.example.tandemapp.viewmodel.UserModeSegment
import com.example.tandemapp.viewmodel.HomeViewModel
import com.example.tandemapp.viewmodel.NavigationDirection
import com.example.tandemapp.viewmodel.SnapshotField
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp

@Composable
fun HomeScreen(
	vm: HomeViewModel,
	modifier: Modifier = Modifier,
	onUpdateClick: () -> Unit = {}
) {
	val configuration = LocalConfiguration.current
	val screenWidth = configuration.screenWidthDp.dp
	val screenHeight = configuration.screenHeightDp.dp
	val state by vm.state
	val dataset = state.dataset
	val windowOptions = listOf(3, 6, 12, 24, 36)
	// La linea rossa centrale resta il riferimento fisso dello zoom temporale.

	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(
				horizontal = GlucoseChartLayout.HomeUi.adaptiveHorizontalPadding(screenWidth),
				vertical = GlucoseChartLayout.HomeUi.screenHorizontalPadding
			),
		verticalArrangement = Arrangement.spacedBy(GlucoseChartLayout.HomeUi.chartCardTopPadding)
	) {
		val snapshot = rememberCursorSnapshot(
			dataset = dataset,
			visibleWindowStart = state.visibleWindowStart,
			visibleWindowEnd = state.visibleWindowEnd
		)

		SnapshotTable(
			snapshot = snapshot,
			screenWidth = screenWidth,
			screenHeight = screenHeight,
			onNavigate = { field, direction -> vm.navigate(field, direction) }
		)

		BoxWithConstraints(
			modifier = Modifier
				.fillMaxWidth()
				.weight(1f)
		) {
			val chartCanvasHeight: Dp = (maxHeight - GlucoseChartLayout.HomeUi.chartCardTopPadding * 2)
				.coerceAtLeast(GlucoseChartLayout.Chart.canvasMinHeight)

			if (state.visibleWindowStart != null && state.visibleWindowEnd != null) {
				Card(
					modifier = Modifier.fillMaxSize(),
					colors = CardDefaults.cardColors(),
					shape = RoundedCornerShape(GlucoseChartLayout.HomeUi.chartCardCornerRadius)
				) {
					Column(
						modifier = Modifier
							.fillMaxSize()
							.padding(
								horizontal = GlucoseChartLayout.HomeUi.chartCardHorizontalPadding,
								vertical = GlucoseChartLayout.HomeUi.chartCardTopPadding
							)
					) {
						GlucoseChart(
							points = dataset?.cgm ?: emptyList(),
							bolusEvents = dataset?.bolus ?: emptyList(),
							carbEvents = dataset?.carbs ?: emptyList(),
							basalPoints = dataset?.basal ?: emptyList(),
							iobPoints = dataset?.iob ?: emptyList(),
							userModeSegments = state.userModeSegments,
							deviceStateEvents = dataset?.deviceStates ?: emptyList(),
							windowHours = state.selectedWindowHours,
							fixedWindowStart = state.visibleWindowStart,
							fixedWindowEnd = state.visibleWindowEnd,
							canvasHeight = chartCanvasHeight,
							onPanBySeconds = { delta ->
								vm.panWindowBySeconds(delta)
							}
						)
					}
				}
			}
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(GlucoseChartLayout.HomeUi.zoomButtonGap)
		) {
			windowOptions.forEach { hours ->
				FilterChip(
					selected = state.selectedWindowHours == hours,
					onClick = { vm.selectWindow(hours) },
					label = {
						Text(
							text = "${hours}h",
							fontSize = GlucoseChartLayout.HomeUi.zoomButtonFontSize,
							maxLines = 1
						)
					},
					modifier = Modifier
						.heightIn(min = GlucoseChartLayout.HomeUi.zoomButtonHeight)
						.sizeIn(minWidth = GlucoseChartLayout.HomeUi.zoomButtonMinWidth)
				)
			}

			FilterChip(
				selected = false,
				onClick = onUpdateClick,
				label = {
					Icon(
							imageVector = Icons.Default.Refresh,
							contentDescription = "Update"
					)
				},
				modifier = Modifier
					.heightIn(min = GlucoseChartLayout.HomeUi.zoomButtonHeight)
					.sizeIn(minWidth = GlucoseChartLayout.HomeUi.zoomButtonMinWidth)
			)
		}
	}
}

@Composable
private fun SnapshotTable(
	snapshot: CursorSnapshot?,
	screenWidth: Dp,
	screenHeight: Dp,
	onNavigate: (SnapshotField, NavigationDirection) -> Unit
) {
	val horizPadding = GlucoseChartLayout.HomeUi.adaptiveHorizontalSpacing(screenWidth, 8f)
	val vertPadding = GlucoseChartLayout.HomeUi.adaptiveVerticalSpacing(screenHeight, 7f)
	val colGap = GlucoseChartLayout.HomeUi.adaptiveHorizontalSpacing(screenWidth, 6f)

	Card(
		shape = RoundedCornerShape(16.dp),
		colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F6F8)),
		elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
		modifier = Modifier.fillMaxWidth()
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = horizPadding, vertical = vertPadding),
			horizontalArrangement = Arrangement.spacedBy(colGap)
		) {
			SnapshotColumn(
				modifier = Modifier.weight(1f),
				screenHeight = screenHeight,
				screenWidth = screenWidth,
				rows = listOf(
					SnapshotRow("Day", snapshot?.day.orEmpty(), SnapshotField.DAY),
					SnapshotRow("Hour", snapshot?.time.orEmpty(), SnapshotField.HOUR),
					SnapshotRow("Event", snapshot?.event.orEmpty(), SnapshotField.EVENT),
					SnapshotRow("CHO", snapshot?.cho.orEmpty(), SnapshotField.CHO)
				),
				onNavigate = onNavigate
			)

			SnapshotColumn(
				modifier = Modifier.weight(1f),
				screenHeight = screenHeight,
				screenWidth = screenWidth,
				rows = listOf(
					SnapshotRow("CGM", snapshot?.cgm.orEmpty(), SnapshotField.CGM),
					SnapshotRow("IOB", snapshot?.iob.orEmpty(), SnapshotField.IOB),
					SnapshotRow("Basal", snapshot?.basal.orEmpty(), SnapshotField.BASAL),
					SnapshotRow("Bolus", snapshot?.bolus.orEmpty(), SnapshotField.BOLUS)
				),
				onNavigate = onNavigate
			)
		}
	}
}

private data class SnapshotRow(
	val label: String,
	val value: String,
	val field: SnapshotField? = null
)

@Composable
private fun SnapshotColumn(
	modifier: Modifier = Modifier,
	screenHeight: Dp,
	screenWidth: Dp,
	rows: List<SnapshotRow>,
	onNavigate: (SnapshotField, NavigationDirection) -> Unit
) {
	val metricGap = GlucoseChartLayout.HomeUi.adaptiveVerticalSpacing(screenHeight, 3f)

	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(metricGap)
	) {
		rows.forEach { row ->
			SnapshotMetric(row = row, screenWidth = screenWidth, onNavigate = onNavigate)
		}
	}
}

@Composable
private fun SnapshotMetric(
	row: SnapshotRow,
	screenWidth: Dp,
	onNavigate: (SnapshotField, NavigationDirection) -> Unit
) {
	val rowGap = GlucoseChartLayout.HomeUi.adaptiveHorizontalSpacing(screenWidth, 3f)

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(rowGap),
		verticalAlignment = Alignment.CenterVertically
	) {
		val field = row.field

		if (field != null) {
			NavigationChip(symbol = "<") {
				onNavigate(field, NavigationDirection.PREVIOUS)
			}
		}

		Box(
			modifier = Modifier
				.width(34.dp)
				.background(Color(0xFFF1EDF4), RoundedCornerShape(8.dp))
				.padding(horizontal = 2.dp, vertical = 2.dp)
		) {
			Text(
				text = row.label,
				fontSize = 9.sp,
				fontWeight = FontWeight.Medium,
				color = Color(0xFF6F6775),
				maxLines = 1,
				softWrap = false
			)
		}

		Text(
			text = row.value.ifBlank { "—" },
			fontSize = 11.sp,
			fontWeight = FontWeight.SemiBold,
			color = Color(0xFF1F1A22),
			maxLines = 1,
			softWrap = false,
			overflow = TextOverflow.Clip,
			modifier = Modifier.weight(1f)
		)

		if (field != null) {
			NavigationChip(symbol = ">") {
				onNavigate(field, NavigationDirection.NEXT)
			}
		}
	}
}

@Composable
private fun NavigationChip(
	symbol: String,
	onClick: () -> Unit
) {
	Box(
		modifier = Modifier
			.sizeIn(minWidth = 24.dp, minHeight = 28.dp)
			.background(Color(0xFFE8E2EC), RoundedCornerShape(8.dp))
			.clickable(onClick = onClick)
			.padding(horizontal = 5.dp, vertical = 2.dp),
		contentAlignment = Alignment.Center
	) {
		Text(
			text = symbol,
			fontSize = 9.sp,
			fontWeight = FontWeight.SemiBold,
			color = Color(0xFF4D4652),
			maxLines = 1,
			softWrap = false
		)
	}
}

private data class CursorSnapshot(
	val day: String,
	val time: String,
	val iob: String,
	val bolus: String,
	val cho: String,
	val cgm: String,
	val basal: String,
	val event: String
)

@Composable
private fun rememberCursorSnapshot(
	dataset: DayDataset?,
	visibleWindowStart: OffsetDateTime?,
	visibleWindowEnd: OffsetDateTime?
): CursorSnapshot? {
	if (visibleWindowStart == null || visibleWindowEnd == null) return null

	val centerTime = visibleWindowStart.plusSeconds(
		Duration.between(visibleWindowStart, visibleWindowEnd).seconds / 2
	)

	val dayFormatter = DateTimeFormatter.ofPattern("dd/MM")
	val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

	if (dataset == null) {
		return CursorSnapshot(
			day = centerTime.format(dayFormatter),
			time = centerTime.format(timeFormatter),
			iob = "",
			bolus = "",
			cho = "",
			cgm = "",
			basal = "",
			event = ""
		)
	}

	val iobValue = interpolateIob(dataset.iob, centerTime)
	val cgmValue = interpolateCgm(dataset.cgm, centerTime)
	val basalValue = currentBasal(dataset.basal, centerTime)
	val bolusAtCursor = findBolusAtCursor(dataset.bolus, centerTime)
	val choAtCursor = findChoAtCursor(dataset, centerTime)
	val eventAtCursor = findEventAtCursor(dataset.deviceStates, centerTime)

	return CursorSnapshot(
		day = centerTime.format(dayFormatter),
		time = centerTime.format(timeFormatter),
		iob = iobValue?.let { formatOneDecimal(it) }.orEmpty(),
		bolus = bolusAtCursor?.let { event ->
			formatTwoDecimals(event.insulin_delivered_u) + if (event.is_extended == true) " E" else ""
		}.orEmpty(),
		cho = choAtCursor?.let { formatWhole(it) }.orEmpty(),
		cgm = cgmValue?.let { formatWhole(it) }.orEmpty(),
		basal = basalValue?.let { formatOneDecimal(it) }.orEmpty(),
		event = eventAtCursor.orEmpty()
	)
}

private fun interpolateIob(points: List<IobPoint>, targetTime: OffsetDateTime): Double? {
	val parsed = points.mapNotNull { point ->
		parseChartTime(point.time)?.let { ParsedNumericPoint(it, point.iob) }
	}.sortedBy { it.time.toInstant() }

	return interpolateNumericSeries(parsed, targetTime, maxGapSeconds = 15 * 60L)
}

private fun interpolateCgm(points: List<CgmPoint>, targetTime: OffsetDateTime): Double? {
	val parsed = points.mapNotNull { point ->
		parseChartTime(point.time)?.let { ParsedNumericPoint(it, point.value.toDouble()) }
	}.sortedBy { it.time.toInstant() }

	return interpolateNumericSeries(parsed, targetTime, maxGapSeconds = 15 * 60L)
}

private fun currentBasal(points: List<BasalPoint>, targetTime: OffsetDateTime): Double? {
	val parsed = points.mapNotNull { point ->
		parseChartTime(point.time)?.let { ParsedNumericPoint(it, point.CommandedBasalRate) }
	}.sortedBy { it.time.toInstant() }

	if (parsed.isEmpty()) return null
	val previous = parsed.lastOrNull { !it.time.isAfter(targetTime) } ?: return null
	val next = parsed.firstOrNull { it.time.isAfter(previous.time) }
	val maxBasalHoldSeconds = 30 * 60L

	if (Duration.between(previous.time, targetTime).seconds > maxBasalHoldSeconds) return null
	if (next != null && targetTime.isAfter(next.time)) return null

	return previous.value
}

private fun findBolusAtCursor(
	events: List<BolusEvent>,
	targetTime: OffsetDateTime,
	toleranceSeconds: Long = 120
): BolusEvent? {
	return events
		.mapNotNull { event ->
			parseChartTime(event.time)?.let { parsedTime -> parsedTime to event }
		}
		.filter { (time, _) -> abs(Duration.between(time, targetTime).seconds) <= toleranceSeconds }
		.minByOrNull { (time, _) -> abs(Duration.between(time, targetTime).seconds) }
		?.second
}

private fun findChoAtCursor(
	dataset: DayDataset,
	targetTime: OffsetDateTime,
	toleranceSeconds: Long = 120
): Double? {
	val bolusCho = dataset.bolus.mapNotNull { event ->
		if (!event.bolus_type.equals("carb", true)) return@mapNotNull null
		parseChartTime(event.time)?.let { parsedTime -> parsedTime to event.carbs_g }
	}

	val carbCho = dataset.carbs.mapNotNull { event ->
		parseChartTime(event.time)?.let { parsedTime -> parsedTime to event.carbs_g }
	}

	return (bolusCho + carbCho)
		.filter { (time, _) -> abs(Duration.between(time, targetTime).seconds) <= toleranceSeconds }
		.minByOrNull { (time, _) -> abs(Duration.between(time, targetTime).seconds) }
		?.second
}

private fun findEventAtCursor(
	events: List<com.example.tandemapp.model.DeviceStateEvent>,
	targetTime: OffsetDateTime,
	toleranceSeconds: Long = 15 * 60
): String? {
	val nearest = events
		.mapNotNull { event ->
			parseChartTime(event.time)?.let { parsedTime -> parsedTime to event }
		}
		.filter { (time, _) -> abs(Duration.between(time, targetTime).seconds) <= toleranceSeconds }
		.minByOrNull { (time, _) -> abs(Duration.between(time, targetTime).seconds) }
		?.second

	return when {
		nearest == null -> null
		!shortEventLabel(nearest.eventType, nearest.eventLabel).isNullOrBlank() -> shortEventLabel(nearest.eventType, nearest.eventLabel)
		nearest.CurrentUserMode.equals("exercise", true) || nearest.CurrentUserMode.equals("exercising", true) -> "EX"
		nearest.CurrentUserMode.equals("sleep", true) ||
			nearest.CurrentUserMode.equals("sleeping", true) ||
			nearest.CurrentUserMode.equals("night", true) ||
			nearest.CurrentUserMode.equals("notte", true) -> "Sleep"
		nearest.PumpControlState.equals("No Control", true) -> "!"
		else -> nearest.CurrentUserMode ?: nearest.PumpControlState
	}
}

private fun shortEventLabel(eventType: String?, eventLabel: String?): String? {
	return when {
		eventType.equals("pump_suspended", true) -> "Stop"
		eventType.equals("pump_resumed", true) -> "Restart"
		eventType.equals("cartridge_site_change", true) -> "Change set"
		eventType.equals("sensor_session_ended", true) -> "End Sensor"
		!eventLabel.isNullOrBlank() -> eventLabel
		else -> null
	}
}

private fun interpolateNumericSeries(
	points: List<ParsedNumericPoint>,
	targetTime: OffsetDateTime,
	maxGapSeconds: Long
): Double? {
	if (points.isEmpty()) return null
	if (targetTime < points.first().time || targetTime > points.last().time) return null

	points.firstOrNull { it.time == targetTime }?.let { return it.value }

	for (index in 0 until points.lastIndex) {
		val p1 = points[index]
		val p2 = points[index + 1]
		if (!targetTime.isBefore(p1.time) && !targetTime.isAfter(p2.time)) {
			val totalSeconds = Duration.between(p1.time, p2.time).seconds
			if (totalSeconds > maxGapSeconds) return null
			val total = totalSeconds.toDouble().coerceAtLeast(1.0)
			val part = Duration.between(p1.time, targetTime).seconds.toDouble().coerceIn(0.0, total)
			val ratio = part / total
			return p1.value + (p2.value - p1.value) * ratio
		}
	}

	return null
}

private data class ParsedNumericPoint(
	val time: OffsetDateTime,
	val value: Double
)

private fun parseChartTime(raw: String): OffsetDateTime? = try {
	OffsetDateTime.parse(raw)
} catch (_: Exception) {
	try {
		LocalDateTime.parse(raw).atOffset(ZoneOffset.UTC)
	} catch (_: Exception) {
		null
	}
}

private fun formatWhole(value: Double): String = value.toInt().toString()
private fun formatOneDecimal(value: Double): String = String.format("%.1f", value).replace('.', ',')
private fun formatTwoDecimals(value: Double): String = String.format("%.2f", value).replace('.', ',')
