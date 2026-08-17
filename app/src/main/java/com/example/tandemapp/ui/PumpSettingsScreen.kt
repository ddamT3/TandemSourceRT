package com.example.tandemapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.tandemapp.model.PumpProfile
import com.example.tandemapp.model.PumpProfileSegment
import com.example.tandemapp.model.PumpSettingsData
import com.example.tandemapp.model.PumpSettingsUiState
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PumpSettingsScreen(
	state: PumpSettingsUiState,
	modifier: Modifier = Modifier,
	onRetry: () -> Unit = {}
) {
	when (state) {
		PumpSettingsUiState.Idle,
		PumpSettingsUiState.Loading -> Column(
			modifier = modifier.fillMaxSize(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center
		) {
			CircularProgressIndicator()
			Spacer(Modifier.height(12.dp))
			Text("Loading pump settings...")
		}

		is PumpSettingsUiState.Error -> Column(
			modifier = modifier.fillMaxSize().padding(16.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center
		) {
			Text(state.message, color = MaterialTheme.colorScheme.error)
			Spacer(Modifier.height(12.dp))
			Button(onClick = onRetry) { Text("Retry") }
		}

		is PumpSettingsUiState.Ready -> PumpSettingsContent(state.data, modifier)
	}
}

@Composable
private fun PumpSettingsContent(data: PumpSettingsData, modifier: Modifier) {
	var selectedTab by rememberSaveable { mutableIntStateOf(0) }
	LaunchedEffect(data.serialNumber, data.profiles.size) { selectedTab = 0 }

	Column(modifier = modifier.fillMaxSize()) {
		ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
			Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("General") })
			data.profiles.forEachIndexed { index, profile ->
				Tab(
					selected = selectedTab == index + 1,
					onClick = { selectedTab = index + 1 },
					text = { Text(if (profile.isActive) "${profile.name} ●" else profile.name) }
				)
			}
		}

		if (selectedTab == 0) GeneralPumpSettings(data)
		else ProfileSettings(data.profiles[selectedTab - 1], data.glucoseUnit)
	}
}

@Composable
private fun GeneralPumpSettings(data: PumpSettingsData) {
	LazyColumn(
		modifier = Modifier.fillMaxSize().padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp)
	) {
		item {
			SettingsCard("Data source", collapsible = false) {
				SettingLine(
					"Data timestamp",
					formatPumpDataTimestamp(data.lastUploadDate ?: data.settingsTimestamp)
				)
				if (data.isFromCache) {
					Text("Available offline", color = MaterialTheme.colorScheme.primary)
				}
			}
		}
		item {
			SettingsCard("Pump") {
				SettingLine("Model", data.modelName)
				SettingLine("Serial number", data.serialNumber)
				data.softwareVersion?.let { SettingLine("Software", it) }
				data.algorithm?.let { SettingLine("Algorithm", it) }
				SettingLine("Active profile", data.activeProfileName ?: "Not available")
				SettingLine("Glucose unit", data.glucoseUnit)
			}
		}

		data.controlIqSettings?.let { controlIq ->
			item {
				SettingsCard("Control-IQ") {
					SettingLine("Status", enabledText(controlIq.closedLoop))
					controlIq.weight?.let { SettingLine("Configured weight", "$it kg") }
					controlIq.totalDailyInsulin?.let { SettingLine("Total daily insulin", "$it U") }
					val schedules = listOfNotNull(
						controlIq.sleepSchedule0, controlIq.sleepSchedule1,
						controlIq.sleepSchedule2, controlIq.sleepSchedule3
					).count { it.enabled }
					SettingLine("Active sleep schedules", schedules.toString())
				}
			}
		}

		item {
			SettingsCard("Limits and behavior") {
				data.basalLimitUnitsPerHour?.let { SettingLine("Maximum basal rate", "${format3(it)} U/h") }
				data.maxBolusUnits?.let { SettingLine("Maximum bolus", "${format2(it)} U") }
				data.pumpSettings?.lowInsulinThreshold?.let { SettingLine("Low insulin threshold", "$it U") }
				data.pumpSettings?.cannulaPrimeSize?.let { SettingLine("Cannula fill", "${format2(it / 100.0)} U") }
				SettingLine("Auto-off", enabledText(data.pumpSettings?.autoShutdownEnabled))
			}
		}

		data.cgmSettings?.let { cgm ->
			item {
				SettingsCard("CGM") {
					SettingLine("High alert", alertText(cgm.highGlucoseAlertEnabled, cgm.highGlucoseAlertMgPerDl, data.glucoseUnit))
					SettingLine("Low alert", alertText(cgm.lowGlucoseAlertEnabled, cgm.lowGlucoseAlertMgPerDl, data.glucoseUnit))
					SettingLine("Rise alert", enabledText(cgm.riseRateAlertEnabled))
					SettingLine("Fall alert", enabledText(cgm.fallRateAlertEnabled))
					SettingLine("Sensor timeout", if (cgm.sensorTimeoutEnabled == true) "${cgm.sensorTimeoutMinutes ?: 0} min" else "Disabled")
				}
			}
		}

		data.reminders?.let { reminders ->
			item {
				SettingsCard("Reminders") {
					SettingLine("Site change", if (reminders.siteChangeReminder?.enabled == true) "Every ${reminders.siteChangeDays ?: 0} days" else "Disabled")
					SettingLine("Low glucose", enabledText(reminders.lowBgReminder?.enabled))
					SettingLine("High glucose", enabledText(reminders.highBgReminder?.enabled))
					SettingLine("After bolus", enabledText(reminders.afterBolusReminder?.enabled))
					SettingLine("Missed bolus", if (reminders.missedBolusReminders.any { it.enabled }) "Enabled" else "Disabled")
				}
			}
		}
	}
}

