package com.example.tandemapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tandemapp.st.BuildConfig
import java.util.Locale

@Composable
fun AppSettingsScreen(
	modifier: Modifier = Modifier,
	pumpSettingsExportMessage: String? = null,
	onDownloadPumpSettingsJson: () -> Unit = {}
) {
	Column(
		modifier = modifier.fillMaxSize().padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Card(modifier = Modifier.fillMaxWidth()) {
			Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
				Text("App version", style = MaterialTheme.typography.labelMedium)
				Text(
					text = String.format(
						Locale.US,
						"v%s.%03d",
						BuildConfig.VERSION_NAME,
						BuildConfig.VERSION_CODE % 1000
					),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Medium
				)
			}
		}
		Card(modifier = Modifier.fillMaxWidth()) {
			Column(
				modifier = Modifier.fillMaxWidth().padding(12.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp)
			) {
				Text("Pump settings diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
				Text(
					"Download pump settings and profiles as JSON to Download/TandemSourceRT.",
					style = MaterialTheme.typography.bodySmall
				)
				OutlinedButton(onClick = onDownloadPumpSettingsJson, modifier = Modifier.fillMaxWidth()) {
					Text("Download settings JSON")
				}
				if (!pumpSettingsExportMessage.isNullOrBlank()) {
					Text(pumpSettingsExportMessage, style = MaterialTheme.typography.bodySmall)
				}
			}
		}
	}
}
