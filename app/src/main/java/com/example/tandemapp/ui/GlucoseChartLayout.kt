package com.example.tandemapp.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object GlucoseChartLayout {
	/*
	 * Parametri UI centralizzati.
	 * Target: telefono reale, portrait, status/nav bar presenti.
	 */
	object HomeUi {
		// Reference screen dimensions (development phone)
		const val REF_WIDTH = 392f
		const val REF_HEIGHT = 800f

		// Reusable adaptive scaling helpers
		fun adaptiveHorizontalPadding(screenWidth: Dp): Dp =
			(screenWidth.value * (12f / REF_WIDTH)).dp.coerceIn(8.dp, 16.dp)

		fun adaptiveVerticalSpacing(screenHeight: Dp, base: Float): Dp =
			(screenHeight.value * (base / REF_HEIGHT)).dp
			
		fun adaptiveHorizontalSpacing(screenWidth: Dp, base: Float): Dp =
			(screenWidth.value * (base / REF_WIDTH)).dp

		fun adaptiveSnapshotWidth(screenWidth: Dp, base: Float): Dp =
			(screenWidth.value * (base / REF_WIDTH)).dp

		fun adaptiveButtonWidth(screenWidth: Dp, base: Float): Dp =
			(screenWidth.value * (base / REF_WIDTH)).dp

		fun adaptiveFontSize(screenWidth: Dp, base: Float): TextUnit =
			(base * (screenWidth.value / REF_WIDTH)).sp

		val screenHorizontalPadding: Dp = 12.dp
		val screenTopPadding: Dp = 2.dp

		val topBarLogoSize: Dp = 54.dp
		val topBarLogoEndPadding: Dp = 4.dp
		val topBarTitleFontSize: TextUnit = 26.sp

		// Adaptive Dashboard title area
		val topBarHeightRatio: Float = 0.065f
		val topBarMinHeight: Dp = 48.dp
		val topBarMaxHeight: Dp = 64.dp
		val topBarTitleBottomPadding: Dp = 4.dp


		val snapshotCardVerticalPadding: Dp = 4.dp
		val snapshotCardHorizontalPadding: Dp = 8.dp
		val snapshotCardCornerRadius: Dp = 18.dp

		val snapshotButtonSize: Dp = 28.dp
		val snapshotLabelMinWidth: Dp = 48.dp
		val snapshotValueMinWidth: Dp = 54.dp
		val snapshotRowGap: Dp = 3.dp
		val snapshotColumnGap: Dp = 6.dp

		val snapshotLabelFontSize: TextUnit = 12.sp
		val snapshotValueFontSize: TextUnit = 15.sp
		val snapshotArrowFontSize: TextUnit = 13.sp

		val chartCardTopPadding: Dp = 0.dp
		val chartCardHorizontalPadding: Dp = 7.dp
		val chartCardBottomPadding: Dp = 4.dp
		val chartCardCornerRadius: Dp = 16.dp
		val chartBottomScrollPadding: Dp = 0.dp
		val chartViewportHeightRatio: Float = 1.0f

		val zoomRowTopPadding: Dp = 1.dp
		val zoomButtonHeight: Dp = 24.dp
		val zoomButtonMinWidth: Dp = 44.dp
		val zoomButtonGap: Dp = 5.dp
		val zoomButtonFontSize: TextUnit = 11.sp
	}

	object Chart {
		/*
		 * Altezza canvas ridotta per far entrare anche BASAL/EVENT su telefono reale.
		 */
		val canvasMinHeight: Dp = 330.dp
		val canvasPreferredHeight: Dp = 376.dp
		val canvasMaxHeight: Dp = 376.dp
		val canvasHeight: Dp = canvasPreferredHeight

		const val chartLeft: Float = 22f
		const val chartRightPadding: Float = 50f

		const val topDayBarTop: Float = 4f
		const val topDayBarHeight: Float = 26f
		const val topHoursGap: Float = 8f

		const val iobTop: Float = 50f
		const val iobHeight: Float = 180f

		const val cgmGapFromIob: Float = 29f
		const val cgmHeight: Float = 280f

		const val basalGapFromCgm: Float = 3f
		const val basalHeight: Float = 88f

		const val eventGapFromBasal: Float = 12f
		const val eventHeight: Float = 120f
		const val eventStatusLineRatio: Float = 0.56f

		val fixedBgLabelY: Float
			get() = cgmTop + fixedBgLabelOffsetFromCgmTop

		const val fixedBgLabelOffsetFromCgmTop: Float = 18f
		const val fixedChoLabelOffsetFromIobBottom: Float = 18f

		const val bottomTicksGap: Float = 6f
		const val bottomTickHeight: Float = 7f
		const val bottomLabelGap: Float = 3f

		// Font sizes canvas
		const val fontTopHours: Float = 18f
		const val fontBottomHours: Float = 18f
		const val fontDay: Float = 20f

		const val fontSectionLabel: Float = 18f

		const val fontBolus: Float = 18f
		const val fontBg: Float = 18f
		const val fontCho: Float = 18f
		const val fontChoLegend: Float = 18f

		const val fontScaleLeft: Float = 18f
		const val fontScaleRight: Float = 18f
		const val fontIobScale: Float = 18f
		const val fontBasalScale: Float = 18f

		const val fontEvent: Float = 18f

		val topDayBarBottom: Float
			get() = topDayBarTop + topDayBarHeight

		val topHourRowY: Float
			get() = topDayBarBottom + topHoursGap

		val iobBottom: Float
			get() = iobTop + iobHeight

		val cgmTop: Float
			get() = iobBottom + cgmGapFromIob

		val cgmBottom: Float
			get() = cgmTop + cgmHeight

		val basalTop: Float
			get() = cgmBottom + basalGapFromCgm

		val basalBottom: Float
			get() = basalTop + basalHeight

		val eventTop: Float
			get() = basalBottom + eventGapFromBasal

		val eventBottom: Float
			get() = eventTop + eventHeight

		val statusLineY: Float
			get() = eventTop + eventHeight * eventStatusLineRatio

		val fixedChoLabelY: Float
			get() = iobBottom + fixedChoLabelOffsetFromIobBottom

		val axisTickTop: Float
			get() = eventBottom + bottomTicksGap

		val axisTickBottom: Float
			get() = axisTickTop + bottomTickHeight

		val axisLabelY: Float
			get() = axisTickBottom + bottomLabelGap
	}


	object EventMarkers {
		// Marker event icons: all tuning centralized here.
		const val scale: Float = 0.9f

		val radius: Float = 15f * scale
		val outsideOffsetAboveLine: Float = 30f * scale
		val outsideOffsetBelowLine: Float = 28f * scale

		const val tipHalfWidthRatio: Float = 0.68f
		const val tipLengthRatio: Float = 0.74f
		val innerCircleInset: Float = 2.4f * scale

		val pauseBarOffsetX: Float = 4.2f * scale
		val pauseBarHalfHeight: Float = 7.0f * scale
		val pauseStrokeWidth: Float = 3.4f * scale

		val playLeftOffsetX: Float = 5.0f * scale
		val playHalfHeight: Float = 8.0f * scale
		val playRightOffsetX: Float = 8.0f * scale

		val cartridgeDropTop: Float = 9.2f * scale
		val cartridgeDropControlX: Float = 7.8f * scale
		val cartridgeDropControlY: Float = 2.0f * scale
		val cartridgeDropSideX: Float = 6.8f * scale
		val cartridgeDropSideY: Float = 4.4f * scale
		val cartridgeDropBottomControlX: Float = 5.6f * scale
		val cartridgeDropBottomY: Float = 10.0f * scale

		val xHalfSize: Float = 7.0f * scale
		val xStrokeWidth: Float = 3.4f * scale

		val exerciseHeadRadius: Float = 2.8f * scale
		val exerciseHeadOffsetX: Float = 3.3f * scale
		val exerciseHeadOffsetY: Float = -6.8f * scale
		val exerciseStrokeWidth: Float = 3.0f * scale
		val exerciseBodyStartX: Float = 1.2f * scale
		val exerciseBodyStartY: Float = -3.0f * scale
		val exerciseBodyEndX: Float = -2.4f * scale
		val exerciseBodyEndY: Float = 2.2f * scale
		val exerciseArmStartX: Float = -0.9f * scale
		val exerciseArmStartY: Float = -0.3f * scale
		val exerciseArmEndX: Float = -7.6f * scale
		val exerciseArmEndY: Float = -0.3f * scale
		val exerciseLeg1StartX: Float = -2.2f * scale
		val exerciseLeg1StartY: Float = 2.2f * scale
		val exerciseLeg1EndX: Float = -7.4f * scale
		val exerciseLeg1EndY: Float = 7.6f * scale
		val exerciseLeg2StartX: Float = -2.1f * scale
		val exerciseLeg2StartY: Float = 2.1f * scale
		val exerciseLeg2EndX: Float = 6.8f * scale
		val exerciseLeg2EndY: Float = 5.5f * scale

		val sleepTextSize: Float = 12.5f * scale
		val sleepTextBaselineOffset: Float = 4.6f * scale
	}

	// Compatibilità col codice esistente
	val canvasHeight: Dp
		get() = Chart.canvasHeight

	val canvasMinHeight: Dp
		get() = Chart.canvasMinHeight

	val canvasPreferredHeight: Dp
		get() = Chart.canvasPreferredHeight

	val canvasMaxHeight: Dp
		get() = Chart.canvasMaxHeight

	const val chartLeft: Float = Chart.chartLeft
	const val chartRightPadding: Float = Chart.chartRightPadding

	const val topDayBarTop: Float = Chart.topDayBarTop
	val topDayBarHeight: Float = Chart.topDayBarHeight
	const val topHoursGap: Float = Chart.topHoursGap

	const val iobTop: Float = Chart.iobTop
	const val iobHeight: Float = Chart.iobHeight

	const val cgmGapFromIob: Float = Chart.cgmGapFromIob
	const val cgmHeight: Float = Chart.cgmHeight

	const val basalGapFromCgm: Float = Chart.basalGapFromCgm
	const val basalHeight: Float = Chart.basalHeight

	const val eventGapFromBasal: Float = Chart.eventGapFromBasal
	val eventHeight: Float = Chart.eventHeight
	const val eventStatusLineRatio: Float = Chart.eventStatusLineRatio

	const val fixedBgLabelOffsetFromCgmTop: Float = Chart.fixedBgLabelOffsetFromCgmTop
	const val fixedChoLabelOffsetFromIobBottom: Float = Chart.fixedChoLabelOffsetFromIobBottom

	const val bottomTicksGap: Float = Chart.bottomTicksGap
	const val bottomTickHeight: Float = Chart.bottomTickHeight
	const val bottomLabelGap: Float = Chart.bottomLabelGap

	const val fontTopHours: Float = Chart.fontTopHours
	const val fontBottomHours: Float = Chart.fontBottomHours
	const val fontDay: Float = Chart.fontDay

	const val fontSectionLabel: Float = Chart.fontSectionLabel

	const val fontBolus: Float = Chart.fontBolus
	const val fontBg: Float = Chart.fontBg
	const val fontCho: Float = Chart.fontCho
	const val fontChoLegend: Float = Chart.fontChoLegend

	const val fontScaleLeft: Float = Chart.fontScaleLeft
	const val fontScaleRight: Float = Chart.fontScaleRight
	const val fontIobScale: Float = Chart.fontIobScale
	const val fontBasalScale: Float = Chart.fontBasalScale

	const val fontEvent: Float = Chart.fontEvent

	val topDayBarBottom: Float
		get() = Chart.topDayBarBottom

	val topHourRowY: Float
		get() = Chart.topHourRowY

	val iobBottom: Float
		get() = Chart.iobBottom

	val cgmTop: Float
		get() = Chart.cgmTop

	val cgmBottom: Float
		get() = Chart.cgmBottom

	val basalTop: Float
		get() = Chart.basalTop

	val basalBottom: Float
		get() = Chart.basalBottom

	val eventTop: Float
		get() = Chart.eventTop

	val eventBottom: Float
		get() = Chart.eventBottom

	val statusLineY: Float
		get() = Chart.statusLineY

	val fixedBgLabelY: Float
		get() = Chart.fixedBgLabelY

	val fixedChoLabelY: Float
		get() = Chart.fixedChoLabelY

	val axisTickTop: Float
		get() = Chart.axisTickTop

	val axisTickBottom: Float
		get() = Chart.axisTickBottom

	val axisLabelY: Float
		get() = Chart.axisLabelY
}