private fun formatPumpDataTimestamp(value: String?): String {
	if (value.isNullOrBlank()) return "Not available"
	val zone = ZoneId.systemDefault()
	val dateTime = runCatching { Instant.parse(value).atZone(zone) }.getOrNull()
		?: runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone) }.getOrNull()
		?: runCatching { LocalDateTime.parse(value).atZone(zone) }.getOrNull()
		?: return value
	return dateTime.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH))
}

@Composable
private fun ProfileSettings(profile: PumpProfile, glucoseUnit: String) {
	LazyColumn(
		modifier = Modifier.fillMaxSize().padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp)
	) {
		item {
			SettingsCard(profile.name, collapsible = false) {
				if (profile.isActive) Text("● Active", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
				SettingLine("Total daily basal", "${format3(profile.dailyBasalUnits)} U")
				SettingLine("Insulin duration", durationText(profile.insulinDurationMinutes))
				SettingLine("Maximum bolus", "${format2(profile.maxBolusUnits)} U")
				SettingLine("Carbohydrates", if (profile.carbEntryEnabled) "Enabled" else "Disabled")
			}
		}
		item { ProfileSegmentsTable(profile.segments, glucoseUnit) }
	}
}

@Composable
private fun ProfileSegmentsTable(segments: List<PumpProfileSegment>, glucoseUnit: String) {
	Card(modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState())) {
			Row {
				TableCell("Time", 70.dp, true)
				TableCell("Basal U/h", 105.dp, true)
				TableCell("Correction", 105.dp, true)
				TableCell("I:C ratio", 115.dp, true)
				TableCell("Target $glucoseUnit", 120.dp, true)
			}
			HorizontalDivider()
			segments.forEach { segment ->
				Row {
					TableCell(minutesToTime(segment.startMinutes), 70.dp)
					TableCell(format3(segment.basalUnitsPerHour), 105.dp)
					TableCell("1:${segment.correctionFactor}", 105.dp)
					TableCell("1:${format1(segment.carbRatioGrams)}", 115.dp)
					TableCell(segment.targetMgDl.toString(), 120.dp)
				}
				HorizontalDivider()
			}
		}
	}
}

@Composable
private fun SettingsCard(
	title: String,
	collapsible: Boolean = true,
	content: @Composable ColumnScope.() -> Unit
) {
	var expanded by rememberSaveable(title) { mutableStateOf(true) }
	Card(modifier = Modifier.fillMaxWidth()) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
				if (collapsible) Text(if (expanded) "▲" else "▼")
			}
			if (!collapsible || expanded) content()
		}
	}
}

@Composable
private fun SettingLine(label: String, value: String) {
	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
		Spacer(Modifier.width(12.dp))
		Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
	}
}

@Composable
private fun TableCell(text: String, width: Dp, header: Boolean = false) {
	Text(
		text = text,
		modifier = Modifier.width(width).padding(horizontal = 4.dp, vertical = 8.dp),
		fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal
	)
}

private fun enabledText(value: Boolean?): String = when (value) {
	true -> "Enabled"
	false -> "Disabled"
	null -> "Not available"
}

private fun alertText(enabled: Boolean?, threshold: Int?, unit: String): String =
	if (enabled == true && threshold != null) "$threshold $unit" else enabledText(enabled)

private fun minutesToTime(value: Int): String = "%02d:%02d".format(value / 60, value % 60)
private fun durationText(value: Int): String = if (value % 60 == 0) "${value / 60} h" else "${value / 60} h ${value % 60} min"
private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
