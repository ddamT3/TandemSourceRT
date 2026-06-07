package com.example.tandemapp.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import com.example.tandemapp.model.BasalPoint
import com.example.tandemapp.model.BolusEvent
import com.example.tandemapp.model.CarbEvent
import com.example.tandemapp.model.CgmPoint
import com.example.tandemapp.model.DeviceStateEvent
import com.example.tandemapp.model.IobPoint
import com.example.tandemapp.viewmodel.UserModeSegment
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun GlucoseChart(
	points: List<CgmPoint>,
	bolusEvents: List<BolusEvent>,
	carbEvents: List<CarbEvent>,
	basalPoints: List<BasalPoint> = emptyList(),
	iobPoints: List<IobPoint> = emptyList(),
	userModeSegments: List<UserModeSegment> = emptyList(),
	deviceStateEvents: List<DeviceStateEvent> = emptyList(),
	windowHours: Int = 24,
	fixedWindowStart: OffsetDateTime? = null,
	fixedWindowEnd: OffsetDateTime? = null,
	canvasHeight: Dp = GlucoseChartLayout.canvasHeight,
	onPanBySeconds: ((Long) -> Unit)? = null
) {
	val parsedPoints = points.mapNotNull {
		val t = parseChartTime(it.time) ?: return@mapNotNull null
		ParsedCgmPoint(t, it.value.toFloat())
	}.sortedBy { it.time.toInstant() }

	val parsedBolus = bolusEvents.mapNotNull {
		val t = parseChartTime(it.time) ?: return@mapNotNull null
		ParsedBolusEvent(
			time = t,
			value = it.insulin_delivered_u.toFloat(),
			bolusType = it.bolus_type,
			bgMgdl = it.bg_mgdl?.toFloat(),
			carbsGrams = it.carbs_g?.toFloat(),
			isExtended = it.is_extended ?: false,
			immediateInsulin = it.immediate_insulin_u?.toFloat(),
			extendedInsulin = it.extended_insulin_u?.toFloat(),
			extendedDurationMin = it.extended_duration_min ?: it.duration_min
		)
	}.sortedBy { it.time.toInstant() }

	val manualBolusTimes = parsedBolus
		.filter { it.bolusType == "carb" }
		.map { it.time }

	val parsedCarbs = carbEvents.mapNotNull {
		val t = parseChartTime(it.time) ?: return@mapNotNull null
		ParsedCarbEvent(t, it.carbs_g.toFloat())
	}.filter { carb ->
		manualBolusTimes.none { bolusTime ->
			abs(Duration.between(bolusTime, carb.time).seconds) <= 120
		}
	}.sortedBy { it.time.toInstant() }

	val parsedBasal = basalPoints.mapNotNull {
		val t = parseChartTime(it.time) ?: return@mapNotNull null
		ParsedBasalPoint(t, it.CommandedBasalRate.toFloat())
	}.sortedBy { it.time.toInstant() }

	val parsedIob = iobPoints.mapNotNull {
		val t = parseChartTime(it.time) ?: return@mapNotNull null
		ParsedIobPoint(t, it.iob.toFloat())
	}.sortedBy { it.time.toInstant() }


	val parsedDeviceEvents = deviceStateEvents.mapNotNull {
		val t = parseChartTime(it.time) ?: return@mapNotNull null
		val type = it.eventType ?: return@mapNotNull null
		ParsedDeviceEvent(t, type, it.eventLabel ?: shortEventLabel(type), it.sourceEventId)
	}.sortedBy { it.time.toInstant() }


	val allTimes = buildList {
		addAll(parsedPoints.map { it.time })
		addAll(parsedBolus.map { it.time })
		addAll(parsedCarbs.map { it.time })
		addAll(parsedBasal.map { it.time })
		addAll(parsedIob.map { it.time })
		addAll(userModeSegments.flatMap { listOf(it.start, it.end) })
		addAll(parsedDeviceEvents.map { it.time })
	}.sorted()

	if (allTimes.isEmpty() && fixedWindowStart == null && fixedWindowEnd == null) return

	val visibleStart = fixedWindowStart
		?: allTimes.firstOrNull()
		?: OffsetDateTime.now(ZoneOffset.UTC).withHour(0).withMinute(0).withSecond(0).withNano(0)

	val visibleEnd = fixedWindowEnd ?: visibleStart.plusHours(windowHours.toLong())
	val visibleSeconds = Duration.between(visibleStart, visibleEnd).seconds.toFloat().coerceAtLeast(1f)

	fun inVisibleRange(time: OffsetDateTime): Boolean {
		return !time.isBefore(visibleStart) && !time.isAfter(visibleEnd)
	}

	val visiblePoints = parsedPoints.filter { inVisibleRange(it.time) }
	val visibleBolus = parsedBolus.filter { inVisibleRange(it.time) }
	val visibleCarbs = parsedCarbs.filter { inVisibleRange(it.time) }
	val visibleBasal = parsedBasal.filter { inVisibleRange(it.time) }
	val visibleIob = parsedIob.filter { inVisibleRange(it.time) }
	val maxCgmGapSeconds = 15 * 60L
	val maxIobGapSeconds = 15 * 60L
	val maxBasalHoldSeconds = 30 * 60L
	val visibleDeviceEvents = parsedDeviceEvents.filter { inVisibleRange(it.time) }
	val visibleUserModeSegments = userModeSegments.filter { segment ->
		segment.end.isAfter(visibleStart) && segment.start.isBefore(visibleEnd)
	}

	val choTextColor = Color(0xFF7E57C2)
	val manualBolusDiamondColor = Color(0xFF1565C0)
	val iobLineColor = Color(0xFF8D6E63)
	val iobFillColor = Color(0x33B08968)
	val iobPointColor = Color(0xFFB08968)
	val bolusBlue = Color(0xFF1565C0)

	val topDayBarColorA = Color(0xFFDCEBFF)
	val topDayBarColorB = Color(0xFFFFE9D6)
	val topDayBorderColor = Color(0xFFB0B0B0)
	val midnightLineColor = Color(0xFF555555)
	val centerGuideColor = Color(0xFFFF2D2D)
	val panelBorderColor = Color(0xFF6F6F6F)

	val eventLineColor = Color(0xFF0D47A1)
	val eventFillColor = Color(0x552965D8)
	val eventStrokeColor = Color(0xFF1A237E)

	fun cgmColor(value: Float): Color = when {
		value < 70f -> Color(0xFFE53935)
		value <= 180f -> Color(0xFF4CAF50)
		else -> Color(0xFFFF9800)
	}

	Canvas(
		modifier = Modifier
			.fillMaxWidth()
			.height(canvasHeight)
			.pointerInput(visibleSeconds, onPanBySeconds) {
				detectHorizontalDragGestures { _, dragAmount ->
					val widthPx = size.width.toFloat().coerceAtLeast(1f)
					val secondsPerPixel = visibleSeconds / widthPx
					val deltaSeconds = (-dragAmount * secondsPerPixel).toLong()
					onPanBySeconds?.invoke(deltaSeconds)
				}
			}
	) {
		val width = size.width
		val height = size.height

		val virtualHeight = GlucoseChartLayout.Chart.axisLabelY
		val yScale = height / virtualHeight
		val mScale = yScale.coerceIn(0.9f, 1.25f)

		val chartLeft = GlucoseChartLayout.chartLeft
		val chartRight = width - GlucoseChartLayout.chartRightPadding
		val chartWidth = chartRight - chartLeft
		val centerGuideX = chartLeft + chartWidth / 2f

		val topDayBarTop = GlucoseChartLayout.topDayBarTop * yScale
		val topDayBarBottom = GlucoseChartLayout.topDayBarBottom * yScale
		val topHourRowY = GlucoseChartLayout.topHourRowY * yScale

		val iobTop = GlucoseChartLayout.iobTop * yScale
		val iobBottom = GlucoseChartLayout.iobBottom * yScale
		val iobScaleMax = 10f

		val cgmTop = GlucoseChartLayout.cgmTop * yScale
		val cgmBottom = GlucoseChartLayout.cgmBottom * yScale

		val basalTop = GlucoseChartLayout.basalTop * yScale
		val basalBottom = GlucoseChartLayout.basalBottom * yScale

		val eventTop = GlucoseChartLayout.eventTop * yScale
		val eventBottom = GlucoseChartLayout.eventBottom * yScale
		val statusLineY = GlucoseChartLayout.statusLineY * yScale

		val fixedBgLabelY = GlucoseChartLayout.fixedBgLabelY * yScale
		val fixedChoLabelY = GlucoseChartLayout.fixedChoLabelY * yScale

		val axisTickTop = GlucoseChartLayout.axisTickTop * yScale
		val axisTickBottom = GlucoseChartLayout.axisTickBottom * yScale
		val axisLabelY = GlucoseChartLayout.axisLabelY * yScale

		// Scaled Marker Variables
		val mRadius = GlucoseChartLayout.EventMarkers.radius * mScale
		val mOffsetAbove = GlucoseChartLayout.EventMarkers.outsideOffsetAboveLine * mScale
		val mOffsetBelow = GlucoseChartLayout.EventMarkers.outsideOffsetBelowLine * mScale

		// Alternating lane backgrounds (drawn before grid lines)
		val lighterLaneColor = Color(0xFFFAF9FA)
		val darkerLaneColor = Color(0xFFF0EDF0)

		// IOB Lane (Lighter)
		drawRect(
			color = lighterLaneColor,
			topLeft = Offset(chartLeft, iobTop),
			size = Size(chartWidth, cgmTop - iobTop)
		)
		// CGM Lane (Darker)
		drawRect(
			color = darkerLaneColor,
			topLeft = Offset(chartLeft, cgmTop),
			size = Size(chartWidth, basalTop - cgmTop)
		)
		// BASAL Lane (Lighter)
		drawRect(
			color = lighterLaneColor,
			topLeft = Offset(chartLeft, basalTop),
			size = Size(chartWidth, eventTop - basalTop)
		)
		// EVENT Lane (Darker)
		drawRect(
			color = darkerLaneColor,
			topLeft = Offset(chartLeft, eventTop),
			size = Size(chartWidth, eventBottom - eventTop)
		)

		fun xFor(time: OffsetDateTime): Float {
			val seconds = Duration.between(visibleStart, time).seconds.toFloat()
			val clamped = seconds.coerceIn(0f, visibleSeconds)
			return chartLeft + (clamped / visibleSeconds) * chartWidth
		}

		fun cgmY(value: Float): Float =
			cgmBottom - ((value / 400f).coerceIn(0f, 1f) * (cgmBottom - cgmTop))

		val basalScaleMax = maxOf(6f, visibleBasal.maxOfOrNull { it.rate } ?: 0f)
		fun basalY(rate: Float): Float =
			basalBottom - ((rate / basalScaleMax).coerceIn(0f, 1f) * (basalBottom - basalTop))

		fun iobY(value: Float): Float =
			iobBottom - ((value / iobScaleMax).coerceIn(0f, 1f) * (iobBottom - iobTop))

		fun interpolateIobValue(time: OffsetDateTime): Float {
			if (visibleIob.isEmpty()) return 0f
			if (time < visibleIob.first().time || time > visibleIob.last().time) return 0f

			for (i in 0 until visibleIob.lastIndex) {
				val p1 = visibleIob[i]
				val p2 = visibleIob[i + 1]
				if (time >= p1.time && time <= p2.time) {
					val totalSeconds = Duration.between(p1.time, p2.time).seconds
					if (totalSeconds > maxIobGapSeconds) return 0f
					val total = totalSeconds.toFloat().coerceAtLeast(1f)
					val part = Duration.between(p1.time, time).seconds.toFloat().coerceIn(0f, total)
					val ratio = part / total
					return p1.value + (p2.value - p1.value) * ratio
				}
			}

			return visibleIob.firstOrNull { it.time == time }?.value ?: 0f
		}

		val y70 = cgmY(70f)
		val y180 = cgmY(180f)

		val bolusPaint = Paint().apply {
			color = bolusBlue.toArgbInt()
			textAlign = Paint.Align.CENTER
			textSize = GlucoseChartLayout.fontBolus
			isAntiAlias = true
		}

		val manualBgPaint = Paint().apply {
			textAlign = Paint.Align.CENTER
			textSize = GlucoseChartLayout.fontBg
			isAntiAlias = true
			isFakeBoldText = true
		}

		val choPaint = Paint().apply {
			color = choTextColor.toArgbInt()
			textAlign = Paint.Align.CENTER
			textSize = GlucoseChartLayout.fontCho
			isAntiAlias = true
			isFakeBoldText = true
		}

		val choLegendPaint = Paint().apply {
			color = choTextColor.toArgbInt()
			textAlign = Paint.Align.LEFT
			textSize = GlucoseChartLayout.fontChoLegend
			isAntiAlias = true
			isFakeBoldText = true
		}

		val topHourPaint = Paint().apply {
			color = android.graphics.Color.DKGRAY
			textAlign = Paint.Align.CENTER
			textSize = GlucoseChartLayout.fontTopHours
			isAntiAlias = true
		}

		val bottomHourPaint = Paint().apply {
			color = android.graphics.Color.DKGRAY
			textAlign = Paint.Align.CENTER
			textSize = GlucoseChartLayout.fontBottomHours
			isAntiAlias = true
		}

		val topDayPaint = Paint().apply {
			color = android.graphics.Color.DKGRAY
			textAlign = Paint.Align.CENTER
			textSize = GlucoseChartLayout.fontDay
			isAntiAlias = true
			isFakeBoldText = true
		}

		val smallScalePaint = Paint().apply {
			color = android.graphics.Color.DKGRAY
			textAlign = Paint.Align.LEFT
			textSize = GlucoseChartLayout.fontIobScale
			isAntiAlias = true
		}

		val cgmScaleLeftPaint = Paint().apply {
			color = android.graphics.Color.DKGRAY
			textAlign = Paint.Align.RIGHT
			textSize = GlucoseChartLayout.fontScaleLeft
			isAntiAlias = true
		}

		val thresholdPaint = Paint().apply {
			textAlign = Paint.Align.LEFT
			textSize = GlucoseChartLayout.fontScaleRight
			isAntiAlias = true
		}

		val statusBadgePaint = Paint().apply {
			color = eventLineColor.toArgbInt()
			textAlign = Paint.Align.CENTER
			textSize = GlucoseChartLayout.fontEvent
			isAntiAlias = true
			isFakeBoldText = true
		}

		val sectionLabelPaint = Paint().apply {
			color = android.graphics.Color.DKGRAY
			textAlign = Paint.Align.LEFT
			textSize = GlucoseChartLayout.fontSectionLabel
			isAntiAlias = true
			isFakeBoldText = true
		}

		val dayFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
		var currentDay: LocalDate = visibleStart.toLocalDate()
		var dayIndex = 0

		while (!currentDay.isAfter(visibleEnd.toLocalDate())) {
			val dayStart = currentDay.atStartOfDay().atOffset(visibleStart.offset)
			val nextDayStart = currentDay.plusDays(1).atStartOfDay().atOffset(visibleStart.offset)

			val segmentStart = if (dayStart.isBefore(visibleStart)) visibleStart else dayStart
			val segmentEnd = if (nextDayStart.isAfter(visibleEnd)) visibleEnd else nextDayStart

			if (!segmentEnd.isBefore(segmentStart) && segmentEnd != segmentStart) {
				val left = xFor(segmentStart)
				val right = xFor(segmentEnd)
				val segmentColor = if (dayIndex % 2 == 0) topDayBarColorA else topDayBarColorB

				drawRect(
					color = segmentColor,
					topLeft = Offset(left, topDayBarTop),
					size = Size((right - left).coerceAtLeast(1f), topDayBarBottom - topDayBarTop)
				)
				drawRect(
					color = topDayBorderColor,
					topLeft = Offset(left, topDayBarTop),
					size = Size((right - left).coerceAtLeast(1f), topDayBarBottom - topDayBarTop),
					style = Stroke(width = 1f)
				)

				val dayMetrics = topDayPaint.fontMetrics
				val dayTextY = (topDayBarTop + topDayBarBottom) / 2f - (dayMetrics.ascent + dayMetrics.descent) / 2f
				drawContext.canvas.nativeCanvas.drawText(
					currentDay.format(dayFormatter),
					(left + right) / 2f,
					dayTextY,
					topDayPaint
				)
			}

			currentDay = currentDay.plusDays(1)
			dayIndex++
		}

		fun hourFloor(time: OffsetDateTime): OffsetDateTime =
			time.withMinute(0).withSecond(0).withNano(0)

		var hourCursor = hourFloor(visibleStart)
		if (hourCursor.isAfter(visibleStart)) {
			hourCursor = hourCursor.minusHours(1)
		}

		while (!hourCursor.isAfter(visibleEnd.plusHours(1))) {
			val x = xFor(hourCursor)
			val isMajor = hourCursor.hour % 2 == 0

			drawLine(
				color = if (isMajor) Color(0x22000000) else Color(0x14000000),
				start = Offset(x, iobTop),
				end = Offset(x, eventBottom),
				strokeWidth = if (isMajor) 1.2f else 1f
			)

			if (isMajor && x >= chartLeft - 20f && x <= chartRight + 20f) {
				val label = hourCursor.hour.toString().padStart(2, '0')
				drawContext.canvas.nativeCanvas.drawText(label, x, topHourRowY, topHourPaint)
				drawContext.canvas.nativeCanvas.drawText(label, x, axisLabelY, bottomHourPaint)
				drawLine(Color(0xFF9E9E9E), Offset(x, axisTickTop), Offset(x, axisTickBottom), 1.2f)
			}

			hourCursor = hourCursor.plusHours(1)
		}

		var midnightCursor = visibleStart.toLocalDate().atStartOfDay().atOffset(visibleStart.offset)
		if (midnightCursor.isBefore(visibleStart)) {
			midnightCursor = midnightCursor.plusDays(1)
		}

		while (!midnightCursor.isAfter(visibleEnd)) {
			val x = xFor(midnightCursor)
			drawLine(
				color = midnightLineColor,
				start = Offset(x, iobTop),
				end = Offset(x, eventBottom),
				strokeWidth = 1.8f
			)
			midnightCursor = midnightCursor.plusDays(1)
		}

		drawLine(
			color = centerGuideColor,
			start = Offset(centerGuideX, iobTop),
			end = Offset(centerGuideX, eventBottom),
			strokeWidth = 1.1f
		)

		drawContext.canvas.nativeCanvas.drawText("IOB", chartLeft, iobTop + 10f * yScale, sectionLabelPaint)
		drawContext.canvas.nativeCanvas.drawText("CGM", chartLeft, cgmTop + 10f * yScale, sectionLabelPaint)
		drawContext.canvas.nativeCanvas.drawText("BASAL", chartLeft, basalTop + 10f * yScale, sectionLabelPaint)
		drawContext.canvas.nativeCanvas.drawText("EVENT", chartLeft, eventTop + 10f * yScale, sectionLabelPaint)

		val laneBorderColor = Color(0xFF262626)
		val laneBorderStroke = 0.75f
		drawLine(laneBorderColor, Offset(chartLeft, iobTop), Offset(chartRight, iobTop), laneBorderStroke)
		drawLine(laneBorderColor, Offset(chartLeft, cgmTop), Offset(chartRight, cgmTop), laneBorderStroke)
		drawLine(laneBorderColor, Offset(chartLeft, basalTop), Offset(chartRight, basalTop), laneBorderStroke)
		drawLine(laneBorderColor, Offset(chartLeft, eventTop), Offset(chartRight, eventTop), laneBorderStroke)
		drawLine(laneBorderColor, Offset(chartLeft, basalBottom), Offset(chartRight, basalBottom), laneBorderStroke)
		drawLine(laneBorderColor, Offset(chartLeft, eventBottom), Offset(chartRight, eventBottom), laneBorderStroke)

		// Target range background removed: keep only 70/180 threshold lines.

		for (v in 50..400 step 50) {
			val y = cgmY(v.toFloat())
			drawLine(Color(0x14000000), Offset(chartLeft, y), Offset(chartRight, y), 1f)
		}

		drawLine(Color(0xFFE53935), Offset(chartLeft, y70), Offset(chartRight, y70), 1.5f)
		drawLine(Color(0xFFCC6600), Offset(chartLeft, y180), Offset(chartRight, y180), 1.5f)

		for (i in 0 until visiblePoints.lastIndex) {
			val p1 = visiblePoints[i]
			val p2 = visiblePoints[i + 1]
			val x1 = xFor(p1.time)
			val y1 = cgmY(p1.value)
			val color = cgmColor(p1.value)
			drawCircle(color, 1.8f, Offset(x1, y1))

			if (Duration.between(p1.time, p2.time).seconds <= maxCgmGapSeconds) {
				val x2 = xFor(p2.time)
				val y2 = cgmY(p2.value)
				drawLine(color, Offset(x1, y1), Offset(x2, y2), 2.2f)
			}
		}

		visiblePoints.lastOrNull()?.also {
			drawCircle(cgmColor(it.value), 1.8f, Offset(xFor(it.time), cgmY(it.value)))
		}

		val cgmScaleLeftX = chartLeft - 6f
		listOf(400f, 300f, 200f, 100f, 50f).forEach { value ->
			val y = cgmY(value)
			drawLine(Color(0x33000000), Offset(chartLeft - 6f, y), Offset(chartLeft, y), 1f)
			drawContext.canvas.nativeCanvas.drawText(
				value.toInt().toString(),
				cgmScaleLeftX,
				y + 4f * yScale,
				cgmScaleLeftPaint
			)
		}

		val cgmScaleRightX = chartRight + 10f
		listOf(180f, 70f).forEach { value ->
			val y = cgmY(value)
			drawLine(Color(0x33000000), Offset(chartRight, y), Offset(chartRight + 6f, y), 1f)
			thresholdPaint.color = if (value == 180f) {
				android.graphics.Color.rgb(204, 102, 0)
			} else {
				android.graphics.Color.rgb(229, 57, 53)
			}
			drawContext.canvas.nativeCanvas.drawText(
				value.toInt().toString(),
				cgmScaleRightX,
				y + 4f * yScale,
				thresholdPaint
			)
		}

		drawLine(eventLineColor, Offset(chartLeft, statusLineY), Offset(chartRight, statusLineY), 1.8f)

		visibleUserModeSegments.forEach { segment ->
			val clippedStart = if (segment.start.isBefore(visibleStart)) visibleStart else segment.start
			val clippedEnd = if (segment.end.isAfter(visibleEnd)) visibleEnd else segment.end
			if (!clippedEnd.isAfter(clippedStart)) return@forEach

			val left = xFor(clippedStart)
			val right = xFor(clippedEnd)
			val segWidth = (right - left).coerceAtLeast(2f)
			val barTop = statusLineY - 16f * yScale
			val barHeight = 12f * yScale
			val normalizedMode = normalizeUserMode(segment.mode)

			when (normalizedMode) {
				"Exercise" -> {
					// Exercise: unica modalità con barra celeste chiaro, interrotta quando torna Normal.
					val exerciseFill = Color(0xFFB3E5FC)
					val exerciseStroke = Color(0xFF1E88E5)
					drawRect(exerciseFill, Offset(left, barTop), Size(segWidth, barHeight))
					drawRect(
						exerciseStroke,
						Offset(left, barTop),
						Size(segWidth, barHeight),
						style = Stroke(width = 1.2f * yScale)
					)

					// Icona Exercise sotto la barra EVENT: cerchio con omino che corre e punta verso l'alto.
					val iconCx = left.coerceIn(chartLeft + 10f, chartRight - 10f)
					val iconCy = statusLineY + mOffsetBelow
					drawEventMarkerBase(
						cx = iconCx,
						cy = iconCy,
						fill = exerciseStroke,
						belowEventLine = true,
						r = mRadius,
						mScale = mScale
					)
					// testa
					drawCircle(exerciseStroke, GlucoseChartLayout.EventMarkers.exerciseHeadRadius * mScale, Offset(iconCx + GlucoseChartLayout.EventMarkers.exerciseHeadOffsetX * mScale, iconCy + GlucoseChartLayout.EventMarkers.exerciseHeadOffsetY * mScale))
					// corpo + braccia + gambe stilizzate
					drawLine(exerciseStroke, Offset(iconCx + GlucoseChartLayout.EventMarkers.exerciseBodyStartX * mScale, iconCy + GlucoseChartLayout.EventMarkers.exerciseBodyStartY * mScale), Offset(iconCx + GlucoseChartLayout.EventMarkers.exerciseBodyEndX * mScale, iconCy + GlucoseChartLayout.EventMarkers.exerciseBodyEndY * mScale), GlucoseChartLayout.EventMarkers.exerciseStrokeWidth * mScale)
					drawLine(exerciseStroke, Offset(iconCx + GlucoseChartLayout.EventMarkers.exerciseArmStartX * mScale, iconCy + GlucoseChartLayout.EventMarkers.exerciseArmStartY * mScale), Offset(iconCx + GlucoseChartLayout.EventMarkers.exerciseArmEndX * mScale, iconCy + GlucoseChartLayout.EventMarkers.exerciseArmEndY * mScale), GlucoseChartLayout.EventMarkers.exerciseStrokeWidth * mScale)
					drawLine(exerciseStroke, Offset(iconCx + GlucoseChartLayout.EventMarkers.exerciseLeg1StartX * mScale, iconCy + GlucoseChartLayout.EventMarkers.exerciseLeg1StartY * mScale), Offset(iconCx + GlucoseChartLayout.EventMarkers.exerciseLeg1EndX * mScale, iconCy + GlucoseChartLayout.EventMarkers.exerciseLeg1EndY * mScale), GlucoseChartLayout.EventMarkers.exerciseStrokeWidth * mScale)
					drawLine(exerciseStroke, Offset(iconCx + GlucoseChartLayout.EventMarkers.exerciseLeg2StartX * mScale, iconCy + GlucoseChartLayout.EventMarkers.exerciseLeg2StartY * mScale), Offset(iconCx + GlucoseChartLayout.EventMarkers.exerciseLeg2EndX * mScale, iconCy + GlucoseChartLayout.EventMarkers.exerciseLeg2EndY * mScale), GlucoseChartLayout.EventMarkers.exerciseStrokeWidth * mScale)
				}

				"Sleep" -> {
					// Sleep/Notte: barra blu scuro sopra EVENT, interrotta quando torna Normal.
					val sleepFill = Color(0xFF0D47A1)
					val sleepStroke = Color(0xFF002171)
					drawRect(sleepFill, Offset(left, barTop), Size(segWidth, barHeight))
					drawRect(
						sleepStroke,
						Offset(left, barTop),
						Size(segWidth, barHeight),
						style = Stroke(width = 1.2f * yScale)
					)

					// Icona notte sotto la barra EVENT: cerchio con ZZ e punta verso l'alto.
					val iconCx = left.coerceIn(chartLeft + 10f, chartRight - 10f)
					val iconCy = statusLineY + mOffsetBelow
					drawEventMarkerBase(
						cx = iconCx,
						cy = iconCy,
						fill = sleepFill,
						belowEventLine = true,
						r = mRadius,
						mScale = mScale
					)
					val zPaint = Paint().apply {
						color = sleepFill.toArgbInt()
						textAlign = Paint.Align.CENTER
						textSize = GlucoseChartLayout.EventMarkers.sleepTextSize * mScale
						isAntiAlias = true
						isFakeBoldText = true
					}
					drawContext.canvas.nativeCanvas.drawText("ZZ", iconCx, iconCy + GlucoseChartLayout.EventMarkers.sleepTextBaselineOffset * mScale, zPaint)
				}
			}
		}

		visibleDeviceEvents.forEach { event ->
			val cx = xFor(event.time).coerceIn(chartLeft, chartRight)
			val belowEventLine = event.eventType == "cartridge_site_change" || event.eventType == "sensor_session_ended"
			val cy = if (belowEventLine) statusLineY + mOffsetBelow else statusLineY - mOffsetAbove
			val fill = deviceEventColor(event.eventType)
			val r = mRadius

			drawEventMarkerBase(
				cx = cx,
				cy = cy,
				fill = fill,
				belowEventLine = belowEventLine,
				r = r,
				mScale = mScale
			)

			when (event.eventType) {
				"pump_suspended" -> {
					drawLine(fill, Offset(cx - GlucoseChartLayout.EventMarkers.pauseBarOffsetX * mScale, cy - GlucoseChartLayout.EventMarkers.pauseBarHalfHeight * mScale), Offset(cx - GlucoseChartLayout.EventMarkers.pauseBarOffsetX * mScale, cy + GlucoseChartLayout.EventMarkers.pauseBarHalfHeight * mScale), GlucoseChartLayout.EventMarkers.pauseStrokeWidth * mScale)
					drawLine(fill, Offset(cx + GlucoseChartLayout.EventMarkers.pauseBarOffsetX * mScale, cy - GlucoseChartLayout.EventMarkers.pauseBarHalfHeight * mScale), Offset(cx + GlucoseChartLayout.EventMarkers.pauseBarOffsetX * mScale, cy + GlucoseChartLayout.EventMarkers.pauseBarHalfHeight * mScale), GlucoseChartLayout.EventMarkers.pauseStrokeWidth * mScale)
				}
				"pump_resumed" -> {
					val playPath = Path().apply {
						moveTo(cx - GlucoseChartLayout.EventMarkers.playLeftOffsetX * mScale, cy - GlucoseChartLayout.EventMarkers.playHalfHeight * mScale)
						lineTo(cx - GlucoseChartLayout.EventMarkers.playLeftOffsetX * mScale, cy + GlucoseChartLayout.EventMarkers.playHalfHeight * mScale)
						lineTo(cx + GlucoseChartLayout.EventMarkers.playRightOffsetX * mScale, cy)
						close()
					}
					drawPath(playPath, color = fill, style = Fill)
				}
				"cartridge_site_change" -> {
					val cartridgeDrop = Path().apply {
						moveTo(cx, cy - GlucoseChartLayout.EventMarkers.cartridgeDropTop * mScale)
						quadraticBezierTo(cx - GlucoseChartLayout.EventMarkers.cartridgeDropControlX * mScale, cy - GlucoseChartLayout.EventMarkers.cartridgeDropControlY * mScale, cx - GlucoseChartLayout.EventMarkers.cartridgeDropSideX * mScale, cy + GlucoseChartLayout.EventMarkers.cartridgeDropSideY * mScale)
						quadraticBezierTo(cx - GlucoseChartLayout.EventMarkers.cartridgeDropBottomControlX * mScale, cy + GlucoseChartLayout.EventMarkers.cartridgeDropBottomY * mScale, cx, cy + GlucoseChartLayout.EventMarkers.cartridgeDropBottomY * mScale)
						quadraticBezierTo(cx + GlucoseChartLayout.EventMarkers.cartridgeDropBottomControlX * mScale, cy + GlucoseChartLayout.EventMarkers.cartridgeDropBottomY * mScale, cx + GlucoseChartLayout.EventMarkers.cartridgeDropSideX * mScale, cy + GlucoseChartLayout.EventMarkers.cartridgeDropSideY * mScale)
						quadraticBezierTo(cx + GlucoseChartLayout.EventMarkers.cartridgeDropControlX * mScale, cy - GlucoseChartLayout.EventMarkers.cartridgeDropControlY * mScale, cx, cy - GlucoseChartLayout.EventMarkers.cartridgeDropTop * mScale)
						close()
					}
					drawPath(cartridgeDrop, color = fill, style = Fill)
				}
				"sensor_session_ended" -> {
					drawLine(fill, Offset(cx - GlucoseChartLayout.EventMarkers.xHalfSize * mScale, cy - GlucoseChartLayout.EventMarkers.xHalfSize * mScale), Offset(cx + GlucoseChartLayout.EventMarkers.xHalfSize * mScale, cy + GlucoseChartLayout.EventMarkers.xHalfSize * mScale), GlucoseChartLayout.EventMarkers.xStrokeWidth * mScale)
					drawLine(fill, Offset(cx + GlucoseChartLayout.EventMarkers.xHalfSize * mScale, cy - GlucoseChartLayout.EventMarkers.xHalfSize * mScale), Offset(cx - GlucoseChartLayout.EventMarkers.xHalfSize * mScale, cy + GlucoseChartLayout.EventMarkers.xHalfSize * mScale), GlucoseChartLayout.EventMarkers.xStrokeWidth * mScale)
				}
			}
		}

		drawLine(Color(0x14000000), Offset(chartLeft, iobY(2f)), Offset(chartRight, iobY(2f)), 1f)
		drawLine(Color(0x14000000), Offset(chartLeft, iobY(4f)), Offset(chartRight, iobY(4f)), 1f)
		drawLine(Color(0x14000000), Offset(chartLeft, iobY(6f)), Offset(chartRight, iobY(6f)), 1f)
		drawLine(Color(0x14000000), Offset(chartLeft, iobY(8f)), Offset(chartRight, iobY(8f)), 1f)
		drawLine(Color(0xFF8D6E63), Offset(chartLeft, iobBottom), Offset(chartRight, iobBottom), 1.4f)

		val iobScaleX = chartRight + 10f
		listOf(10f, 8f, 6f, 4f, 2f, 0f).forEach { value ->
			val y = iobY(value)
			drawLine(Color(0x33000000), Offset(chartRight, y), Offset(chartRight + 6f, y), 1f)
			drawContext.canvas.nativeCanvas.drawText(
				formatScaleLabel(value),
				iobScaleX,
				y + 4f * yScale,
				smallScalePaint
			)
		}

		if (visibleIob.isNotEmpty()) {
			var segmentStart = 0
			while (segmentStart < visibleIob.size) {
				var segmentEnd = segmentStart
				while (segmentEnd < visibleIob.lastIndex &&
					Duration.between(visibleIob[segmentEnd].time, visibleIob[segmentEnd + 1].time).seconds <= maxIobGapSeconds
				) {
					segmentEnd++
				}

				if (segmentEnd > segmentStart) {
					val fillPath = Path().apply {
						val first = visibleIob[segmentStart]
						moveTo(xFor(first.time), iobBottom)
						lineTo(xFor(first.time), iobY(first.value))
						for (i in segmentStart + 1..segmentEnd) {
							val p = visibleIob[i]
							lineTo(xFor(p.time), iobY(p.value))
						}
						val last = visibleIob[segmentEnd]
						lineTo(xFor(last.time), iobBottom)
						close()
					}
					drawPath(fillPath, iobFillColor)
				}

				segmentStart = segmentEnd + 1
			}

			for (i in 0 until visibleIob.lastIndex) {
				val p1 = visibleIob[i]
				val p2 = visibleIob[i + 1]
				if (Duration.between(p1.time, p2.time).seconds <= maxIobGapSeconds) {
					drawLine(
						color = iobLineColor,
						start = Offset(xFor(p1.time), iobY(p1.value)),
						end = Offset(xFor(p2.time), iobY(p2.value)),
						strokeWidth = 1.6f
					)
				}
			}

			visibleIob.forEach { p ->
				drawCircle(
					color = iobPointColor,
					radius = 1.6f,
					center = Offset(xFor(p.time), iobY(p.value))
				)
			}
		}

		var row1EndX = Float.NEGATIVE_INFINITY
		var row2EndX = Float.NEGATIVE_INFINITY
		val labelHalfWidth = 14f

		visibleBolus.forEach { event ->
			val x = xFor(event.time)
			val currentIob = interpolateIobValue(event.time).coerceIn(0f, iobScaleMax)
			val topIobValue = (currentIob + event.value).coerceIn(0f, iobScaleMax)

			val yBase = iobY(currentIob)
			val yTop = iobY(topIobValue)
			val isManual = event.bolusType == "carb"

			drawLine(
				color = bolusBlue,
				start = Offset(x, yBase),
				end = Offset(x, yTop),
				strokeWidth = 2.4f
			)

			if (event.isExtended) {
				val durationMin = event.extendedDurationMin?.coerceAtLeast(0) ?: 0
				val extendedInsulin = event.extendedInsulin ?: 0f
				if (durationMin > 0) {
					val xEnd = xFor(event.time.plusMinutes(durationMin.toLong())).coerceIn(chartLeft, chartRight)
					val yExtended = iobY(0f)
					drawLine(
						color = bolusBlue,
						start = Offset(x, yExtended),
						end = Offset(xEnd, yExtended),
						strokeWidth = 6.0f
					)

					if (extendedInsulin > 0f) {
						drawContext.canvas.nativeCanvas.drawText(
							formatBolusLabel(extendedInsulin),
							(x + xEnd) / 2f,
							yExtended - 7f * yScale,
							bolusPaint
						)
					}
				}
			}

			if (isManual) {
				drawDiamond(
					center = Offset(x, yTop),
					radius = 6.8f * mScale,
					fillColor = manualBolusDiamondColor
				)
			} else {
				drawCircle(
					color = bolusBlue,
					radius = 4.5f * mScale,
					center = Offset(x, yTop)
				)
			}

			val canUseRow1 = x - row1EndX >= labelHalfWidth * 2
			val canUseRow2 = x - row2EndX >= labelHalfWidth * 2
			val labelRow = when {
				canUseRow1 -> 1
				canUseRow2 -> 2
				row1EndX <= row2EndX -> 1
				else -> 2
			}

			if (labelRow == 1) {
				row1EndX = x + labelHalfWidth
			} else {
				row2EndX = x + labelHalfWidth
			}

			val bolusLabelY = if (labelRow == 1) yTop - 10f * yScale else yTop - 20f * yScale

			val displayedBolusValue = if (event.isExtended) {
				event.immediateInsulin ?: event.value
			} else {
				event.value
			}

			drawContext.canvas.nativeCanvas.drawText(
				formatBolusLabel(displayedBolusValue),
				x,
				bolusLabelY,
				bolusPaint
			)

			if (isManual) {
				event.bgMgdl?.let { bg ->
					manualBgPaint.color = cgmColor(bg).toArgbInt()
					drawContext.canvas.nativeCanvas.drawText(
						bg.toInt().toString(),
						x,
						fixedBgLabelY,
						manualBgPaint
					)
				}

				val choLabel = if (event.isExtended == true) {
					formatWholeNumber(event.carbsGrams ?: 0f) + "E"
				} else {
					formatWholeNumber(event.carbsGrams ?: 0f)
				}

				drawContext.canvas.nativeCanvas.drawText(
					choLabel,
					x,
					fixedChoLabelY,
					choPaint
				)
			}
		}

		visibleCarbs.forEach {
			val x = xFor(it.time)
			drawContext.canvas.nativeCanvas.drawText(
				formatWholeNumber(it.grams),
				x,
				fixedChoLabelY,
				choPaint
			)
		}

		drawContext.canvas.nativeCanvas.drawText("CHO", chartLeft, fixedChoLabelY, choLegendPaint)

		drawLine(
			Color(0xFFBDBDBD),
			Offset(chartLeft, basalTop + (basalBottom - basalTop) / 3f),
			Offset(chartRight, basalTop + (basalBottom - basalTop) / 3f),
			1f
		)
		drawLine(
			Color(0xFFBDBDBD),
			Offset(chartLeft, basalTop + 2f * (basalBottom - basalTop) / 3f),
			Offset(chartRight, basalTop + 2f * (basalBottom - basalTop) / 3f),
			1f
		)

		if (visibleBasal.isNotEmpty()) {
			visibleBasal.forEachIndexed { index, sample ->
				val nextSample = if (index < visibleBasal.lastIndex) visibleBasal[index + 1] else null
				val xStart = xFor(sample.time)
				val segmentEndTime = when {
					nextSample != null && Duration.between(sample.time, nextSample.time).seconds <= maxBasalHoldSeconds -> nextSample.time
					nextSample != null -> sample.time.plusSeconds(maxBasalHoldSeconds)
					Duration.between(sample.time, visibleEnd).seconds <= maxBasalHoldSeconds -> visibleEnd
					else -> sample.time.plusSeconds(maxBasalHoldSeconds)
				}
				val xEnd = xFor(segmentEndTime)

				val left = xStart.coerceIn(chartLeft, chartRight)
				val right = xEnd.coerceIn(chartLeft, chartRight)
				if (right <= left) return@forEachIndexed
				val widthBar = (right - left).coerceAtLeast(1f)

				if (sample.rate <= 0.0001f) {
					drawLine(Color(0xFFE53935), Offset(left, basalBottom), Offset(left + widthBar, basalBottom), 1.5f)
				} else {
					val topY = basalY(sample.rate).coerceIn(basalTop, basalBottom)
					drawRect(
						Color(0xFF90CAF9),
						Offset(left, topY),
						Size(widthBar, (basalBottom - topY).coerceAtLeast(1f))
					)
					drawLine(Color(0xFF1E88E5), Offset(left, topY), Offset(left + widthBar, topY), 1.5f)
				}
			}
		}

		val basalScaleX = chartRight + 10f
		listOf(6f, 4f, 2f).forEach { value ->
			drawContext.canvas.nativeCanvas.drawText(
				formatScaleLabel(value),
				basalScaleX,
				basalY(value) + 4f * yScale,
				smallScalePaint
			)
		}
	}
}


