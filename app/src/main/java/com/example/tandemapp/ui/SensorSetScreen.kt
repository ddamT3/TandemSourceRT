package com.example.tandemapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tandemapp.model.SensorSetData
import com.example.tandemapp.model.SensorSetUiState
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Displays the latest cached sensor and infusion-set snapshot. */
@Composable
fun SensorSetScreen(state: SensorSetUiState, modifier: Modifier = Modifier) {
	when (state) {
		SensorSetUiState.Idle,
		SensorSetUiState.Loading -> Column(
			modifier = modifier.fillMaxSize(),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center
		) {
			CircularProgressIndicator()
			Text("Loading sensor and infusion set data...", modifier = Modifier.padding(top = 12.dp))
		}

		is SensorSetUiState.Error -> Column(
			modifier = modifier.fillMaxSize().padding(16.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center
		) {
			Text(state.message, color = MaterialTheme.colorScheme.error)
		}

		is SensorSetUiState.Ready -> SensorSetContent(state.data, modifier)
	}
}

@Composable
private fun SensorSetContent(data: SensorSetData, modifier: Modifier) {
	LazyColumn(
		modifier = modifier.fillMaxSize().padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		item {
			SensorSetCard("Data source") {
				SensorSetLine(
					"Data timestamp",
					if (data.sourceTimestampIsPumpLocal || data.sourceTimestamp == data.dataTimestamp) {
						formatPumpLocalTime(data.sourceTimestamp ?: data.dataTimestamp)
					} else {
						formatSensorSetTime(data.sourceTimestamp ?: data.dataTimestamp)
					}
				)
			}
		}
		item {
			SensorSetCard("Sensor") {
				SensorSetLine("Type", data.sensorType ?: "Not available")
				SensorSetLine("Observed since", formatPumpLocalTime(data.sensorObservedSince))
				SensorSetLine("Estimated session end", formatPumpLocalTime(data.estimatedSensorEnd))
				Text(
					"Estimated from the first CGM reading observed by Tandem Source.",
					style = MaterialTheme.typography.bodySmall
				)
			}
		}
		item {
			SensorSetCard("Infusion set") {
				SensorSetLine("Last change", formatPumpLocalTime(data.lastSetChange))
				SensorSetLine("Set change reminder", formatPumpLocalTime(data.nextSetChangeDue))
				SensorSetLine(
					"Insulin remaining",
					data.remainingInsulinUnits?.let { "$it U" } ?: "Not available"
				)
				if (data.remainingInsulinTimestamp != null) {
					SensorSetLine(
						"Insulin timestamp",
						formatPumpLocalTime(data.remainingInsulinTimestamp)
					)
				}
			}
		}
	}
}

@Composable
private fun SensorSetCard(title: String, content: @Composable () -> Unit) {
	Card(modifier = Modifier.fillMaxWidth()) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
			content()
		}
	}
}

@Composable
private fun SensorSetLine(label: String, value: String) {
	androidx.compose.foundation.layout.Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween
	) {
		Text(label, modifier = Modifier.weight(1f))
		Text(value, fontWeight = FontWeight.Medium)
	}
}

private fun formatSensorSetTime(value: String?): String {
	if (value.isNullOrBlank()) return "Not available"
	val zone = ZoneId.systemDefault()
	val parsed = runCatching { Instant.parse(value).atZone(zone) }.getOrNull()
		?: runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone) }.getOrNull()
		?: runCatching { LocalDateTime.parse(value).atZone(zone) }.getOrNull()
		?: return value
	return parsed.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH))
}

private fun formatPumpLocalTime(value: String?): String {
	if (value.isNullOrBlank()) return "Not available"
	val localValue = value.removeSuffix("Z").substringBeforeLast('+').let {
		if (it.length > 10 && it.lastIndexOf('-') > 10) it.substring(0, it.lastIndexOf('-')) else it
	}
	val parsed = runCatching { LocalDateTime.parse(localValue) }.getOrNull() ?: return value
	return parsed.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH))
}