private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEventMarkerBase(
	cx: Float,
	cy: Float,
	fill: Color,
	belowEventLine: Boolean,
	r: Float,
	mScale: Float
) {
	val markerTip = Path().apply {
		val tipHalfWidth = r * GlucoseChartLayout.EventMarkers.tipHalfWidthRatio
		val tipLength = r * GlucoseChartLayout.EventMarkers.tipLengthRatio
		if (belowEventLine) {
			// Sotto la barra EVENT: punta verso l'alto.
			moveTo(cx - tipHalfWidth, cy - r + 1f)
			lineTo(cx + tipHalfWidth, cy - r + 1f)
			lineTo(cx, cy - r - tipLength)
		} else {
			// Sopra la barra EVENT: punta verso il basso.
			moveTo(cx - tipHalfWidth, cy + r - 1f)
			lineTo(cx + tipHalfWidth, cy + r - 1f)
			lineTo(cx, cy + r + tipLength)
		}
		close()
	}
	drawPath(markerTip, color = fill, style = Fill)
	drawCircle(fill, r, Offset(cx, cy))
	drawCircle(Color.White, r - GlucoseChartLayout.EventMarkers.innerCircleInset * mScale, Offset(cx, cy))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDiamond(
	center: Offset,
	radius: Float,
	fillColor: Color
) {
	val path = Path().apply {
		moveTo(center.x, center.y - radius)
		lineTo(center.x + radius, center.y)
		lineTo(center.x, center.y + radius)
		lineTo(center.x - radius, center.y)
		close()
	}
	drawPath(path, color = fillColor, style = Fill)
}

private data class ParsedCgmPoint(val time: OffsetDateTime, val value: Float)

private data class ParsedBolusEvent(
	val time: OffsetDateTime,
	val value: Float,
	val bolusType: String,
	val bgMgdl: Float?,
	val carbsGrams: Float?,
	val isExtended: Boolean = false,
	val immediateInsulin: Float?,
	val extendedInsulin: Float?,
	val extendedDurationMin: Int?
)

private data class ParsedCarbEvent(val time: OffsetDateTime, val grams: Float)
private data class ParsedBasalPoint(val time: OffsetDateTime, val rate: Float)
private data class ParsedIobPoint(val time: OffsetDateTime, val value: Float)
private data class ParsedDeviceEvent(
	val time: OffsetDateTime,
	val eventType: String,
	val label: String,
	val sourceEventId: Int?
)


private fun deviceEventColor(eventType: String): Color {
	return when (eventType) {
		"pump_suspended" -> Color(0xFFD32F2F)
		"pump_resumed" -> Color(0xFF43A047)
		"cartridge_site_change" -> Color(0xFFFB8C00)
		"sensor_session_ended" -> Color(0xFF8E24AA)
		else -> Color(0xFF757575)
	}
}

private fun shortEventLabel(eventType: String): String {
	return when (eventType) {
		"pump_suspended" -> "Stop"
		"pump_resumed" -> "Restart"
		"cartridge_site_change" -> "Change set"
		"sensor_session_ended" -> "End Sensor"
		else -> eventType
	}
}

private fun normalizeUserMode(raw: String?): String {
	return when (raw.orEmpty().trim().lowercase()) {
		"exercise", "exercising" -> "Exercise"
		"sleep", "sleeping", "night", "notte" -> "Sleep"
		"eatingsoon", "eating_soon", "eating soon" -> "EatingSoon"
		"normal", "norm" -> "Normal"
		else -> "Normal"
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

private fun formatBolusLabel(value: Float): String = String.format("%.2f", value)
private fun formatWholeNumber(value: Float): String = value.toInt().toString()
private fun formatScaleLabel(value: Float): String = String.format("%.1f", value).replace('.', ',')

private fun Color.toArgbInt(): Int {
	val a = (alpha * 255).toInt().coerceIn(0, 255)
	val r = (red * 255).toInt().coerceIn(0, 255)
	val g = (green * 255).toInt().coerceIn(0, 255)
	val b = (blue * 255).toInt().coerceIn(0, 255)
	return android.graphics.Color.argb(a, r, g, b)
}
